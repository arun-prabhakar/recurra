# Recurra MVP Implementation Plan

## Executive Summary

**Current Status**: Basic in-memory caching with Jaro-Winkler similarity matching
**MVP Target**: Production-ready OpenAI-compatible proxy with advanced template-aware caching, streaming support, and guardrails
**Estimated Effort**: 8-12 weeks (1 senior engineer)
**Critical Path**: Storage Layer → Streaming → Advanced Matching → Guardrails

---

## Phase 1: Infrastructure & Storage (Week 1-2)

### 1.1 Redis Integration for Exact Cache
**Epic**: B1 (Must Have)
**Current**: In-memory `ConcurrentHashMap`
**Target**: Redis with compression and write-behind

**Tasks**:
- [ ] Add Redis dependencies (Lettuce, Spring Data Redis)
- [ ] Create `RedisExactCacheRepository`
  - Key pattern: `cache:exact:{sha256}`
  - Value: Gzipped JSON of `ChatCompletionResponse`
  - TTL support per model family
- [ ] Implement write-behind pattern
  - Buffer response during streaming
  - Async write to Redis after completion
  - Handle write failures gracefully
- [ ] Add Redis connection health checks
- [ ] Configuration: connection pool, timeouts, retry policy

**Files to Create/Modify**:
```
src/main/java/com/recurra/
├── repository/
│   ├── RedisExactCacheRepository.java
│   └── CacheWriteBehindBuffer.java
├── config/
│   └── RedisConfiguration.java
└── service/
    └── TemplateCacheService.java (modify)
```

**Acceptance Criteria**:
- Exact cache hit latency < 30ms p95
- Write-behind doesn't block client response
- Redis down → graceful pass-through mode
- Unit tests with embedded Redis (Testcontainers)

---

### 1.2 PostgreSQL + pgvector for Template Matching
**Epic**: B2 (Must Have)
**Current**: None
**Target**: Postgres with pgvector for semantic similarity

**Tasks**:
- [ ] Add dependencies (PostgreSQL, pgvector JDBC, Flyway)
- [ ] Database schema design:
  ```sql
  -- cache_entries table
  CREATE TABLE cache_entries (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    exact_key VARCHAR(64) NOT NULL,  -- SHA256
    simhash BIGINT NOT NULL,         -- 64-bit structural hash
    embedding VECTOR(384),            -- semantic vector
    canonical_prompt TEXT NOT NULL,   -- masked/normalized
    raw_prompt_hmac VARCHAR(64),      -- for dedupe
    request_json JSONB NOT NULL,      -- full request
    response_json JSONB NOT NULL,     -- cached response
    model VARCHAR(128) NOT NULL,
    temperature_bucket VARCHAR(16),   -- "low", "medium", "high"
    mode VARCHAR(32) NOT NULL,        -- "text", "json", "tools"
    tool_schema_hash VARCHAR(64),     -- checksum of tools
    hit_count INTEGER DEFAULT 0,
    last_hit_at TIMESTAMP,
    is_golden BOOLEAN DEFAULT false,
    pii_present BOOLEAN DEFAULT false,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    metadata JSONB
  );

  -- Indexes
  CREATE INDEX idx_exact_key ON cache_entries(tenant_id, exact_key);
  CREATE INDEX idx_simhash ON cache_entries(tenant_id, simhash);
  CREATE INDEX idx_embedding_ivfflat ON cache_entries
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
  CREATE INDEX idx_model_mode ON cache_entries(tenant_id, model, mode);
  CREATE INDEX idx_expires_at ON cache_entries(expires_at)
    WHERE expires_at IS NOT NULL;
  ```
- [ ] Flyway migrations setup
- [ ] Create `CacheEntryEntity` JPA entity
- [ ] Create `CacheEntryRepository` with custom queries
- [ ] Implement candidate filtering:
  - SimHash bucket query (Hamming distance ≤ 6)
  - pgvector ANN query (cosine distance ≤ 0.15)
  - Composite scoring formula

**Files to Create**:
```
src/main/java/com/recurra/
├── entity/
│   └── CacheEntryEntity.java
├── repository/
│   └── CacheEntryRepository.java (JPA)
└── service/
    └── VectorSearchService.java

src/main/resources/
└── db/migration/
    ├── V1__initial_schema.sql
    └── V2__add_pgvector_extension.sql
```

**Acceptance Criteria**:
- Template search returns top 10 candidates in < 100ms
- pgvector ANN index built successfully
- Composite scoring implemented correctly
- Integration tests with Testcontainers Postgres

---

### 1.3 Embeddings Service (Local)
**Epic**: B2, J2 (Must Have)
**Current**: None
**Target**: Local embedding model (e5-small) via ONNX

**Tasks**:
- [ ] Add ONNX Runtime dependency (DJL or direct)
- [ ] Download and package e5-small-v2 model (384 dims)
- [ ] Create `EmbeddingService` interface:
  ```java
  public interface EmbeddingService {
    float[] embed(String text);
    int dimensions();
  }
  ```
- [ ] Implement `LocalOnnxEmbeddingService`
  - Model loading on startup
  - Tokenization (WordPiece)
  - Batch inference support
  - Thread-safe pooling
- [ ] Create fallback `RemoteEmbeddingService` (OpenAI/Cohere)
- [ ] Configuration: model path, batch size, timeout

