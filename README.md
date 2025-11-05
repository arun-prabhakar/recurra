# Recurra

> Deterministic memory for AI APIs.

Recurra is an OpenAI-compatible proxy that caches and replays responses based on structural and semantic similarity — delivering instant, repeatable results while cutting provider costs.

## Why Recurra?

- **🎯 Deterministic AI**: Get the same response for similar prompts every time
- **⚡ Instant Responses**: Cached responses bypass slow API calls
- **💰 Cost Savings**: Eliminate redundant provider charges
- **🧠 Template-Aware**: Matches requests by structure, not just exact text
- **🔌 Drop-in Compatible**: Works with existing OpenAI SDK code
- **🛤️ Proven Paths**: Guide AI agents down successful routes

## Features

### Template-Aware Caching

Unlike simple request caching, Recurra understands the *structure* of your prompts:

```python
# First request
"Summarize this article: https://example.com/article-123"

# Second request (different URL, same structure)
"Summarize this article: https://example.com/article-456"
# ✓ Cache HIT! Recurra recognizes the structural similarity
```

Recurra normalizes URLs, numbers, dates, UUIDs, and emails to identify patterns.

### OpenAI-Compatible

Works seamlessly with the OpenAI SDK:

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",  # Point to Recurra
    api_key="your-openai-key"
)

response = client.chat.completions.create(
    model="gpt-4",
    messages=[{"role": "user", "content": "Hello!"}]
)

# Check if cached
if response.x_cached:
    print("Instant response from cache!")
```

### Multi-Provider Support

Automatically routes to the right provider:

- OpenAI (GPT-4, GPT-3.5, etc.)
- Anthropic Claude (coming soon)
- Custom providers (extensible)

## Quick Start

### Prerequisites

- Java 17 or later
- Maven 3.6+
- API key for OpenAI (or other provider)

### Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/recurra.git
cd recurra

# Configure your API key
cp .env.example .env
# Edit .env and add your OPENAI_API_KEY

# Build and run
./mvnw spring-boot:run
```

The service starts on `http://localhost:8080`

### Test It Out

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -d '{
    "model": "gpt-4",
    "messages": [{"role": "user", "content": "Say hello!"}]
  }'
```

Run the same request again — notice how fast the cached response is!

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
recurra:
  cache:
    enabled: true
    max-size: 10000
    expire-after-write: 24h
    similarity-threshold: 0.85  # 0.0-1.0 (higher = stricter)
    template-matching: true

  providers:
    openai:
      enabled: true
      api-key: ${OPENAI_API_KEY}
```

**Similarity Threshold Guide:**
- `0.95-1.0`: Very strict, almost exact matches
- `0.85-0.95`: Balanced (recommended)
- `0.70-0.85`: Relaxed, aggressive caching
- `<0.70`: Very relaxed

## Use Cases

### Development & Testing

Stop burning API credits during development:

```python
# Iterate quickly without repeated API costs
for i in range(100):
    response = get_ai_response("same prompt")
    # First call: hits provider
    # Remaining 99: instant from cache
```

### Deterministic Workflows

Build reproducible AI systems:

```python
# Always get the same response for the same input
response = ai.generate("Write a haiku about coding")
# Exact same haiku every time, instantly
```

### Agent Guidance

Guide AI agents down proven successful paths:

```python
# Agent explores various approaches
# Recurra caches successful interactions
# Future agents follow proven patterns instantly
# No wasted tokens on dead ends
```

### Cost Optimization

Reduce API costs for production systems:

- Template matching catches similar user queries
- Instant responses improve UX
- Zero provider charges for cache hits

## API Reference

### Chat Completions

`POST /v1/chat/completions`

OpenAI-compatible chat completions endpoint. All OpenAI parameters supported.

**Response includes:**
```json
{
  "id": "chatcmpl-...",
  "choices": [...],
  "x_cached": true  // Indicates cache hit
}
```

### Cache Management

**Get Statistics:**
```bash
GET /v1/cache/stats
```

**Clear Cache:**
```bash
POST /v1/cache/clear
```

### Health Check

```bash
GET /health
```

## Architecture

```
┌─────────────┐
│   Client    │
│  (OpenAI    │
│    SDK)     │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────┐
│        Recurra Proxy        │
│  ┌───────────────────────┐  │
│  │  Template Extractor   │  │
│  │  • Pattern matching   │  │
│  │  • Normalization      │  │
│  │  • Similarity calc    │  │
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │   Cache Service       │  │
│  │  • Exact matching     │  │
│  │  • Template matching  │  │
│  │  • TTL management     │  │
│  └───────────────────────┘  │
└──────────┬──────────────────┘
           │
           ▼
    ┌──────────────┐
    │  Providers   │
    │  • OpenAI    │
    │  • Anthropic │
    └──────────────┘
```

## How It Works

1. **Request arrives** at Recurra's OpenAI-compatible endpoint
2. **Template extraction** identifies structural patterns
3. **Cache lookup** checks for:
   - Exact matches (fastest)
   - Template matches (fuzzy, configurable threshold)
4. **Cache hit?** Return cached response immediately with `x_cached: true`
5. **Cache miss?** Forward to provider, cache response, return to client

## Monitoring

Check cache effectiveness:

```bash
# View cache statistics
curl http://localhost:8080/v1/cache/stats

# Monitor logs
tail -f logs/recurra.log | grep "Cache HIT"
```

## Development

```bash
# Run tests
./mvnw test

# Build package
./mvnw clean package

# Run with custom config
./mvnw spring-boot:run -Dspring-boot.run.arguments=--recurra.cache.similarity-threshold=0.9
```

## Documentation

- [Usage Guide](USAGE.md) - Detailed usage examples and configuration
- [API Reference](API.md) - Complete API documentation (coming soon)
- [Architecture](ARCHITECTURE.md) - Technical deep dive (coming soon)

## Contributing

Contributions welcome! Please read our contributing guidelines first.

## License

See [LICENSE](LICENSE) for details.

## Support

- Issues: [GitHub Issues](https://github.com/yourusername/recurra/issues)
- Discussions: [GitHub Discussions](https://github.com/yourusername/recurra/discussions)

---

**Built with ❤️ for making AI deterministic and cost-effective**
