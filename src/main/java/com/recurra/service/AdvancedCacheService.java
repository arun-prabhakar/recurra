package com.recurra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recurra.config.RecurraProperties;
import com.recurra.entity.CacheEntryEntity;
import com.recurra.model.CachedResponse;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import com.recurra.repository.CacheEntryRepository;
import com.recurra.repository.RedisExactCacheRepository;
import com.recurra.service.canonicalization.PromptMasker;
import com.recurra.service.canonicalization.RequestCanonicalizer;
import com.recurra.service.embedding.EmbeddingService;
import com.recurra.service.similarity.CompositeScorer;
import com.recurra.service.similarity.SimHashGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Advanced cache service integrating Redis (exact) + Postgres (template) caching.
 *
 * Flow:
 * 1. Check Redis for exact match (< 30ms)
 * 2. If miss, check Postgres for template match (< 100ms)
 * 3. Use composite scoring to rank candidates with embeddings
 * 4. Return best match above threshold
 */
@Slf4j
@Service
public class AdvancedCacheService {

    private final RedisExactCacheRepository redisCache;
    private final CacheEntryRepository postgresRepo;
    private final RequestCanonicalizer canonicalizer;
    private final PromptMasker promptMasker;
    private final SimHashGenerator simHashGenerator;
    private final CompositeScorer compositeScorer;
    private final RecurraProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    private static final String DEFAULT_TENANT = "default";

