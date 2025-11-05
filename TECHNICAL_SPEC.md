# Recurra Technical Specification

## System Overview

Recurra is an OpenAI-compatible proxy that implements intelligent caching with template-aware matching to serve deterministic responses instantly and guide AI systems down proven paths.

---

## Core Components

### 1. Ingress Layer (Spring Boot MVC + WebFlux)

**Endpoints**:
```
POST   /v1/chat/completions         (blocking)
GET    /v1/chat/completions/stream  (SSE)
GET    /v1/cache/stats
POST   /v1/cache/clear
GET    /admin/v1/cache/entries
GET    /admin/v1/cache/entries/{id}
GET    /health
```

**Request Processing**:
1. Parse OpenAI-compatible request
2. Extract cache control headers
3. Canonicalize request
4. Check cache (exact → template)
5. On miss: forward to provider
6. On hit: serve cached response
7. Add provenance headers

---

### 2. Cache Engine

#### 2.1 Exact Cache (Redis)

**Key Pattern**: `cache:exact:{tenant}:{sha256}`

**Value**: Gzipped JSON
```json
{
  "response": { /* ChatCompletionResponse */ },
  "metadata": {
    "created_at": "2025-11-05T10:30:00Z",
    "hit_count": 42,
    "source_model": "gpt-4",
    "mode": "text"
  }
}
```

**TTL**: Configurable per model family (default 24h)

**Operations**:
- `GET`: O(1), < 1ms typical
- `SET`: Async write-behind
- Eviction: Redis LFU

---

#### 2.2 Template Cache (PostgreSQL + pgvector)

**Schema**:
```sql
CREATE TABLE cache_entries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',

  -- Keys
  exact_key VARCHAR(64) NOT NULL UNIQUE,
  simhash BIGINT NOT NULL,

  -- Embedding
  embedding VECTOR(384),

  -- Content
  canonical_prompt TEXT NOT NULL,
  raw_prompt_hmac VARCHAR(64),
  request_json JSONB NOT NULL,
  response_json JSONB NOT NULL,

  -- Metadata
  model VARCHAR(128) NOT NULL,
  temperature_bucket VARCHAR(16),
  mode VARCHAR(32) NOT NULL,
  tool_schema_hash VARCHAR(64),

  -- Stats
  hit_count INTEGER DEFAULT 0,
  last_hit_at TIMESTAMP,

  -- Flags
  is_golden BOOLEAN DEFAULT false,
  pii_present BOOLEAN DEFAULT false,

  -- Timestamps
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMP,

  -- Extensible
  metadata JSONB
);

-- Indexes
CREATE INDEX idx_exact_key ON cache_entries(tenant_id, exact_key);
CREATE INDEX idx_simhash ON cache_entries(tenant_id, simhash);
CREATE INDEX idx_embedding_ivfflat ON cache_entries
  USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 100);
CREATE INDEX idx_model_mode ON cache_entries(tenant_id, model, mode);
CREATE INDEX idx_golden ON cache_entries(tenant_id) WHERE is_golden = true;
```

**Query Flow**:
```sql
-- 1. SimHash bucket candidates (Hamming ≤ 6)
WITH buckets AS (
  SELECT id, simhash,
         bit_count(simhash # $1) AS hamming_distance
  FROM cache_entries
  WHERE tenant_id = $2
    AND mode = $3
    AND model_family = $4
  HAVING hamming_distance <= 6
  LIMIT 100
)
-- 2. Semantic similarity (cosine ≤ 0.15)
SELECT e.*,
       1 - (e.embedding <=> $5) AS cosine_similarity
FROM cache_entries e
JOIN buckets b ON e.id = b.id
WHERE e.embedding <=> $5 < 0.15
ORDER BY cosine_similarity DESC
LIMIT 10;
```

**Performance Target**: < 100ms p95

---

### 3. Canonicalization Pipeline

#### 3.1 Request Canonicalization

**Steps**:
1. **Sort JSON keys** (recursive, stable ordering)
2. **Remove defaults**:
   - `temperature: null` → omit
   - `stream: false` → omit
