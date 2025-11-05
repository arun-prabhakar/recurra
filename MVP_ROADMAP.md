# Recurra MVP Roadmap - Quick Reference

## 🎯 MVP Goal
Production-ready OpenAI-compatible proxy with template-aware caching, deterministic streaming, and enterprise guardrails.

---

## 📊 Current vs. MVP State

| Feature | Current | MVP Target |
|---------|---------|------------|
| **Caching** | In-memory HashMap | Redis (exact) + Postgres/pgvector (template) |
| **Matching** | Jaro-Winkler only | SimHash + Embedding vectors + Composite scoring |
| **Streaming** | ❌ None | ✅ SSE with deterministic replay |
| **Guardrails** | ❌ None | ✅ Model compat + Mode checks + Tool validation |
| **Headers** | Basic x_cached | Full provenance (hit type, score, source) |
| **Resilience** | Basic errors | Circuit breakers + graceful degradation |
| **Observability** | Logs only | OpenTelemetry traces + Prometheus metrics |
| **Admin** | Stats endpoint | Full explorer API + search |
| **Tests** | Unit tests | Golden tests + Load tests + Chaos tests |

---

## 🗓️ 10-Week Timeline

```
Week 1-2  │ Phase 1: Infrastructure & Storage
          │ ├─ Redis exact cache with write-behind
          │ ├─ PostgreSQL + pgvector setup
          │ └─ Local embedding service (e5-small)
          │
Week 2-3  │ Phase 2: Canonicalization & Keys
          │ ├─ Request canonicalizer
          │ ├─ SimHash generator
          │ └─ Composite scoring
          │
Week 3-4  │ Phase 3: Streaming & Replay
          │ ├─ SSE passthrough on miss
          │ └─ Deterministic chunking/pacing on hit
          │
Week 4-5  │ Phase 4: Guardrails & Compatibility
          │ ├─ Model family policies
          │ ├─ Mode detection (text/json/tools)
          │ └─ Tool schema validation
          │
Week 5-6  │ Phase 5: Headers & Provenance
          │ ├─ Request control headers
          │ └─ Response provenance headers
          │
Week 6-7  │ Phase 6: Admin Console
          │ ├─ Entry explorer API
          │ └─ Statistics API
          │
Week 7-8  │ Phase 7: Observability & Resilience
          │ ├─ OpenTelemetry integration
          │ └─ Circuit breakers + degradation
          │
Week 8    │ Phase 8: Testing & Docs
          │ ├─ Golden compatibility tests
          │ ├─ Similarity quality tests
          │ ├─ Load & chaos tests
          │ └─ Complete documentation
          │
Week 9-10 │ Phase 9: Polish & Release
          │ ├─ CI/CD pipeline
          │ ├─ Docker Compose setup
          │ ├─ Final bug fixes
          │ └─ MVP release
```

---

## 🎫 Epic Status (MoSCoW)

### Must Have (MVP Blockers)
- [ ] **A1** - Chat Completions _(75% done - needs response_format)_
- [ ] **A2** - Streaming SSE _(0% - critical gap)_
- [ ] **B1** - Redis Exact Cache _(0% - critical gap)_
- [ ] **B2** - Template-Aware Matching _(20% - needs SimHash + pgvector)_
- [ ] **B3** - Guardrails _(0% - critical gap)_
- [x] **C1** - Provider Routing _(90% - OpenAI working)_
- [ ] **D1/D2** - Control Headers _(0% - critical gap)_
- [ ] **E1** - Canonicalization _(30% - basic masking exists)_
- [ ] **F1** - Admin Console _(10% - only basic stats)_
- [ ] **H1** - Observability _(0% - critical gap)_
- [ ] **I1** - SLO Validation _(0% - needs load tests)_
- [ ] **I2** - Failure Modes _(0% - critical gap)_
- [x] **L1** - Configuration _(80% - YAML working)_
- [ ] **N1** - Compatibility Tests _(0% - critical gap)_

### Should Have (Post-MVP Soon)
- [ ] **B4** - Golden Pins
- [ ] **C2** - Cost/Latency Stamping
- [ ] **F2/F3** - Admin Actions
- [ ] **G1** - Multi-Tenancy
- [ ] **H2** - Audit Logs
- [ ] **K1/K2** - JSON/Tool Robustness
- [ ] **M1/M2** - Security (Vault, Rate Limits)

### Could Have (Later)
- [ ] **A3** - Embeddings Proxy
- [ ] **G2** - RBAC/OIDC
- [ ] **H3** - Backup/Restore
- [ ] **J1/J2** - SPI Adapters
- [ ] **L2** - Maintenance CLI

---

## 🏗️ Architecture Changes Required

### Storage Stack
```
┌─────────────────────────────────┐
│    Spring Boot Application      │
│  ┌──────────┐    ┌────────────┐ │
│  │  Exact   │    │  Template  │ │
│  │  Cache   │    │   Cache    │ │
│  └────┬─────┘    └──────┬─────┘ │
└───────┼──────────────────┼───────┘
        │                  │
        ▼                  ▼
  ┌──────────┐      ┌─────────────┐
  │  Redis   │      │ PostgreSQL  │
  │ (< 30ms) │      │ + pgvector  │
  │  LFU     │      │ (< 100ms)   │
  └──────────┘      └─────────────┘
```

### Request Flow (Miss)
```
Client Request
    │
    ├─► Check Redis (exact key)     ← MISS
    │
    ├─► Query Postgres (SimHash buckets)  ← MISS
    │   └─► pgvector ANN search
    │
    ├─► Forward to Provider
    │   └─► Stream to client (Flux<SSE>)
    │       └─► Buffer chunks
    │
    └─► Write-behind to Redis + Postgres
        └─► Generate embedding (async)
```