**Files to Create**:
```
src/main/java/com/recurra/
├── service/embedding/
│   ├── EmbeddingService.java (interface)
│   ├── LocalOnnxEmbeddingService.java
│   └── RemoteEmbeddingService.java
└── config/
    └── EmbeddingConfiguration.java

src/main/resources/
└── models/
    └── e5-small-v2.onnx
```

**Acceptance Criteria**:
- Embedding generation < 50ms p95 for 512 tokens
- Model loads successfully on startup
- SPI allows swapping to remote provider
- Unit tests with sample prompts

---

## Phase 2: Canonicalization & Key Generation (Week 2-3)

### 2.1 Request Canonicalization
**Epic**: E1 (Must Have)
**Current**: Basic normalization
**Target**: Complete canonical form generation

**Tasks**:
- [ ] Implement `RequestCanonicalizer`:
  - Sort JSON keys recursively
  - Remove provider-specific defaults
  - Round floats to 2 decimals
  - Normalize whitespace in strings
  - Handle null vs missing field equivalence
- [ ] Enhanced template masking:
  - `<NUM>`, `<DATE>`, `<URL>`, `<EMAIL>` (already done)
  - `<PERSON>`, `<ORG>` using simple NER (regex patterns)
  - Code identifier masking (variable names in code blocks)
  - Preserve Markdown structure (headings, lists, code fences)
- [ ] Create `MaskedPrompt` value object:
  ```java
  public record MaskedPrompt(
    String masked,           // template with placeholders
    String rawHmac,          // HMAC-SHA256 of raw text
    Map<String, String> metadata  // placeholder mappings
  ) {}
  ```
- [ ] Implement `PromptMasker` service

**Files to Create/Modify**:
```
src/main/java/com/recurra/
├── service/canonicalization/
│   ├── RequestCanonicalizer.java
│   ├── PromptMasker.java
│   └── MaskedPrompt.java
└── service/
    └── TemplateExtractor.java (refactor)
```

**Acceptance Criteria**:
- Same logical request → same canonical JSON
- Masked prompts preserve semantic meaning
- HMAC allows dedupe without storing raw PII
- Golden test suite with 50+ variations

---

### 2.2 SimHash Implementation
**Epic**: B2 (Must Have)
**Current**: None
**Target**: 64-bit SimHash for structural similarity

**Tasks**:
- [ ] Implement `SimHashGenerator`:
  - Tokenize canonical prompt (word-level + shingles)
  - Hash each token (MurmurHash3)
  - Accumulate weighted bit vectors
  - Generate 64-bit fingerprint
- [ ] Hamming distance calculator
- [ ] Bucket strategy:
  - Use top 16 bits for Redis bucket key
  - Query buckets within ±1 bit flip range
- [ ] Optimize for speed (< 5ms per prompt)

**Files to Create**:
```
src/main/java/com/recurra/
└── service/similarity/
    ├── SimHashGenerator.java
    ├── HammingDistance.java
    └── SimHashBucketStrategy.java
```

**Acceptance Criteria**:
- Similar prompts have Hamming distance < 6
- Generation < 5ms p95
- Collision rate < 1% for diverse prompts
- Unit tests with known similar/dissimilar pairs

---

### 2.3 Composite Scoring
**Epic**: B2 (Must Have)
**Current**: Jaro-Winkler only
**Target**: Multi-factor scoring with threshold

**Tasks**:
- [ ] Implement `CompositeScorer`:
  ```java
  public record CandidateScore(
    String cacheId,
    double structuralScore,  // 1 - hamming/64
    double semanticScore,    // 1 - cosine
    double paramScore,       // temperature, top_p closeness
    double recencyScore,     // time decay
    double composite         // weighted sum
  ) {}
  ```
- [ ] Scoring formula:
  ```
  composite = 0.4 * structural
            + 0.4 * semantic
            + 0.1 * param
            + 0.1 * recency
  ```
- [ ] Configurable threshold (default 0.80)
- [ ] Tie-breaking logic (prefer newer entries)

**Files to Create**:
```
src/main/java/com/recurra/
└── service/similarity/
    ├── CompositeScorer.java
    ├── CandidateScore.java
    └── ScoringWeights.java (config)
```

**Acceptance Criteria**:
- Score calculation < 1ms per candidate
- Threshold rejection works correctly
- Configuration allows weight tuning
- Unit tests with edge cases

---

## Phase 3: Streaming & Deterministic Replay (Week 3-4)

### 3.1 SSE Streaming for Cache Miss
**Epic**: A2 (Must Have)
**Current**: Blocking request/response
**Target**: SSE passthrough with buffering

**Tasks**:
- [ ] Modify `ProviderService` to support streaming:
  ```java
  public Flux<ServerSentEvent<String>> forwardStreaming(
    ChatCompletionRequest request
  )
  ```
- [ ] Implement `StreamBuffer`:
  - Capture chunks while streaming to client
  - Reconstruct full response on `[DONE]`
  - Trigger write-behind to cache
- [ ] Update `ChatController` to handle `stream=true`:
  ```java
  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> createChatCompletionStream(...)
  ```
- [ ] Error handling: provider disconnect, client disconnect
- [ ] Backpressure handling