3. **Round floats**: `0.7000000001` → `0.70`
4. **Normalize messages**:
   - Trim whitespace
   - Collapse multiple spaces
   - Remove trailing punctuation variance

**Example**:
```json
// Input
{"temperature":0.7000001,"model":"gpt-4","messages":[{"role":"user","content":"Hello!  "}]}

// Canonical
{"messages":[{"content":"Hello!","role":"user"}],"model":"gpt-4","temperature":0.70}
```

**Hash**: SHA-256 → 64-char hex string

---

#### 3.2 Prompt Masking

**Patterns**:
```
<NUM>     : \b\d+(\.\d+)?\b
<DATE>    : \d{4}-\d{2}-\d{2}
<URL>     : https?://[^\s]+
<EMAIL>   : [a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}
<UUID>    : [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}
<PERSON>  : Capitalized Name (NER regex)
<ORG>     : Capitalized Org (NER regex)
```

**Example**:
```
Input:
"Summarize this article: https://example.com/article-12345
 Published on 2024-11-05 by John Doe"

Masked:
"Summarize this article: <URL>
 Published on <DATE> by <PERSON>"
```

**Code Identifier Masking**:
```python
# Input
def calculate_total(price, quantity):
    return price * quantity

# Masked
def <FUNC>(<VAR>, <VAR>):
    return <VAR> * <VAR>
```

**Privacy Mode**:
- Store: `masked_prompt` + `HMAC-SHA256(raw_prompt)`
- HMAC enables deduplication without storing PII
- Raw prompt never persisted

---

#### 3.3 SimHash Generation

**Algorithm**:
```java
public class SimHashGenerator {
    public long generate(String text) {
        // 1. Tokenize (word + 3-grams)
        List<String> tokens = tokenize(text);

        // 2. Initialize accumulator
        int[] vector = new int[64];

        // 3. For each token
        for (String token : tokens) {
            long hash = MurmurHash3.hash64(token);
            int weight = computeWeight(token);  // TF-IDF-like

            for (int i = 0; i < 64; i++) {
                if ((hash & (1L << i)) != 0) {
                    vector[i] += weight;
                } else {
                    vector[i] -= weight;
                }
            }
        }

        // 4. Generate fingerprint
        long fingerprint = 0;
        for (int i = 0; i < 64; i++) {
            if (vector[i] > 0) {
                fingerprint |= (1L << i);
            }
        }

        return fingerprint;
    }
}
```

**Properties**:
- Similar texts → similar fingerprints
- Hamming distance ≤ 6 → candidate match
- Generation: < 5ms p95

---

### 4. Embedding Service

#### 4.1 Local ONNX Model (e5-small-v2)

**Specifications**:
- Model: `intfloat/e5-small-v2`
- Dimensions: 384
- Format: ONNX (optimized for CPU)
- Size: ~120 MB

**API**:
```java
public interface EmbeddingService {
    float[] embed(String text);
    int dimensions();
}

@Service
public class LocalOnnxEmbeddingService implements EmbeddingService {
    private final OrtEnvironment env;
    private final OrtSession session;

    public float[] embed(String text) {
        // 1. Tokenize (WordPiece)
        int[] tokenIds = tokenizer.encode(text, maxLength=512);

        // 2. Run ONNX inference
        OnnxTensor inputTensor = OnnxTensor.createTensor(env, tokenIds);
        Result result = session.run(Map.of("input_ids", inputTensor));

        // 3. Mean pooling
        float[][] hidden = result.get(0).getValue();
        float[] embedding = meanPool(hidden);

        // 4. L2 normalize
        return normalize(embedding);
    }
}
```

**Performance**:
- Latency: < 50ms p95 (CPU)
- Throughput: 200 embeds/sec (batch=1)
- Memory: ~500 MB loaded

**Fallback**: `RemoteEmbeddingService` (OpenAI text-embedding-3-small)

---

### 5. Similarity Scoring

#### 5.1 Composite Score Formula