    public AdvancedCacheService(
            RedisExactCacheRepository redisCache,
            CacheEntryRepository postgresRepo,
            RequestCanonicalizer canonicalizer,
            PromptMasker promptMasker,
            SimHashGenerator simHashGenerator,
            CompositeScorer compositeScorer,
            RecurraProperties properties,
            ObjectMapper objectMapper) {
        this.redisCache = redisCache;
        this.postgresRepo = postgresRepo;
        this.canonicalizer = canonicalizer;
        this.promptMasker = promptMasker;
        this.simHashGenerator = simHashGenerator;
        this.compositeScorer = compositeScorer;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Get cached response (exact or template match).
     *
     * @param request incoming request
     * @return cached response if found
     */
    public Optional<CacheResult> get(ChatCompletionRequest request) {
        if (!properties.getCache().isEnabled()) {
            return Optional.empty();
        }

        long startTime = System.nanoTime();

        try {
            // 1. Try exact match from Redis
            String exactKey = canonicalizer.generateHash(request);
            if (exactKey != null) {
                Optional<CachedResponse> exactMatch = redisCache.get(DEFAULT_TENANT, exactKey);
                if (exactMatch.isPresent()) {
                    long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
                    log.info("Cache HIT (exact) in {}ms, key={}", latencyMs, exactKey);

                    return Optional.of(CacheResult.builder()
                            .response(exactMatch.get().getResponse())
                            .matchType(MatchType.EXACT)
                            .score(1.0)
                            .latencyMs(latencyMs)
                            .source("redis")
                            .build());
                }
            }

            // 2. Try template match from Postgres (if enabled)
            if (properties.getCache().isTemplateMatching()) {
                Optional<CacheResult> templateMatch = findTemplateMatch(request, exactKey);
                if (templateMatch.isPresent()) {
                    long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
                    log.info("Cache HIT (template) in {}ms, score={}",
                            latencyMs, templateMatch.get().getScore());

                    return templateMatch.map(result ->
                            result.toBuilder().latencyMs(latencyMs).build());
                }
            }

            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            log.debug("Cache MISS in {}ms", latencyMs);
            return Optional.empty();

        } catch (Exception e) {
            log.error("Error retrieving from cache", e);
            return Optional.empty();
        }
    }

    /**
     * Store response in cache (both Redis and Postgres).
     *
     * @param request original request
     * @param response provider response
     */
    @Transactional
    public void put(ChatCompletionRequest request, ChatCompletionResponse response) {
        if (!properties.getCache().isEnabled()) {
            return;
        }

        try {
            // Generate keys and metadata
            String exactKey = canonicalizer.generateHash(request);
            if (exactKey == null) {
                log.warn("Failed to generate exact key, skipping cache storage");
                return;
            }

            // Extract prompt for template matching
            String promptText = extractPromptText(request);
            PromptMasker.MaskedPrompt maskedPrompt = promptMasker.mask(promptText);
            long simhash = simHashGenerator.generate(maskedPrompt.getMasked());

            // Store in Redis (exact cache)
            storeInRedis(request, response, exactKey);

            // Store in Postgres (template cache) - async
            storeInPostgres(request, response, exactKey, maskedPrompt, simhash);

            log.debug("Stored in cache: exact_key={}, simhash={}", exactKey, simhash);

        } catch (Exception e) {
            log.error("Error storing in cache", e);
            // Don't throw - cache failures shouldn't break requests
        }
    }

    /**
     * Find template match from Postgres.
     */
    private Optional<CacheResult> findTemplateMatch(ChatCompletionRequest request, String exactKey) {
        try {
            // Extract prompt and generate template signature
            String promptText = extractPromptText(request);
            PromptMasker.MaskedPrompt maskedPrompt = promptMasker.mask(promptText);
            long simhash = simHashGenerator.generate(maskedPrompt.getMasked());

            // Get request metadata
            String model = request.getModel();
            String mode = detectMode(request);

            // Query Postgres for SimHash candidates
            List<CacheEntryEntity> candidates = postgresRepo.findSimHashCandidates(
                    DEFAULT_TENANT,
                    mode,
                    model,
                    simhash,
                    properties.getCache().getHammingThreshold(),
                    100  // candidate limit
            );

            if (candidates.isEmpty()) {
                log.debug("No SimHash candidates found");
                return Optional.empty();
            }

            log.debug("Found {} SimHash candidates, scoring...", candidates.size());

            // Generate embedding for semantic similarity
            // IMPORTANT: Use RAW text, not masked text, to capture semantic differences
            // Example: "Summarize https://example.com/article-123" vs "...article-456"
            // Masked would be identical, but raw embeddings will differ
            float[] requestEmbedding = null;
            if (embeddingService != null && embeddingService.isReady()) {
                try {
                    requestEmbedding = embeddingService.embed(promptText);
                    log.debug("Generated embedding ({}d) from raw prompt for semantic matching", requestEmbedding.length);
                } catch (Exception e) {
                    log.warn("Failed to generate embedding, falling back to structural matching only", e);
                }
            } else {
                log.debug("Embedding service not available, using structural matching only");
            }

            // Score candidates
            CompositeScorer.ScoredCandidate bestMatch = compositeScorer.findBestMatch(
                    request,
                    candidates,
                    simhash,
                    requestEmbedding,
                    properties.getCache().getSimilarityThreshold()
            );

            if (bestMatch == null) {
                log.debug("No candidates above threshold ({})",
                        properties.getCache().getSimilarityThreshold());
                return Optional.empty();
            }

            // Convert to response
            CacheEntryEntity entity = bestMatch.getCandidate();
            ChatCompletionResponse response = objectMapper.treeToValue(
                    entity.getResponseJson(),
                    ChatCompletionResponse.class
            );

            // Update hit stats in Postgres
            updateHitStats(entity);

            return Optional.of(CacheResult.builder()
                    .response(response)
                    .matchType(MatchType.TEMPLATE)
                    .score(bestMatch.getScore().getComposite())
                    .source("postgres")
                    .provenanceId(entity.getId().toString())
                    .sourceModel(entity.getModel())
                    .build());

        } catch (Exception e) {
            log.error("Error finding template match", e);
            return Optional.empty();
        }
    }

    /**
     * Store in Redis.
     */
    private void storeInRedis(ChatCompletionRequest request, ChatCompletionResponse response, String exactKey) {
        try {
            CachedResponse cached = CachedResponse.builder()
                    .response(response)
                    .metadata(CachedResponse.CacheMetadata.builder()
                            .createdAt(Instant.now())
                            .hitCount(0)
                            .sourceModel(request.getModel())
                            .mode(detectMode(request))
                            .temperatureBucket(getTemperatureBucket(request.getTemperature()))
                            .isGolden(false)
                            .build())
                    .build();

            redisCache.put(DEFAULT_TENANT, exactKey, cached);

        } catch (Exception e) {
            log.error("Error storing in Redis", e);
        }
    }

    /**
     * Store in Postgres (async recommended in production).
     */
    @Transactional
    private void storeInPostgres(
            ChatCompletionRequest request,
            ChatCompletionResponse response,
            String exactKey,
            PromptMasker.MaskedPrompt maskedPrompt,
            long simhash) {

        try {
            // Check if already exists
            if (postgresRepo.findByExactKey(exactKey).isPresent()) {
                log.debug("Entry already exists in Postgres: {}", exactKey);
                return;
            }

            // Generate embedding for semantic similarity matching
            // IMPORTANT: Use RAW text for embeddings to preserve semantic differences
            // Masked text loses information (e.g., different URLs become identical {URL})
            float[] embedding = null;
            if (embeddingService != null && embeddingService.isReady()) {
                try {
                    String rawPromptText = extractPromptText(request);
                    embedding = embeddingService.embed(rawPromptText);
                    log.debug("Generated embedding ({}d) from raw prompt for storage", embedding.length);
                } catch (Exception e) {
                    log.warn("Failed to generate embedding for storage, will store without semantic vector", e);
                }
            } else {
                log.debug("Embedding service not available, storing without semantic vector");
            }

            CacheEntryEntity entity = CacheEntryEntity.builder()
                    .tenantId(DEFAULT_TENANT)
                    .exactKey(exactKey)
                    .simhash(simhash)
                    .embedding(embedding)
                    .canonicalPrompt(maskedPrompt.getMasked())
                    .rawPromptHmac(maskedPrompt.getRawHmac())
                    .requestJson(objectMapper.valueToTree(request))
                    .responseJson(objectMapper.valueToTree(response))
                    .model(request.getModel())
                    .temperatureBucket(getTemperatureBucket(request.getTemperature()))
                    .mode(detectMode(request))
                    .toolSchemaHash(null)  // TODO: Compute in Phase 3
                    .hitCount(0)
                    .isGolden(false)
                    .piiPresent(promptMasker.containsPii(extractPromptText(request)))
                    .createdAt(Instant.now())
                    .expiresAt(Instant.now().plus(properties.getCache().getExpireAfterWrite()))
                    .build();

            postgresRepo.save(entity);

        } catch (Exception e) {
            log.error("Error storing in Postgres", e);
        }
    }

    /**
     * Update hit statistics for cache entry.
     */
    @Transactional
    private void updateHitStats(CacheEntryEntity entity) {
        try {
            entity.incrementHitCount();
            postgresRepo.save(entity);
        } catch (Exception e) {
            log.warn("Failed to update hit stats for {}", entity.getId(), e);
        }
    }

    /**
     * Extract prompt text from request messages.
     */
    private String extractPromptText(ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }

        return request.getMessages().stream()
                .filter(msg -> msg.getContent() != null)
                .map(msg -> msg.getRole() + ": " + msg.getContent())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /**
     * Detect request mode (text, json, tools, etc.).
     */
    private String detectMode(ChatCompletionRequest request) {
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            return "tools";
        }
        if (request.getFunctions() != null && !request.getFunctions().isEmpty()) {
            return "function";
        }
        // TODO: Check response_format for json_object/json_schema
        return "text";
    }