**Files to Modify/Create**:
```
src/main/java/com/recurra/
├── controller/
│   └── ChatController.java (add streaming endpoint)
├── service/
│   ├── ProviderService.java (add streaming)
│   └── StreamBuffer.java
└── model/
    └── StreamChunk.java
```

**Acceptance Criteria**:
- SSE events arrive in real-time
- Full response cached after `[DONE]`
- Client disconnect doesn't crash server
- Works with OpenAI Python SDK streaming

---

### 3.2 Deterministic SSE Replay from Cache
**Epic**: A2 (Must Have)
**Current**: None
**Target**: Stable chunking and pacing from cache hit

**Tasks**:
- [ ] Implement `DeterministicChunker`:
  - Split cached response into chunks (word boundaries)
  - Use cache key as seed for PRNG
  - Generate stable chunk sizes (Gaussian distribution)
  - Mean: 15 tokens, σ: 5 tokens
- [ ] Implement `DeterministicPacer`:
  - Generate inter-chunk delays from seeded PRNG
  - Mean: 50ms, σ: 20ms
  - Use `Flux.delayElements()` with scheduler
- [ ] Create `SseReplayService`:
  ```java
  public Flux<ServerSentEvent<String>> replayFromCache(
    ChatCompletionResponse cached,
    String cacheKey
  )
  ```
- [ ] Format as OpenAI SSE chunks:
  ```
  data: {"id":"...","choices":[{"delta":{"content":"chunk"},"index":0}]}

  data: [DONE]
  ```

**Files to Create**:
```
src/main/java/com/recurra/
└── service/streaming/
    ├── DeterministicChunker.java
    ├── DeterministicPacer.java
    └── SseReplayService.java
```

**Acceptance Criteria**:
- Same cache key → identical chunking every time
- Pacing feels natural (not too fast/slow)
- Terminates with `[DONE]`
- Integration test: replay twice, compare output

---

## Phase 4: Guardrails & Compatibility (Week 4-5)

### 4.1 Model Compatibility Policies
**Epic**: B3 (Must Have)
**Current**: None
**Target**: Strict/family/any matching modes

**Tasks**:
- [ ] Define model families:
  ```yaml
  model_families:
    gpt-4:
      - gpt-4
      - gpt-4-turbo
      - gpt-4-0613
      - gpt-4-1106-preview
    gpt-3.5:
      - gpt-3.5-turbo
      - gpt-3.5-turbo-16k
  ```
- [ ] Implement `ModelCompatibilityChecker`:
  - `strict`: exact model name match
  - `family`: same family (gpt-4 variants)
  - `any`: any model (dangerous)
- [ ] Add to composite scoring as filter
- [ ] Configuration: global default + header override

**Files to Create**:
```
src/main/java/com/recurra/
├── service/compatibility/
│   ├── ModelCompatibilityChecker.java
│   └── ModelFamily.java
└── config/
    └── model-families.yml
```

**Acceptance Criteria**:
- Strict mode rejects family matches
- Family mode allows gpt-4 → gpt-4-turbo
- Header override works per request
- Unit tests for all policies

---

### 4.2 Mode & Schema Validation
**Epic**: B3, K1, K2 (Must Have)
**Current**: None
**Target**: Prevent incompatible spoofs

**Tasks**:
- [ ] Detect request mode:
  ```java
  public enum RequestMode {
    TEXT,              // plain chat
    JSON_OBJECT,       // response_format.type = json_object
    JSON_SCHEMA,       // response_format.json_schema
    TOOLS,             // tools present
    FUNCTION           // legacy functions
  }
  ```
- [ ] Implement `ModeDetector`
- [ ] Tool schema hashing:
  ```java
  String computeToolSchemaHash(List<Tool> tools) {
    // Canonical JSON of tool names + parameter schemas
    // SHA256 hash
  }
  ```
- [ ] Add mode and tool hash to `CacheEntryEntity`
- [ ] Filter candidates: exact mode + tool hash match
- [ ] JSON schema validation (if provided):
  - Use `networknt/json-schema-validator`
  - Validate cached JSON against request schema
  - Reject if invalid

**Files to Create**:
```
src/main/java/com/recurra/
└── service/compatibility/
    ├── RequestMode.java
    ├── ModeDetector.java
    ├── ToolSchemaHasher.java
    └── JsonSchemaValidator.java
```

**Acceptance Criteria**:
- JSON mode request never spoofs TEXT mode cache
- Tool schema mismatch → cache miss
- JSON schema validation catches invalid cache
- Integration tests for each mode

---

### 4.3 Parameter Bucketing
**Epic**: B3 (Must Have)
**Current**: String bucketing ("low", "medium", "high")
**Target**: More granular with closeness scoring

**Tasks**:
- [ ] Refine temperature buckets:
  ```
  0.0 → "zero"
  0.0-0.2 → "low"
  0.2-0.5 → "medium"
  0.5-0.8 → "high"
  0.8-1.0 → "very_high"
  1.0+ → "creative"
  ```
- [ ] Add `top_p` bucketing (similar)
- [ ] `max_tokens` bucketing (±10% tolerance)
- [ ] Parameter closeness score:
  ```java
  double paramScore(Request req, CacheEntry cached) {
    double tempScore = 1.0 - Math.abs(req.temp - cached.temp);
    double topPScore = 1.0 - Math.abs(req.topP - cached.topP);
    return (tempScore + topPScore) / 2.0;
  }
  ```
