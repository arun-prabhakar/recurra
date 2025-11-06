package com.recurra.service.similarity;

import com.recurra.entity.CacheEntryEntity;
import com.recurra.model.ChatCompletionRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Composite scoring for template-aware cache matching.
 *
 * Combines multiple signals:
 * - Semantic similarity (embedding cosine distance) - PRIMARY
 * - Structural similarity (SimHash Hamming distance) - SECONDARY
 * - Parameter similarity (temperature, top_p closeness)
 * - Recency (time decay)
 *
 * Formula (prioritizing semantic over structural):
 * score = 0.6 * semantic + 0.2 * structural + 0.1 * param + 0.1 * recency
 *
 * This ensures different content (e.g., different URLs) won't match even if
 * structurally identical, since embeddings capture semantic differences.
 *
 * Threshold (configurable): 0.87 for strict matching
 */
@Slf4j
@Service
public class CompositeScorer {

    private final SimHashGenerator simHashGenerator;

    // Scoring weights (semantic-first approach)
    private static final double WEIGHT_SEMANTIC = 0.6;   // Primary signal
    private static final double WEIGHT_STRUCTURAL = 0.2; // Secondary for template structure
    private static final double WEIGHT_PARAM = 0.1;      // Parameter closeness
    private static final double WEIGHT_RECENCY = 0.1;    // Time decay

    // Recency decay (half-life in hours)
    private static final double RECENCY_HALF_LIFE_HOURS = 168.0; // 1 week

    public CompositeScorer(SimHashGenerator simHashGenerator) {
        this.simHashGenerator = simHashGenerator;
    }

    /**
     * Score a candidate cache entry against a request.
     *
     * @param request incoming request
     * @param candidate cached entry
     * @param requestSimHash SimHash of request
     * @param requestEmbedding embedding of request
     * @return candidate score (0.0-1.0)
     */
    public CandidateScore score(
            ChatCompletionRequest request,
            CacheEntryEntity candidate,
            long requestSimHash,
            float[] requestEmbedding
    ) {
        try {
            // 1. Structural similarity (SimHash)
            double structuralScore = calculateStructuralScore(requestSimHash, candidate.getSimhash());

            // 2. Semantic similarity (embeddings)
            double semanticScore = calculateSemanticScore(requestEmbedding, candidate.getEmbedding());

            // 3. Parameter similarity
            double paramScore = calculateParameterScore(request, candidate);

            // 4. Recency score
            double recencyScore = calculateRecencyScore(candidate.getCreatedAt());

            // 5. Composite score (semantic-first)
            double composite = WEIGHT_SEMANTIC * semanticScore
                    + WEIGHT_STRUCTURAL * structuralScore
                    + WEIGHT_PARAM * paramScore
                    + WEIGHT_RECENCY * recencyScore;

            return CandidateScore.builder()
                    .cacheId(candidate.getId().toString())
                    .structuralScore(structuralScore)
                    .semanticScore(semanticScore)
                    .paramScore(paramScore)
                    .recencyScore(recencyScore)
                    .composite(composite)
                    .build();

        } catch (Exception e) {
            log.error("Error scoring candidate: {}", candidate.getId(), e);
            return CandidateScore.builder()
                    .cacheId(candidate.getId().toString())
                    .composite(0.0)
                    .build();
        }
    }