### Request Flow (Hit)
```
Client Request
    │
    ├─► Check Redis (exact key)     ← HIT!
    │
    └─► Deterministic SSE Replay
        ├─► Seed PRNG with cache key
        ├─► Generate stable chunks
        ├─► Apply deterministic pacing
        └─► Stream to client (< 30ms first byte)
```

---

## 🔧 Technology Stack Additions

### New Dependencies
- **Redis**: `spring-boot-starter-data-redis`, Lettuce
- **PostgreSQL**: `postgresql`, `spring-boot-starter-data-jpa`, Flyway
- **pgvector**: `pgvector-jdbc` (custom repo)
- **ONNX**: `onnxruntime`, `tokenizers` (HuggingFace)
- **Resilience**: `resilience4j-spring-boot3`
- **Observability**: `opentelemetry-spring-boot-starter`
- **Testing**: `testcontainers`, `k6` (external)

### Infrastructure Requirements
```yaml
services:
  app:
    image: recurra:latest
    cpu: 2 vCPU
    memory: 4 GB

  redis:
    image: redis:7-alpine
    cpu: 2 vCPU
    memory: 8 GB

  postgres:
    image: pgvector/pgvector:pg16
    cpu: 4 vCPU
    memory: 16 GB

  prometheus:
    image: prom/prometheus:latest

  grafana:
    image: grafana/grafana:latest

  jaeger:
    image: jaegertracing/all-in-one:latest
```

---

## 📈 Success Criteria

### Functional
- ✅ Works with `openai` Python package (no code changes)
- ✅ Streaming feels natural (not robotic)
- ✅ Template matches 95%+ of paraphrases
- ✅ Zero false positives on dissimilar prompts

### Performance
- ✅ **Exact hit**: p95 < 30ms, p99 < 60ms
- ✅ **Template hit**: p95 < 100ms, p99 < 200ms
- ✅ **Miss overhead**: < 10ms added latency
- ✅ **Throughput**: 2,000 RPS sustained

### Quality
- ✅ **False positive rate**: < 1 in 10,000
- ✅ **Golden tests**: 100% pass
- ✅ **Code coverage**: > 80%
- ✅ **Chaos tests**: All pass

### Operational
- ✅ **Deploy time**: < 30 minutes (Docker Compose)
- ✅ **Monitoring**: Dashboards working out-of-box
- ✅ **Documentation**: Complete operator guide
- ✅ **Resilience**: Graceful degradation proven

---

## 🚨 Critical Risks

### High Risk
1. **pgvector Performance**
   - Risk: Slow queries at scale
   - Mitigation: IVFFlat indexing, query limits, fallback to Qdrant

2. **Deterministic Streaming UX**
   - Risk: Feels fake or off
   - Mitigation: Tunable pacing, user testing

3. **False Positives**
   - Risk: Wrong responses served
   - Mitigation: Conservative threshold (0.80), admin tools

### Medium Risk
4. **Embedding Model Size**
   - Risk: Memory pressure
   - Mitigation: Lazy loading, int8 quantization

5. **Integration Complexity**
   - Risk: Redis/Postgres/OTel all at once
   - Mitigation: Incremental rollout, feature flags

---

## 🎬 Getting Started (After Plan Approval)

### Week 1, Day 1 Tasks
1. **Setup Infrastructure**
   ```bash
   cd recurra
   docker-compose up -d redis postgres
   ```

2. **Create Feature Branch**
   ```bash
   git checkout -b feature/redis-exact-cache
   ```

3. **Add Dependencies**
   - Update `pom.xml` with Redis deps
   - Verify builds successfully

4. **Create Repository Interface**
   - `RedisExactCacheRepository.java`
   - Basic get/put with compression

5. **Integration Test**
   - Testcontainers Redis
   - Store + retrieve entry

### First Milestone (End of Week 2)
- ✅ Redis exact cache working
- ✅ Postgres schema deployed
- ✅ Embedding service loads model
- ✅ All integration tests pass

---

## 📝 Decision Log

### ADR-001: Why pgvector over Qdrant/Pinecone?
**Decision**: Use pgvector for MVP
**Rationale**:
- Single database (simpler ops)
- Self-hosted (no external deps)
- Good enough for MVP scale (<1M entries)
- Can migrate to Qdrant later via SPI

**Trade-offs**:
- Slower than dedicated vector DB
- Limited to 1-2M vectors before performance degrades

### ADR-002: Why SimHash + Embeddings?
**Decision**: Use both structural (SimHash) and semantic (embeddings)
**Rationale**:
- SimHash catches exact structural matches (fast)
- Embeddings catch semantic similarity (paraphrases)
- Composite scoring gives best of both

**Trade-offs**:
- More complex than embeddings alone
- Two indexes to maintain

### ADR-003: Why Deterministic Streaming?
**Decision**: Replay with seeded PRNG
**Rationale**:
- Users expect consistency ("proven paths")
- Instant first token (< 30ms)
- Feels real if paced naturally

**Trade-offs**:
- More complex than instant full response
- Requires tuning for natural feel

---

## 📞 Stakeholder Approval Checklist

- [ ] Timeline approved (10 weeks acceptable)
- [ ] Scope approved (Must Have features only)
- [ ] Budget approved (infra + development)
- [ ] Success metrics agreed
- [ ] Risk mitigation acceptable
- [ ] Post-MVP roadmap acknowledged
- [ ] Resource allocation confirmed

---

**Once approved, development begins with Phase 1: Infrastructure & Storage**

Next: Create detailed sprint plans and task breakdown in JIRA/Linear.