- [ ] Add to composite scoring

**Files to Modify**:
```
src/main/java/com/recurra/
└── service/
    ├── TemplateExtractor.java (update bucketing)
    └── CompositeScorer.java (use param score)
```

**Acceptance Criteria**:
- Close parameters score higher
- Bucketing reduces cache fragmentation
- Config allows custom bucket boundaries

---

## Phase 5: Control Headers & Provenance (Week 5-6)

### 5.1 Request Control Headers
**Epic**: D1 (Must Have)
**Current**: None
**Target**: Per-request cache control

**Tasks**:
- [ ] Define header constants:
  ```java
  public class CacheHeaders {
    public static final String CACHE_BYPASS = "x-cache-bypass";
    public static final String CACHE_STORE = "x-cache-store";
    public static final String CACHE_MODE = "x-cache-mode";
    public static final String MODEL_COMPAT = "x-model-compat";
    public static final String EXPERIMENT = "x-cache-experiment";
  }
  ```
- [ ] Create `CacheControlContext`:
  ```java
  public record CacheControlContext(
    boolean bypass,
    boolean store,
    CacheMode mode,          // EXACT, TEMPLATE, BOTH
    ModelCompatPolicy compat,
    String experiment        // A/B label
  ) {}
  ```
- [ ] Header parser in `ChatController`
- [ ] Pass context through service layer
- [ ] Respect bypass: skip cache lookup
- [ ] Respect store=false: don't cache response

**Files to Create**:
```
src/main/java/com/recurra/
├── model/
│   ├── CacheHeaders.java
│   └── CacheControlContext.java
└── service/
    └── CacheControlParser.java
```

**Acceptance Criteria**:
- `x-cache-bypass: true` forces provider call
- `x-cache-store: false` doesn't persist
- All headers parsed and applied correctly
- Integration tests for each header

---

### 5.2 Response Provenance Headers
**Epic**: D2 (Must Have)
**Current**: Only `x_cached` in JSON body
**Target**: Comprehensive provenance in headers

**Tasks**:
- [ ] Add response headers in `ChatController`:
  ```java
  return ResponseEntity.ok()
    .header("x-cache-hit", hit ? "true" : "false")
    .header("x-cache-match", matchType)  // exact/template/none
    .header("x-cache-score", score)
    .header("x-cache-provenance", cacheId)
    .header("x-cache-source-model", originalModel)
    .header("x-cache-age", ageSeconds)
    .body(response);
  ```
- [ ] Store provenance in `CacheEntryEntity`:
  - `provenance_id` (UUID)
  - `source_model` (original request model)
  - `created_at`, `last_hit_at`
- [ ] Include in all responses (hit and miss)
- [ ] SSE mode: send as comment lines
  ```
  : x-cache-hit: true
  : x-cache-match: template
  data: {...}
  ```

**Files to Modify**:
```
src/main/java/com/recurra/
├── controller/
│   └── ChatController.java
└── service/
    └── ProvenanceService.java (new)
```

**Acceptance Criteria**:
- All provenance headers present
- SSE includes provenance as comments
- Headers accurate for hit/miss/template
- Integration tests verify headers

---

## Phase 6: Admin Console Backend (Week 6-7)

### 6.1 Entry Explorer API
**Epic**: F1 (Must Have - Read Only)
**Current**: None
**Target**: REST API for cache inspection

**Tasks**:
- [ ] Create `AdminController`:
  ```java
  @RestController
  @RequestMapping("/admin/v1/cache")
  public class AdminController {
    @GetMapping("/entries")
    Page<CacheEntrySummary> searchEntries(
      @RequestParam(required=false) String query,
      @RequestParam(required=false) String model,
      @RequestParam(required=false) String tenant,
      @RequestParam(required=false) String matchType,
      @RequestParam(required=false) @DateTimeFormat LocalDate from,
      @RequestParam(required=false) @DateTimeFormat LocalDate to,
      Pageable pageable
    );

    @GetMapping("/entries/{id}")
    CacheEntryDetail getEntry(@PathVariable UUID id);
  }
  ```
- [ ] Implement full-text search on `canonical_prompt`
  - Postgres `tsvector` or basic `LIKE` for MVP
- [ ] DTO mapping:
  ```java
  public record CacheEntrySummary(
    UUID id,
    String tenantId,
    String model,
    String mode,
    String canonicalPromptPreview,  // first 200 chars
    int hitCount,
    Instant lastHitAt,
    Instant createdAt,
    boolean isGolden
  ) {}

  public record CacheEntryDetail(
    CacheEntrySummary summary,
    String canonicalPromptFull,
    JsonNode requestJson,
    JsonNode responseJson,
    double avgScore,
    List<ProvenanceChain> chain
  ) {}
  ```
- [ ] Pagination support (Spring Data)
- [ ] Security: Basic Auth for MVP (RBAC later)

**Files to Create**:
```
src/main/java/com/recurra/
├── controller/admin/
│   └── AdminController.java
├── dto/
│   ├── CacheEntrySummary.java
│   └── CacheEntryDetail.java
└── service/admin/
    └── CacheExplorerService.java
```

**Acceptance Criteria**:
- Search by text returns relevant entries
- Pagination works for large result sets
- Entry detail shows full payload
- No raw PII exposed in masked-only mode

---