```
score = 0.4 * structural_score
      + 0.4 * semantic_score
      + 0.1 * param_score
      + 0.1 * recency_score

where:
  structural_score = 1 - (hamming_distance / 64)
  semantic_score   = 1 - cosine_distance
  param_score      = 1 - |temp_req - temp_cached|
  recency_score    = exp(-age_hours / 168)  // decay over 1 week
```

**Example**:
```
Request: "Summarize https://news.com/article-456"
Cached:  "Summarize https://blog.org/post-789"

Structural: Hamming(simhash_req, simhash_cached) = 2
  → structural_score = 1 - 2/64 = 0.969

Semantic: cosine_distance(emb_req, emb_cached) = 0.05
  → semantic_score = 1 - 0.05 = 0.950

Params: temp_req=0.7, temp_cached=0.7
  → param_score = 1.0

Recency: cached 12 hours ago
  → recency_score = exp(-12/168) = 0.932

Composite: 0.4*0.969 + 0.4*0.950 + 0.1*1.0 + 0.1*0.932 = 0.961

Threshold: 0.80 → ACCEPT ✓
```

---

#### 5.2 Guardrails

**Model Compatibility**:
```yaml
# strict: exact model match
gpt-4-0613 ≠ gpt-4-turbo

# family: same base model
gpt-4-0613 == gpt-4-turbo (both gpt-4 family)

# any: any model (dangerous, opt-in)
gpt-4 == gpt-3.5-turbo
```

**Mode Compatibility**:
```java
public enum RequestMode {
    TEXT,         // plain chat
    JSON_OBJECT,  // response_format.type = json_object
    JSON_SCHEMA,  // response_format.json_schema != null
    TOOLS,        // tools != null
    FUNCTION      // functions != null (legacy)
}

// Rule: cached.mode MUST equal request.mode
if (cached.mode != request.mode) {
    return CacheResult.MISS;
}
```

**Tool Schema Compatibility**:
```java
String computeToolSchemaHash(List<Tool> tools) {
    if (tools == null || tools.isEmpty()) {
        return "none";
    }

    // Canonical JSON of tool definitions
    JsonNode canonical = canonicalize(tools);
    String json = objectMapper.writeValueAsString(canonical);

    // SHA-256 hash
    return DigestUtils.sha256Hex(json);
}

// Rule: if request has tools, hash must match
if (request.tools != null) {
    if (!cached.toolSchemaHash.equals(computeHash(request.tools))) {
        return CacheResult.MISS;
    }
}
```

**JSON Schema Validation**:
```java
if (request.responseFormat.type == "json_schema") {
    JsonSchema schema = request.responseFormat.jsonSchema;
    JsonNode cachedResponse = cached.response.choices[0].message.content;

    ValidationResult result = schemaValidator.validate(schema, cachedResponse);

    if (!result.isValid()) {
        log.warn("Cached response invalid for schema: {}", result.errors);
        return CacheResult.MISS;
    }
}
```

---

### 6. Streaming & Replay

#### 6.1 SSE Passthrough (Cache Miss)

**Flow**:
```java
public Flux<ServerSentEvent<String>> forwardStreaming(ChatCompletionRequest req) {
    return webClient.post()
        .uri(providerUrl)
        .bodyValue(req)
        .retrieve()
        .bodyToFlux(String.class)
        .doOnNext(chunk -> streamBuffer.append(chunk))
        .doOnComplete(() -> cacheService.storeBehind(req, streamBuffer.toResponse()))
        .map(chunk -> ServerSentEvent.builder(chunk).build());
}
```

**Buffering**:
- Capture all chunks in memory
- On `[DONE]`, reconstruct full `ChatCompletionResponse`
- Async write to Redis + Postgres
- Write failures logged but don't block stream

---

#### 6.2 Deterministic Replay (Cache Hit)