    /**
     * Score multiple candidates and filter by threshold.
     *
     * @param request incoming request
     * @param candidates list of cache entries
     * @param requestSimHash SimHash of request
     * @param requestEmbedding embedding of request
     * @param threshold minimum score (0.0-1.0)
     * @return filtered and sorted candidates
     */
    public List<ScoredCandidate> scoreAndFilter(
            ChatCompletionRequest request,
            List<CacheEntryEntity> candidates,
            long requestSimHash,
            float[] requestEmbedding,
            double threshold
    ) {
        return candidates.stream()
                .map(candidate -> {
                    CandidateScore score = score(request, candidate, requestSimHash, requestEmbedding);
                    return new ScoredCandidate(candidate, score);
                })
                .filter(sc -> sc.getScore().getComposite() >= threshold)
                .sorted((a, b) -> Double.compare(
                        b.getScore().getComposite(),
                        a.getScore().getComposite()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Find best match from candidates.
     *
     * @param request incoming request
     * @param candidates list of cache entries
     * @param requestSimHash SimHash of request
     * @param requestEmbedding embedding of request
     * @param threshold minimum score
     * @return best candidate or null
     */
    public ScoredCandidate findBestMatch(
            ChatCompletionRequest request,
            List<CacheEntryEntity> candidates,
            long requestSimHash,
            float[] requestEmbedding,
            double threshold
    ) {
        List<ScoredCandidate> scored = scoreAndFilter(
                request, candidates, requestSimHash, requestEmbedding, threshold
        );

        if (scored.isEmpty()) {
            return null;
        }

        ScoredCandidate best = scored.get(0);
        log.debug("Best match: id={}, score={}", best.getCandidate().getId(), best.getScore().getComposite());

        return best;
    }

    /**
     * Calculate structural similarity from SimHash.
     * Lower Hamming distance = higher similarity.
     */
    private double calculateStructuralScore(long hash1, long hash2) {
        int hammingDistance = simHashGenerator.hammingDistance(hash1, hash2);

        // Convert Hamming distance to similarity (0-64 bits different)
        // Perfect match: distance=0, score=1.0
        // Maximum distance: distance=64, score=0.0
        return 1.0 - (hammingDistance / 64.0);
    }

    /**
     * Calculate semantic similarity from embeddings (cosine similarity).
     * Cosine distance ∈ [0, 2], cosine similarity ∈ [-1, 1]
     * We want: closer vectors = higher score
     */
    private double calculateSemanticScore(float[] embedding1, float[] embedding2) {
        if (embedding1 == null || embedding2 == null) {
            return 0.0;
        }

        if (embedding1.length != embedding2.length) {
            log.warn("Embedding dimension mismatch: {} vs {}", embedding1.length, embedding2.length);
            return 0.0;
        }

        // Cosine similarity = dot product / (norm1 * norm2)
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < embedding1.length; i++) {
            dotProduct += embedding1[i] * embedding2[i];
            norm1 += embedding1[i] * embedding1[i];
            norm2 += embedding2[i] * embedding2[i];
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }

        double cosineSimilarity = dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));

        // Normalize to [0, 1] range
        // cosine similarity ∈ [-1, 1] → [0, 1]
        return (cosineSimilarity + 1.0) / 2.0;
    }

    /**
     * Calculate parameter similarity (temperature, top_p).
     */
    private double calculateParameterScore(ChatCompletionRequest request, CacheEntryEntity candidate) {
        double tempScore = calculateTemperatureScore(request.getTemperature(), candidate.getTemperatureBucket());
        double topPScore = calculateTopPScore(request.getTopP());

        // Average of parameter scores
        return (tempScore + topPScore) / 2.0;
    }

    /**
     * Temperature similarity.
     * Exact bucket match = 1.0, close buckets = 0.5, far = 0.0
     */
    private double calculateTemperatureScore(Double requestTemp, String cachedBucket) {
        if (requestTemp == null) {
            requestTemp = 1.0; // OpenAI default
        }

        String requestBucket = getTemperatureBucket(requestTemp);

        if (cachedBucket == null) {
            return 0.5; // Unknown, give neutral score
        }

        if (requestBucket.equals(cachedBucket)) {
            return 1.0; // Exact match
        }

        // Adjacent buckets get partial credit
        if (isAdjacentBucket(requestBucket, cachedBucket)) {
            return 0.5;
        }

        return 0.0;
    }

    /**
     * Top-p similarity (simplified for now).
     */
    private double calculateTopPScore(Double topP) {
        // For now, just check if it's at default
        if (topP == null || Math.abs(topP - 1.0) < 0.01) {
            return 1.0;
        }
        return 0.8; // Non-default, but give high score
    }

    /**
     * Recency score with exponential decay.
     * Newer entries score higher.
     */
    private double calculateRecencyScore(Instant createdAt) {
        if (createdAt == null) {
            return 0.5;
        }

        Duration age = Duration.between(createdAt, Instant.now());
        double ageHours = age.toHours();

        // Exponential decay: score = exp(-ageHours / halfLife)
        return Math.exp(-ageHours / RECENCY_HALF_LIFE_HOURS);
    }

    /**
     * Get temperature bucket for a temperature value.
     */
    private String getTemperatureBucket(double temp) {
        if (temp < 0.01) return "zero";
        if (temp < 0.3) return "low";
        if (temp < 0.7) return "medium";
        if (temp < 0.9) return "high";
        if (Math.abs(temp - 1.0) < 0.01) return "default";
        return "very_high";
    }

    /**
     * Check if two buckets are adjacent.
     */
    private boolean isAdjacentBucket(String bucket1, String bucket2) {
        List<String> order = List.of("zero", "low", "medium", "high", "default", "very_high");

        int idx1 = order.indexOf(bucket1);
        int idx2 = order.indexOf(bucket2);

        if (idx1 == -1 || idx2 == -1) {
            return false;
        }

        return Math.abs(idx1 - idx2) == 1;
    }

    /**
     * Candidate score breakdown.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateScore {
        private String cacheId;
        private double structuralScore;  // 0.0-1.0
        private double semanticScore;    // 0.0-1.0
        private double paramScore;       // 0.0-1.0
        private double recencyScore;     // 0.0-1.0
        private double composite;        // 0.0-1.0 (weighted average)
    }

    /**
     * Scored candidate pair.
     */
    @Data
    @AllArgsConstructor
    public static class ScoredCandidate {
        private CacheEntryEntity candidate;
        private CandidateScore score;
    }
}