### 6.2 Cache Statistics API
**Epic**: H1 (Must Have)
**Current**: Basic `/v1/cache/stats`
**Target**: Comprehensive metrics

**Tasks**:
- [ ] Expand `/admin/v1/stats` endpoint:
  ```java
  public record CacheStatistics(
    HitRateStats hitRate,
    Map<String, ModelStats> perModel,
    Map<String, TenantStats> perTenant,
    StorageStats storage,
    PerformanceStats performance,
    CostSavings savings
  ) {}

  public record HitRateStats(
    long totalRequests,
    long exactHits,
    long templateHits,
    long misses,
    double exactHitRate,
    double templateHitRate,
    double overallHitRate,
    HistogramData scoreDistribution
  ) {}
  ```
- [ ] Implement statistics collection:
  - Increment counters in Redis (atomic)
  - Aggregate from Postgres periodically
  - Cache stats for 1 minute
- [ ] Score histogram (0.0-1.0 in 0.1 buckets)
- [ ] Cost estimation:
  ```
  saved_cost = hits * avg_cost_per_request
  avg_cost_per_request = model-specific (GPT-4: $0.03/1k)
  ```

**Files to Create**:
```
src/main/java/com/recurra/
├── controller/admin/
│   └── StatsController.java
├── service/admin/
│   └── StatisticsService.java
└── dto/
    └── CacheStatistics.java (with nested records)
```

**Acceptance Criteria**:
- Stats accurate vs database queries
- Hit rate calculation correct
- Per-model and per-tenant breakdowns
- Cost savings estimate reasonable

---

## Phase 7: Observability & Resilience (Week 7-8)

### 7.1 OpenTelemetry Integration
**Epic**: H1 (Must Have)
**Current**: None
**Target**: Traces and metrics

**Tasks**:
- [ ] Add OpenTelemetry dependencies:
  ```xml
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
  </dependency>
  <dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
  </dependency>
  ```
- [ ] Configure OTel exporter (OTLP, Jaeger, or Zipkin)
- [ ] Add spans for key operations:
  ```java
  Span span = tracer.spanBuilder("cache.lookup")
    .setAttribute("cache.key", key)
    .setAttribute("cache.mode", mode)
    .startSpan();
  try (Scope scope = span.makeCurrent()) {
    // cache lookup
    span.setAttribute("cache.hit", hit);
    span.setAttribute("cache.score", score);
  } finally {
    span.end();
  }
  ```
- [ ] Key spans:
  - `http.request` (ingress)
  - `cache.exact_lookup`
  - `cache.template_search`
  - `embedding.generate`
  - `provider.forward`
  - `cache.write`
- [ ] Custom metrics:
  ```java
  LongCounter cacheHits = meter.counterBuilder("cache.hits")
    .setDescription("Cache hit count")
    .build();

  DoubleHistogram cacheScore = meter.histogramBuilder("cache.score")
    .setDescription("Template match score distribution")
    .build();
  ```

**Files to Create/Modify**:
```
src/main/java/com/recurra/
├── config/
│   └── ObservabilityConfiguration.java
└── service/
    └── (add spans to all services)

src/main/resources/
└── application.yml (add OTel config)
```

**Acceptance Criteria**:
- Traces visible in Jaeger/Grafana Tempo
- Metrics exported to Prometheus
- Latency percentiles calculated
- Parent-child span relationships correct

---

### 7.2 Failure Mode Handling
**Epic**: I2 (Must Have)
**Current**: Basic error propagation
**Target**: Graceful degradation

**Tasks**:
- [ ] Implement circuit breaker pattern:
  ```java
  @Service
  public class ResilientCacheService {
    private final CircuitBreaker redisCircuitBreaker;
    private final CircuitBreaker postgresCircuitBreaker;

    public Optional<Response> get(Request req) {
      if (redisCircuitBreaker.isOpen()) {
        log.warn("Redis circuit open, skipping exact cache");
      } else {
        try {
          return exactCache.get(req);
        } catch (Exception e) {
          redisCircuitBreaker.recordFailure();
        }
      }

      if (postgresCircuitBreaker.isOpen()) {
        log.warn("Postgres circuit open, skipping template cache");
        return Optional.empty();
      }
      // ...
    }
  }
  ```
- [ ] Add Resilience4j dependency
- [ ] Configure circuit breakers:
  ```yaml
  resilience4j:
    circuitbreaker:
      instances:
        redis:
          failure-rate-threshold: 50
          wait-duration-in-open-state: 10s
        postgres:
          failure-rate-threshold: 50
          wait-duration-in-open-state: 30s
  ```
- [ ] Fallback modes:
  - Redis down → skip exact cache, try template
  - Postgres down → exact cache only
  - Both down → pure pass-through
- [ ] Health indicators:
  ```java
  @Component
  public class CacheHealthIndicator implements HealthIndicator {
    public Health health() {
      return Health.up()
        .withDetail("redis", redisStatus)
        .withDetail("postgres", postgresStatus)
        .withDetail("mode", currentMode)
        .build();
    }
  }
  ```
- [ ] Add degradation header:
  ```
  x-cache-degraded: true
  x-cache-degraded-reason: redis-unavailable
  ```

**Files to Create**:
```
src/main/java/com/recurra/
├── service/
│   ├── ResilientCacheService.java
│   └── CacheHealthIndicator.java
└── config/
    └── ResilienceConfiguration.java
```