**Algorithm**:
```java
public Flux<ServerSentEvent<String>> replayFromCache(
    ChatCompletionResponse cached,
    String cacheKey
) {
    // 1. Seed PRNG with cache key
    Random random = new Random(cacheKey.hashCode());

    // 2. Generate stable chunks
    List<String> chunks = deterministicChunker.chunk(
        cached.choices[0].message.content,
        random
    );

    // 3. Generate inter-chunk delays
    List<Duration> delays = chunks.stream()
        .map(c -> Duration.ofMillis((int) random.nextGaussian(50, 20)))
        .toList();

    // 4. Stream with pacing
    return Flux.fromIterable(chunks)
        .zipWith(Flux.fromIterable(delays))
        .delayElements(delay -> Mono.delay(delay._2))
        .map(tuple -> formatSseChunk(tuple._1))
        .concatWith(Flux.just(formatSseDone()));
}
```

**Chunking Strategy**:
```java
public List<String> chunk(String content, Random random) {
    List<String> chunks = new ArrayList<>();
    String[] words = content.split(" ");

    int position = 0;
    while (position < words.length) {
        // Gaussian distribution: mean=15 words, σ=5
        int chunkSize = Math.max(1, (int) random.nextGaussian(15, 5));
        int end = Math.min(position + chunkSize, words.length);

        String chunk = String.join(" ", Arrays.copyOfRange(words, position, end));
        chunks.add(chunk);

        position = end;
    }

    return chunks;
}
```

**Properties**:
- Same cache key → identical chunks every time
- Natural pacing (mean 50ms, σ 20ms)
- First token: < 30ms
- Feels human-like

---

### 7. Resilience & Degradation

#### 7.1 Circuit Breakers

**Configuration**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      redis:
        failure-rate-threshold: 50
        slow-call-rate-threshold: 50
        slow-call-duration-threshold: 2s
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 5

      postgres:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s

      provider:
        failure-rate-threshold: 80
        wait-duration-in-open-state: 60s
```

**States**:
- **CLOSED**: Normal operation
- **OPEN**: Too many failures, skip component
- **HALF_OPEN**: Testing recovery, limited calls

---

#### 7.2 Degradation Modes

**Mode Matrix**:
| Redis | Postgres | Embeddings | Mode | Behavior |
|-------|----------|------------|------|----------|
| ✅ | ✅ | ✅ | **Full** | Exact + Template cache |
| ✅ | ❌ | ❌ | **Exact Only** | Redis cache only, no template |
| ❌ | ✅ | ✅ | **Template Only** | Skip exact, use template |
| ❌ | ❌ | ❌ | **Pass-Through** | Direct to provider |

**Headers**:
```
x-cache-degraded: true
x-cache-degraded-reason: redis-unavailable
x-cache-available-modes: template
```

**Health Endpoint**:
```json
{
  "status": "DEGRADED",
  "components": {
    "redis": { "status": "DOWN", "details": "Connection timeout" },
    "postgres": { "status": "UP" },
    "embeddings": { "status": "UP" }
  },
  "cache_mode": "template_only"
}
```

---

### 8. Observability

#### 8.1 Metrics (Prometheus)

**Cache Metrics**:
```
# Counters
cache_requests_total{type="exact|template|miss"}
cache_hits_total{type="exact|template"}
cache_misses_total

# Histograms
cache_lookup_duration_seconds{type="exact|template"}
cache_score_distribution{bucket="0.0-0.1|0.1-0.2|...|0.9-1.0"}

# Gauges
cache_size{store="redis|postgres"}
cache_hit_rate{window="1m|5m|1h"}
```

**Request Metrics**:
```
http_requests_total{endpoint="/v1/chat/completions", status="200|429|500"}
http_request_duration_seconds{endpoint, percentile="p50|p95|p99"}
streaming_connections_active
```

**Provider Metrics**:
```
provider_requests_total{provider="openai|anthropic", status}
provider_errors_total{provider, error_type="timeout|5xx|rate_limit"}
provider_latency_seconds{provider, percentile}
```

---

#### 8.2 Traces (OpenTelemetry)

**Span Hierarchy**:
```
http.request [/v1/chat/completions]
├─ cache.exact_lookup [Redis]
│  └─ redis.get [< 1ms]
├─ cache.template_search [Postgres]
│  ├─ simhash.buckets [10ms]
│  ├─ pgvector.ann_search [50ms]
│  └─ scoring.composite [5ms]
├─ embedding.generate [45ms]
│  └─ onnx.inference [40ms]
└─ provider.forward [OpenAI]
   ├─ http.post [1500ms]
   └─ cache.write_behind [async, 20ms]