    /**
     * Get temperature bucket.
     */
    private String getTemperatureBucket(Double temp) {
        if (temp == null) temp = 1.0;

        if (temp < 0.01) return "zero";
        if (temp < 0.3) return "low";
        if (temp < 0.7) return "medium";
        if (temp < 0.9) return "high";
        if (Math.abs(temp - 1.0) < 0.01) return "default";
        return "very_high";
    }

    /**
     * Clear all cache (Redis + Postgres).
     */
    @Transactional
    public void clear() {
        redisCache.clear();
        postgresRepo.deleteAll();
        log.info("Cleared all cache (Redis + Postgres)");
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        long postgresCount = postgresRepo.count();
        long activeCount = postgresRepo.countActiveEntries(DEFAULT_TENANT, Instant.now());
        long totalHits = postgresRepo.sumHitCountByTenantId(DEFAULT_TENANT);

        return CacheStats.builder()
                .exactEntries(-1)  // Redis doesn't expose count easily
                .templateEntries(postgresCount)
                .activeEntries(activeCount)
                .totalHits(totalHits)
                .build();
    }

    /**
     * Cache result with metadata.
     */
    @lombok.Data
    @lombok.Builder(toBuilder = true)
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CacheResult {
        private ChatCompletionResponse response;
        private MatchType matchType;
        private double score;  // 0.0-1.0
        private long latencyMs;
        private String source;  // "redis" or "postgres"
        private String provenanceId;
        private String sourceModel;
    }

    /**
     * Match type enum.
     */
    public enum MatchType {
        EXACT,      // Exact hash match from Redis
        TEMPLATE,   // Template match from Postgres
        NONE        // Cache miss
    }

    /**
     * Cache statistics.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CacheStats {
        private long exactEntries;
        private long templateEntries;
        private long activeEntries;
        private long totalHits;
    }
}