**Acceptance Criteria**:
- Redis failure → service stays up
- Postgres failure → exact cache still works
- Both fail → pass-through mode
- Health endpoint reflects degraded state
- Chaos tests pass (Testcontainers with network faults)

---

## Phase 8: Testing & Documentation (Week 8)

### 8.1 Compatibility Golden Tests
**Epic**: N1 (Must Have)
**Current**: Basic unit tests
**Target**: Comprehensive compatibility suite

**Tasks**:
- [ ] Create golden test dataset:
  ```
  src/test/resources/golden/
  ├── requests/
  │   ├── 001_basic_chat.json
  │   ├── 002_streaming.json
  │   ├── 003_json_mode.json
  │   ├── 004_tools.json
  │   └── ...
  ├── responses/
  │   ├── 001_basic_chat.json
  │   └── ...
  └── sse/
      ├── 002_streaming.txt
      └── ...
  ```
- [ ] Implement `CompatibilityTest`:
  ```java
  @SpringBootTest
  class OpenAICompatibilityTest {
    @Test
    void testBasicChatCompletion() {
      // Load golden request
      // Send to /v1/chat/completions
      // Compare structure to golden response
    }

    @Test
    void testStreamingFormat() {
      // Verify SSE format matches OpenAI
    }
  }
  ```
- [ ] Test cases:
  - Basic chat completion
  - Multi-turn conversation
  - System message + user message
  - Temperature variations
  - JSON mode (with/without schema)
  - Tool calling (single, parallel, nested)
  - Streaming (regular, tool calls)
  - Error cases (invalid model, missing messages)
- [ ] Response structure validation:
  - All required fields present
  - Types match OpenAI spec
  - Token counts reasonable
  - Timestamps valid

**Files to Create**:
```
src/test/java/com/recurra/
└── compatibility/
    ├── OpenAICompatibilityTest.java
    ├── StreamingCompatibilityTest.java
    └── ToolCallingCompatibilityTest.java

src/test/resources/
└── golden/ (test data)
```

**Acceptance Criteria**:
- 50+ golden tests pass
- SSE format validated against OpenAI spec
- Tool calling scenarios covered
- Tests run in CI/CD

---

### 8.2 Similarity Quality Tests
**Epic**: N2 (Should Have)
**Current**: None
**Target**: Measure precision/recall

**Tasks**:
- [ ] Create similarity test dataset:
  ```
  src/test/resources/similarity/
  ├── positive_pairs.jsonl  # Should match
  ├── negative_pairs.jsonl  # Should NOT match
  └── edge_cases.jsonl
  ```
  - 100+ positive pairs (paraphrases, variable substitutions)
  - 100+ negative pairs (different topics, structures)
  - Edge cases (long prompts, code, multilingual)
- [ ] Implement `SimilarityQualityTest`:
  ```java
  @Test
  void testPrecisionRecall() {
    // Load test pairs
    // Compute scores
    // Calculate precision/recall at threshold 0.80
    // Assert: precision > 99%, recall > 85%
  }
  ```
- [ ] Measure false positive rate:
  - Target: < 1 in 10,000
  - Test with 10k random prompt pairs
- [ ] Threshold tuning:
  - Generate ROC curve
  - Find optimal threshold for use case

**Files to Create**:
```
src/test/java/com/recurra/
└── quality/
    ├── SimilarityQualityTest.java
    └── ThresholdTuningTest.java

src/test/resources/
└── similarity/ (test datasets)
```

**Acceptance Criteria**:
- Precision > 99% at threshold 0.80
- Recall > 85% for true duplicates
- False positive rate < 0.01%
- Results documented in report

---

### 8.3 Load & Chaos Testing
**Epic**: N3 (Should Have)
**Current**: None
**Target**: Performance validation & resilience

**Tasks**:
- [ ] Create k6 load test scripts:
  ```javascript
  // k6/load_test.js
  import http from 'k6/http';
  import { check } from 'k6';

  export let options = {
    stages: [
      { duration: '2m', target: 100 },   // ramp up
      { duration: '5m', target: 2000 },  // sustained load
      { duration: '2m', target: 0 },     // ramp down
    ],
  };

  export default function() {
    let payload = JSON.stringify({
      model: 'gpt-4',
      messages: [{ role: 'user', content: 'Test' }]
    });

    let res = http.post('http://localhost:8080/v1/chat/completions',
      payload, { headers: { 'Content-Type': 'application/json' }});

    check(res, {
      'status is 200': (r) => r.status === 200,
      'cache hit latency < 30ms': (r) =>
        r.headers['X-Cache-Hit'] === 'true'
          ? r.timings.duration < 30
          : true,
    });
  }
  ```
- [ ] Scenarios:
  - Pure cache hits (2k RPS target)
  - Pure misses (provider throughput)
  - Mixed 80/20 (realistic)
  - Streaming load (500 concurrent streams)
- [ ] Chaos tests with Testcontainers:
  ```java
  @Test
  void testRedisNetworkPartition() {
    // Start Redis in Testcontainers
    // Simulate network partition (Toxiproxy)
    // Verify service continues (degraded mode)
    // Restore network
    // Verify recovery
  }
  ```
