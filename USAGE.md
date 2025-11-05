# Recurra Usage Guide

Recurra is an OpenAI-compatible proxy that caches and replays AI responses based on structural and semantic similarity.

## Quick Start

### 1. Configuration

Copy the example configuration:
```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
cp .env.example .env
```

Edit `.env` and add your API keys:
```bash
OPENAI_API_KEY=sk-your-actual-key-here
```

### 2. Build and Run

```bash
# Build the project
./mvnw clean package

# Run the application
./mvnw spring-boot:run
```

Or using Java directly:
```bash
java -jar target/recurra-proxy-0.1.0.jar
```

The service will start on `http://localhost:8080`

## API Usage

### Chat Completions

Recurra is fully compatible with the OpenAI Chat Completions API. Simply point your OpenAI client to Recurra's endpoint:

**Python Example:**
```python
from openai import OpenAI

# Point to Recurra instead of OpenAI
client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="your-openai-key"  # Still needed, passed through to provider
)

# Use exactly as you would with OpenAI
response = client.chat.completions.create(
    model="gpt-4",
    messages=[
        {"role": "user", "content": "What is the capital of France?"}
    ]
)

print(response.choices[0].message.content)

# Check if response was cached
if response.x_cached:
    print("This response was served from cache!")
```

**cURL Example:**
```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -d '{
    "model": "gpt-4",
    "messages": [
      {
        "role": "user",
        "content": "Explain quantum computing in simple terms"
      }
    ]
  }'
```

### Cache Management

**Get Cache Statistics:**
```bash
curl http://localhost:8080/v1/cache/stats
```

Response:
```json
{
  "exact_entries": 42,
  "template_keys": 15,
  "status": "healthy"
}
```

**Clear Cache:**
```bash
curl -X POST http://localhost:8080/v1/cache/clear
```

### Health Check

```bash
curl http://localhost:8080/health
```

## How Template-Aware Caching Works

Recurra doesn't just cache exact request matches. It understands the *structure* of your prompts and can serve cached responses for similar requests.

### Example

**First Request:**
```json
{
  "model": "gpt-4",
  "messages": [
    {"role": "user", "content": "Summarize this article: https://example.com/article-123"}
  ]
}
```

**Second Request (similar structure):**
```json
{
  "model": "gpt-4",
  "messages": [
    {"role": "user", "content": "Summarize this article: https://example.com/article-456"}
  ]
}
```

Even though the URLs are different, Recurra recognizes the structural similarity:
- Same model
- Same message structure (single user message)
- Same pattern (summarize + URL)

If the similarity exceeds the configured threshold (default 85%), Recurra serves the cached response.

### Template Normalization

Recurra automatically normalizes:
- **URLs**: `https://example.com/...` → `{URL}`
- **Numbers**: `123`, `45.67` → `{NUM}`
- **Dates**: `2024-01-15` → `{DATE}`
- **UUIDs**: `a1b2c3d4-...` → `{UUID}`
- **Emails**: `user@example.com` → `{EMAIL}`

This allows requests with different specific values but the same structure to match.

## Configuration Options

### Cache Settings

```yaml
recurra:
  cache:
    enabled: true                    # Enable/disable caching
    max-size: 10000                  # Maximum cache entries
    expire-after-write: 24h          # Cache TTL
    similarity-threshold: 0.85       # 0.0-1.0 (higher = stricter)
    template-matching: true          # Enable fuzzy matching
```

**Similarity Threshold Guide:**
- `0.95-1.0`: Very strict, almost exact matches only
- `0.85-0.95`: Balanced, catches similar requests (recommended)
- `0.70-0.85`: Relaxed, more aggressive caching
- `<0.70`: Very relaxed, may return inappropriate matches

### Provider Configuration

```yaml
recurra:
  providers:
    openai:
      enabled: true
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}

    anthropic:
      enabled: true
      base-url: https://api.anthropic.com/v1
      api-key: ${ANTHROPIC_API_KEY}
```

Recurra automatically routes requests to the correct provider based on the model name:
- `gpt-*`, `text-*` → OpenAI
- `claude-*` → Anthropic

## Use Cases

### 1. Development & Testing
Avoid repeated API calls during development:
```python
# First run: calls provider, caches response
response = client.chat.completions.create(...)

# Subsequent runs: instant response from cache
response = client.chat.completions.create(...)  # x_cached: true
```

### 2. Deterministic AI Systems
Build reproducible AI workflows:
```python
# Same input always gets same output
for i in range(100):
    response = client.chat.completions.create(
        model="gpt-4",
        messages=[{"role": "user", "content": "Plan a trip to Paris"}]
    )
    # Always the same response, instant, no cost
```

### 3. Cost Optimization
Reduce API costs for repeated queries:
- Template matching catches similar user queries
- Instant responses improve UX
- No provider charges for cached responses

### 4. Agent Guidance
Guide AI agents down proven paths:
```python
# Agent explores different approaches
# Recurra caches successful paths
# Future agents follow proven patterns instantly
```

## Monitoring

Monitor cache effectiveness:

```bash
# Watch cache stats
watch -n 5 'curl -s http://localhost:8080/v1/cache/stats'

# Check logs for cache hits
tail -f logs/recurra.log | grep "Cache HIT"
```

## Advanced Usage

### Custom Similarity Threshold per Request

While not directly exposed in the API, you can adjust the global threshold in `application.yml`:

```yaml
recurra:
  cache:
    similarity-threshold: 0.90  # Stricter matching
```

Restart the service for changes to take effect.

### Disable Template Matching

For exact-match-only caching:

```yaml
recurra:
  cache:
    template-matching: false
```

This disables fuzzy matching and only serves responses for exact request matches.

## Troubleshooting

### Cache Not Working

1. Check cache is enabled in config
2. Verify requests are actually similar enough
3. Check logs for similarity scores
4. Try lowering similarity threshold

### Wrong Responses Being Served

1. Increase similarity threshold
2. Disable template matching
3. Clear cache and start fresh

### Provider Errors

1. Verify API keys are correct
2. Check provider base URLs
3. Review provider-specific requirements
4. Check logs for detailed error messages

## Performance Tips

1. **Adjust Cache Size**: Set `max-size` based on your memory constraints
2. **Tune Expiry**: Longer `expire-after-write` = better cache hit rate but stale data
3. **Similarity Threshold**: Start at 0.85, adjust based on your use case
4. **Monitor Stats**: Regularly check cache stats to optimize settings