```

**Attributes**:
```
span.setAttribute("cache.key", sha256Key);
span.setAttribute("cache.hit", true);
span.setAttribute("cache.type", "template");
span.setAttribute("cache.score", 0.87);
span.setAttribute("provider", "openai");
span.setAttribute("model", "gpt-4");
```

---

### 9. Security

#### 9.1 Secrets Management

**Environment Variables**:
```bash
# Provider keys
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...

# Database
DATABASE_URL=postgresql://user:pass@localhost:5432/recurra
DATABASE_ENCRYPTION_KEY=base64:...  # AES-256 key

# Redis
REDIS_URL=redis://localhost:6379
REDIS_PASSWORD=...

# Optional: Vault
VAULT_ADDR=https://vault.example.com
VAULT_TOKEN=s.xyz...
```

**Vault Integration (Optional)**:
```java
@Configuration
public class VaultConfig {
    @Bean
    public VaultTemplate vaultTemplate() {
        VaultEndpoint endpoint = VaultEndpoint.create(vaultAddr, 8200);
        TokenAuthentication auth = new TokenAuthentication(vaultToken);
        return new VaultTemplate(endpoint, auth);
    }

    @Bean
    public String openaiApiKey(VaultTemplate vault) {
        VaultResponse response = vault.read("secret/data/recurra/openai");
        return response.getData().get("api_key");
    }
}
```

---

#### 9.2 Data Encryption

**At Rest** (Postgres):
```sql
-- Encrypted column for raw prompts (if enabled)
CREATE EXTENSION pgcrypto;

CREATE TABLE cache_entries (
  ...
  raw_prompt_encrypted BYTEA,  -- AES-256-GCM
  raw_prompt_nonce BYTEA,      -- 12-byte nonce
  ...
);

-- Encrypt on write
INSERT INTO cache_entries (raw_prompt_encrypted, raw_prompt_nonce, ...)
VALUES (
  pgp_sym_encrypt('raw prompt', :encryption_key, 'cipher-algo=aes256'),
  gen_random_bytes(12),
  ...
);

-- Decrypt on read (admin only)
SELECT pgp_sym_decrypt(raw_prompt_encrypted, :encryption_key) AS raw_prompt
FROM cache_entries WHERE id = :id;
```

**In Transit**:
- TLS 1.3 for all external connections
- Redis TLS (optional, via stunnel for OSS Redis)
- Postgres SSL mode: `require`

---

#### 9.3 Rate Limiting

**Bucket4j** (Token Bucket Algorithm):
```java
@Configuration
public class RateLimitConfig {
    @Bean
    public RateLimiter defaultRateLimiter() {
        Bandwidth limit = Bandwidth.builder()
            .capacity(100)              // burst capacity
            .refillGreedy(10, Duration.ofSeconds(1))  // 10 req/sec steady
            .build();

        return Bucket4j.builder()
            .addLimit(limit)
            .build();
    }
}

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, ...) {
        String apiKey = request.getHeader("Authorization");
        RateLimiter limiter = getRateLimiterForKey(apiKey);

        if (!limiter.tryConsume(1)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            return false;
        }

        return true;
    }
}
```

---

### 10. Deployment

#### 10.1 Docker Compose (MVP)

```yaml
version: '3.9'