- [ ] Chaos scenarios:
  - Redis network partition
  - Postgres read-only mode
  - Provider timeout (mock slow responses)
  - High concurrency (1000+ simultaneous requests)

**Files to Create**:
```
k6/
├── load_test.js
├── streaming_test.js
└── mixed_workload.js

src/test/java/com/recurra/
└── chaos/
    ├── RedisFailureTest.java
    ├── PostgresFailureTest.java
    └── ProviderTimeoutTest.java
```

**Acceptance Criteria**:
- Cache hit p95 < 30ms under load
- Cache hit p99 < 60ms
- Throughput: 2k RPS sustained on reference hardware
- Chaos tests: service stays up in all scenarios
- Degraded mode latency acceptable

---

### 8.4 Documentation
**Epic**: L1 (Must Have)
**Current**: README, USAGE
**Target**: Complete operator guide

**Tasks**:
- [ ] Update README with architecture diagram
- [ ] Create DEPLOYMENT.md:
  - System requirements (Java 17, Redis, Postgres)
  - Docker Compose setup
  - Kubernetes manifests (basic)
  - Environment variables reference
  - Health checks configuration
- [ ] Create OPERATIONS.md:
  - Monitoring dashboard setup (Grafana)
  - Alert rules (hit rate drop, error spike)
  - Backup/restore procedures
  - Cache maintenance (reindexing, purging)
  - Troubleshooting guide
- [ ] Create API.md:
  - Complete API reference
  - cURL examples for all endpoints
  - OpenAPI/Swagger spec
- [ ] Create TUNING.md:
  - Similarity threshold guidance
  - Model compatibility policy selection
  - Performance tuning (connection pools, cache sizes)
  - Cost optimization strategies
- [ ] Inline code documentation:
  - JavaDoc for all public APIs
  - Architecture decision records (ADRs)

**Files to Create**:
```
docs/
├── DEPLOYMENT.md
├── OPERATIONS.md
├── API.md
├── TUNING.md
├── ARCHITECTURE.md
└── adr/
    ├── 001-why-pgvector.md
    ├── 002-simhash-vs-minhash.md
    └── 003-deterministic-streaming.md

docker/
├── docker-compose.yml
└── kubernetes/
    ├── deployment.yml
    ├── service.yml
    └── configmap.yml
```

**Acceptance Criteria**:
- New user can deploy in < 30 minutes
- All configuration options documented
- Troubleshooting guide covers common issues
- OpenAPI spec passes validation

---

## Phase 9: Polish & MVP Release (Week 9-10)

### 9.1 Missing Core Features

**Tasks**:
- [ ] Implement proper TTL per model family:
  ```yaml
  cache_ttl:
    gpt-4: 168h      # 7 days
    gpt-3.5: 72h     # 3 days
    default: 24h
  ```
- [ ] Add tenant_id throughout (default: "default")
- [ ] Implement eviction: Redis LFU, Postgres `DELETE WHERE expires_at < NOW()`
- [ ] Add `response_format` handling:
  - Detect `{"type":"json_object"}` vs `{"type":"json_schema", ...}`
  - Validate cached JSON if schema provided
- [ ] Provider timeout → transparent error passthrough
- [ ] Add rate limiting (Bucket4j):
  ```yaml
  rate_limits:
    default:
      capacity: 100
      refill_rate: 10/s
  ```

---

### 9.2 CI/CD Pipeline

**Tasks**:
- [ ] GitHub Actions workflow:
  ```yaml
  # .github/workflows/ci.yml
  - name: Test
    run: ./mvnw verify
  - name: Build Docker
    run: docker build -t recurra:${{ github.sha }} .
  - name: Run Integration Tests
    run: docker-compose -f docker/compose.test.yml up --abort-on-container-exit
  ```
- [ ] Testcontainers in CI (Docker-in-Docker)
- [ ] Code coverage (JaCoCo) → 80% target
- [ ] Security scanning (OWASP Dependency Check)
- [ ] Docker image optimization (multi-stage build)

---

### 9.3 MVP Checklist

**Must Have Features**:
- [x] A1: Chat completions endpoint ✓ (existing)
- [ ] A2: Streaming SSE (miss + deterministic replay)
- [ ] B1: Redis exact cache with write-behind
- [ ] B2: SimHash + pgvector template matching
- [ ] B3: Guardrails (model compat, mode, tools)
- [x] C1: OpenAI provider adapter ✓ (existing)
- [ ] D1/D2: Control + provenance headers
- [ ] E1: Canonicalization + masking
- [ ] F1: Admin API (read-only)
- [ ] H1: OpenTelemetry + metrics
- [ ] I1: SLO validation (load tests)
- [ ] I2: Failure mode resilience
- [x] L1: Configuration ✓ (existing, needs enhancement)
- [ ] N1: Golden compatibility tests

**Quality Gates**:
- [ ] All golden tests pass
- [ ] Load test: 2k RPS with p95 < 30ms for hits
- [ ] Chaos tests: all degradation modes work
- [ ] False positive rate < 0.01%
- [ ] Code coverage > 80%
- [ ] Documentation complete
- [ ] Docker Compose: one command to run
- [ ] Security: no secrets in logs/errors

---

## Technical Architecture Decisions

### Storage Layer
- **Redis**: Exact cache (hot path)
- **PostgreSQL + pgvector**: Template index (warm path)
- **No object storage** (S3) for MVP - direct DB storage

### Embedding Model
- **e5-small-v2** (384 dims, ONNX)
- Local inference for privacy & determinism
- 50ms p95 latency target

### Similarity Algorithm
- **SimHash (64-bit)** for structural similarity
- **Cosine distance** on embeddings for semantic similarity
- **Composite scoring** (structural 40%, semantic 40%, param 10%, recency 10%)

### Streaming Strategy
- **Reactive Streams** (Spring WebFlux `Flux<ServerSentEvent>`)
- **Seeded PRNG** (Java `Random` with cache key seed)
- **Buffering** during provider stream for write-behind

### Resilience
- **Circuit breakers** (Resilience4j)
- **Graceful degradation**: Redis → Postgres → Pass-through
- **No request blocking** on cache write failures

---

## Risk Mitigation

### High-Risk Items
1. **pgvector performance at scale**
   - Mitigation: IVFFlat index with 100 lists for MVP
   - Fallback: Qdrant/Milvus if pgvector insufficient

2. **Deterministic streaming feeling "off"**
   - Mitigation: Tunable pacing parameters
   - A/B test with users to find natural feel

3. **False positives in template matching**
   - Mitigation: Conservative threshold (0.80)
   - Admin tools to identify and pin/purge bad matches

4. **Memory pressure from embeddings**
   - Mitigation: Lazy loading, batch inference
   - Consider quantization (int8) if needed

### Medium-Risk Items
- SimHash collisions → low probability with 64-bit
- ONNX model compatibility → test on target platforms
- Streaming backpressure → handled by Reactor

---

## Success Metrics (MVP)

### Functional
- ✅ OpenAI SDK works without code changes
- ✅ Streaming replays are deterministic
- ✅ Template matching works for 95%+ of similar prompts
- ✅ Admin can inspect and manage cache

### Performance
- ✅ Cache hit latency: p95 < 30ms, p99 < 60ms
- ✅ Throughput: 2k RPS sustained
- ✅ Miss overhead: < 10ms vs direct provider call

### Quality
- ✅ False positive rate: < 1 in 10,000
- ✅ Golden tests: 100% pass
- ✅ Chaos tests: no crashes, graceful degradation

### Operational
- ✅ Deploy from scratch in < 30 minutes
- ✅ Monitoring dashboards functional
- ✅ Clear documentation for all features

---

## Post-MVP Roadmap

### Phase 10: Enhanced Features (Should Have)
- B4: Golden pins + manual curation UI
- C2: Cost/latency stamping
- F2/F3: Admin actions (promote, purge, bulk ops)
- G1: Proper multi-tenancy
- H2: Audit logs
- K1/K2: Advanced JSON/tool validation
- M1/M2: Vault integration, rate limiting per key

### Phase 11: Scale & Optimize (Could Have)
- A3: Embeddings endpoint proxy
- G2: OIDC + RBAC
- H3: Automated backup/restore
- J1: Qdrant/Pinecone adapters
- L2: Maintenance CLI
- Horizontal scaling (stateless design)

### Phase 12: Enterprise (Won't Have for MVP)
- Multi-region deployment
- Cross-region cache replication
- Advanced analytics (cache ROI, cost attribution)
- Semantic versioning of prompts
- Prompt A/B testing framework

---

## Resource Requirements

### Development
- **1 senior backend engineer** (full-time, 8-10 weeks)
- **1 DevOps engineer** (part-time, 2-3 weeks for infra)
- **1 QA engineer** (part-time, 2 weeks for test design)

### Infrastructure (MVP)
- **Application**: 2 vCPU, 4GB RAM (Spring Boot)
- **Redis**: 2 vCPU, 8GB RAM (cache storage)
- **PostgreSQL**: 4 vCPU, 16GB RAM (pgvector needs memory)
- **Total**: ~$300-500/month on AWS/GCP for staging + prod

### Tools
- IDE: IntelliJ IDEA
- Database: DBeaver
- API Testing: Bruno/Postman
- Load Testing: k6
- Monitoring: Grafana + Prometheus (self-hosted)
- Tracing: Jaeger (self-hosted)

---

## Next Steps

1. **Review & Approve Plan**: Stakeholder sign-off on scope
2. **Setup Infrastructure**: Provision Redis + Postgres + observability
3. **Begin Phase 1**: Redis integration (highest priority)
4. **Weekly Demos**: Show progress every Friday
5. **MVP Beta**: Week 8, internal testing
6. **MVP Release**: Week 10, public release

---

## Questions for Product Owner

1. **Similarity Threshold**: Is 0.80 acceptable, or prefer stricter (0.85+)?
2. **Privacy Mode**: MVP needs masked-only storage, or raw OK for internal use?
3. **Embedding Model**: Local e5-small acceptable, or prefer remote OpenAI embeddings?
4. **Multi-Tenancy**: Hard requirement for MVP, or single-tenant OK initially?
5. **Admin UI**: REST API sufficient for MVP, or need web UI?
6. **Provider Priority**: OpenAI only for MVP, or also Anthropic?
7. **Deployment Target**: Docker Compose, Kubernetes, or both?
8. **SLOs**: Are 30ms p95 / 2k RPS targets realistic for your use case?

---

**Plan Version**: 1.0
**Last Updated**: 2025-11-05
**Status**: Awaiting approval