services:
  app:
    image: recurra:latest
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - REDIS_URL=redis://redis:6379
      - DATABASE_URL=postgresql://postgres:5432/recurra
      - OPENAI_API_KEY=${OPENAI_API_KEY}
    depends_on:
      - redis
      - postgres
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 10s
      timeout: 5s
      retries: 3

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    command: redis-server --maxmemory 8gb --maxmemory-policy allkeys-lfu

  postgres:
    image: pgvector/pgvector:pg16
    ports:
      - "5432:5432"
    environment:
      - POSTGRES_DB=recurra
      - POSTGRES_USER=recurra
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data

  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD}
    volumes:
      - grafana_data:/var/lib/grafana
      - ./grafana/dashboards:/etc/grafana/provisioning/dashboards

volumes:
  redis_data:
  postgres_data:
  prometheus_data:
  grafana_data:
```

**Startup**:
```bash
cp .env.example .env
# Edit .env with API keys
docker-compose up -d
```

---

#### 10.2 Kubernetes (Production)

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: recurra
spec:
  replicas: 3
  selector:
    matchLabels:
      app: recurra
  template:
    metadata:
      labels:
        app: recurra
    spec:
      containers:
      - name: recurra
        image: recurra:v0.1.0
        ports:
        - containerPort: 8080
        env:
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: recurra-secrets
              key: openai-api-key
        resources:
          requests:
            cpu: 2000m
            memory: 4Gi
          limits:
            cpu: 4000m
            memory: 8Gi
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
```

---

## Performance Characteristics

### Latency Targets
| Operation | p50 | p95 | p99 |
|-----------|-----|-----|-----|
| Exact cache hit | 5ms | 15ms | 30ms |
| Template cache hit | 40ms | 80ms | 150ms |
| Cache miss (provider) | 1000ms | 2500ms | 5000ms |
| Embedding generation | 30ms | 50ms | 80ms |
| SimHash generation | 2ms | 5ms | 10ms |

### Throughput Targets
- **Cache hits**: 5,000 RPS/node (Redis bottleneck)
- **Cache misses**: Provider-limited (~100 RPS to OpenAI)
- **Mixed (80% hit)**: 2,000 RPS sustained

### Storage Capacity
- **Redis**: 10,000 entries × 5KB avg = 50 MB
- **Postgres**: 1,000,000 entries × 10KB avg = 10 GB
- **Embeddings**: 1,000,000 × 384 floats × 4 bytes = 1.5 GB

---

## API Contract

### Request Headers
```
Authorization: Bearer sk-...         (provider API key)
Content-Type: application/json

# Cache control (optional)
x-cache-bypass: true|false
x-cache-store: true|false
x-cache-mode: exact|template|both
x-model-compat: strict|family|any
x-cache-experiment: control|test
```

### Response Headers
```
# Provenance
x-cache-hit: true|false
x-cache-match: exact|template|none
x-cache-score: 0.87
x-cache-provenance: 550e8400-e29b-41d4-a716-446655440000
x-cache-source-model: gpt-4-0613
x-cache-age: 3600

# Degradation
x-cache-degraded: false
x-cache-available-modes: exact,template

# Performance
x-response-time-ms: 25
```

### Error Responses
```json
// 429 Rate Limit
{
  "error": {
    "message": "Rate limit exceeded",
    "type": "rate_limit_error",
    "code": "rate_limit_exceeded"
  }
}

// 500 Internal Error
{
  "error": {
    "message": "Cache lookup failed: Redis timeout",
    "type": "internal_error",
    "code": "cache_unavailable"
  }
}

// Provider errors passed through unchanged
```

---

## Testing Strategy

### Unit Tests
- All services: >80% coverage
- Canonicalization: golden dataset
- SimHash: collision tests
- Scoring: edge cases

### Integration Tests
- Testcontainers (Redis, Postgres)
- Full request flow
- Degradation modes
- Header handling

### Compatibility Tests
- OpenAI SDK: Python, Node.js
- Streaming format validation
- Tool calling scenarios

### Load Tests (k6)
- Pure hits: 5k RPS target
- Mixed 80/20: 2k RPS target
- Streaming: 500 concurrent

### Chaos Tests
- Redis/Postgres failures
- Network partitions
- Provider timeouts

---

**This spec serves as the authoritative technical reference for Recurra MVP implementation.**
