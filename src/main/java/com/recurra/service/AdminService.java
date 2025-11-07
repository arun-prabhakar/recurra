package com.recurra.service;

import com.recurra.model.dto.CacheEntryDetail;
import com.recurra.model.dto.CacheEntrySummary;
import com.recurra.model.dto.CacheStatistics;
import com.recurra.persistence.entity.CacheEntryEntity;
import com.recurra.persistence.repository.CacheEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin service for cache management and analytics.
 */
@Slf4j
@Service
public class AdminService {

    private final CacheEntryRepository cacheEntryRepository;
    private final AdvancedCacheService advancedCacheService;

    // Model pricing (USD per 1K tokens) - rough estimates
    private static final Map<String, Double> MODEL_PRICING = Map.of(
            "gpt-4", 0.03,
            "gpt-3.5-turbo", 0.002,
            "claude-3-opus", 0.015,
            "claude-3-sonnet", 0.003,
            "claude-3-haiku", 0.00025
    );

    public AdminService(CacheEntryRepository cacheEntryRepository,
                       AdvancedCacheService advancedCacheService) {
        this.cacheEntryRepository = cacheEntryRepository;
        this.advancedCacheService = advancedCacheService;
    }

    /**
     * Get paginated cache entries with filters.
     */
    public Page<CacheEntrySummary> getEntries(
            String model,
            String tenant,
            String mode,
            String search,
            Instant from,
            Instant to,
            Pageable pageable) {

        Specification<CacheEntryEntity> spec = buildSpecification(model, tenant, mode, search, from, to);

        Page<CacheEntryEntity> entities = cacheEntryRepository.findAll(spec, pageable);

        return entities.map(this::toSummary);
    }

    /**
     * Get detailed information about a cache entry.
     */
    public Optional<CacheEntryDetail> getEntryDetail(UUID id) {
        return cacheEntryRepository.findById(id)
                .map(this::toDetail);
    }

    /**
     * Delete a specific cache entry.
     */
    @Transactional
    public void deleteEntry(UUID id) {
        cacheEntryRepository.deleteById(id);
        log.info("Deleted cache entry: id={}", id);
    }

    /**
     * Clear all cache (Redis + Postgres).
     */
    @Transactional
    public void clearAllCache() {
        advancedCacheService.clear();
        log.warn("Cleared all cache entries");
    }

    /**
     * Get comprehensive cache statistics.
     */
    public CacheStatistics getStatistics(Instant from, Instant to) {
        Specification<CacheEntryEntity> spec = buildSpecification(null, null, null, null, from, to);
        List<CacheEntryEntity> entries = cacheEntryRepository.findAll(spec);

        // Get hit/miss counts from AdvancedCacheService
        AdvancedCacheService.CacheStats serviceStats = advancedCacheService.getStats();

        // Calculate basic metrics
        long totalEntries = entries.size();
        long totalHits = entries.stream().mapToLong(e -> e.getHitCount() != null ? e.getHitCount() : 0).sum();
        long totalMisses = serviceStats.getMisses();
        double hitRate = totalHits + totalMisses > 0 ? (double) totalHits / (totalHits + totalMisses) : 0.0;

        // Calculate token savings and costs
        long tokensSaved = entries.stream()
                .mapToLong(e -> {
                    int hits = e.getHitCount() != null ? e.getHitCount() : 0;
                    int responseTokens = extractResponseTokens(e);
                    return (long) hits * responseTokens;
                })
                .sum();

        double costSavings = entries.stream()
                .mapToDouble(e -> {
                    int hits = e.getHitCount() != null ? e.getHitCount() : 0;
                    int responseTokens = extractResponseTokens(e);
                    double pricePerToken = getModelPrice(e.getModel()) / 1000.0;
                    return hits * responseTokens * pricePerToken;
                })
                .sum();

        // Calculate averages
        double avgMatchScore = entries.isEmpty() ? 0.0 :
                entries.stream()
                        .filter(e -> e.getHitCount() != null && e.getHitCount() > 0)
                        .mapToDouble(e -> 0.85) // Placeholder - would need actual score tracking
                        .average()
                        .orElse(0.0);

        Instant now = Instant.now();
        double avgCacheAgeSeconds = entries.isEmpty() ? 0.0 :
                entries.stream()
                        .mapToLong(e -> Duration.between(e.getCreatedAt(), now).getSeconds())
                        .average()
                        .orElse(0.0);

        // Build histograms
        Map<String, Long> hitCountHistogram = buildHitCountHistogram(entries);
        Map<String, Long> ageHistogram = buildAgeHistogram(entries, now);
        Map<String, Long> scoreHistogram = buildScoreHistogram(entries);

        // Breakdown by model
        List<CacheStatistics.ModelStatistics> byModel = buildModelStatistics(entries);

        // Breakdown by mode
        List<CacheStatistics.ModeStatistics> byMode = buildModeStatistics(entries);

        // Recent activity
        CacheStatistics.RecentActivity recentActivity = buildRecentActivity(entries, serviceStats);

        return CacheStatistics.builder()
                .totalEntries(totalEntries)
                .totalExactEntries(serviceStats.getExactHits()) // Approximate
                .totalHits(totalHits)
                .totalMisses(totalMisses)
                .hitRate(hitRate)
                .tokensSaved(tokensSaved)
                .costSavings(costSavings)
                .avgMatchScore(avgMatchScore)
                .avgCacheAgeSeconds(avgCacheAgeSeconds)
                .hitCountHistogram(hitCountHistogram)
                .ageHistogram(ageHistogram)
                .scoreHistogram(scoreHistogram)
                .byModel(byModel)
                .byMode(byMode)
                .recentActivity(recentActivity)
                .build();
    }

    /**
     * Get hit rate time series data.
     */
    public List<Map<String, Object>> getHitRateTimeSeries(String interval, Instant from, Instant to) {
        // This would require more complex SQL queries with time bucketing
        // For now, return a placeholder
        log.warn("getHitRateTimeSeries not fully implemented yet");
        return List.of();
    }

    /**
     * Get cost savings breakdown by model.
     */
    public List<Map<String, Object>> getCostSavings(Instant from, Instant to) {
        Specification<CacheEntryEntity> spec = buildSpecification(null, null, null, null, from, to);
        List<CacheEntryEntity> entries = cacheEntryRepository.findAll(spec);

        return buildModelStatistics(entries).stream()
                .map(ms -> Map.of(
                        "model", (Object) ms.getModel(),
                        "costSavings", ms.getCostSavings(),
                        "tokensSaved", ms.getTokensSaved(),
                        "hits", ms.getHits()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Mark entry as golden.
     */
    @Transactional
    public void markAsGolden(UUID id) {
        cacheEntryRepository.findById(id).ifPresent(entry -> {
            entry.setIsGolden(true);
            cacheEntryRepository.save(entry);
            log.info("Marked entry as golden: id={}", id);
        });
    }

    /**
     * Unmark entry as golden.
     */
    @Transactional
    public void unmarkAsGolden(UUID id) {
        cacheEntryRepository.findById(id).ifPresent(entry -> {
            entry.setIsGolden(false);
            cacheEntryRepository.save(entry);
            log.info("Unmarked entry as golden: id={}", id);
        });
    }

    // ===========================
    // Private Helper Methods
    // ===========================

    private Specification<CacheEntryEntity> buildSpecification(
            String model, String tenant, String mode, String search, Instant from, Instant to) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (model != null && !model.isEmpty()) {
                predicates.add(cb.equal(root.get("model"), model));
            }

            if (tenant != null && !tenant.isEmpty()) {
                predicates.add(cb.equal(root.get("tenant"), tenant));
            }

            if (mode != null && !mode.isEmpty()) {
                predicates.add(cb.equal(root.get("mode"), mode));
            }

            if (search != null && !search.isEmpty()) {
                // Full-text search on canonical_prompt
                predicates.add(cb.like(cb.lower(root.get("canonicalPrompt")),
                        "%" + search.toLowerCase() + "%"));
            }

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }

            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private CacheEntrySummary toSummary(CacheEntryEntity entity) {
        String promptPreview = entity.getCanonicalPrompt();
        if (promptPreview != null && promptPreview.length() > 100) {
            promptPreview = promptPreview.substring(0, 100) + "...";
        }

        return CacheEntrySummary.builder()
                .id(entity.getId())
                .tenant(entity.getTenant())
                .model(entity.getModel())
                .promptPreview(promptPreview)
                .mode(entity.getMode())
                .simhash(entity.getSimhash())
                .hitCount(entity.getHitCount())
                .createdAt(entity.getCreatedAt())
                .lastHitAt(entity.getLastHitAt())
                .isGolden(entity.getIsGolden())
                .responseTokens(extractResponseTokens(entity))
                .build();
    }

    private CacheEntryDetail toDetail(CacheEntryEntity entity) {
        return CacheEntryDetail.builder()
                .id(entity.getId())
                .tenant(entity.getTenant())
                .model(entity.getModel())
                .canonicalPrompt(entity.getCanonicalPrompt())
                .mode(entity.getMode())
                .toolSchemaHash(entity.getToolSchemaHash())
                .simhash(entity.getSimhash())
                .temperatureBucket(entity.getTemperatureBucket())
                .requestJson(entity.getRequestJson())
                .responseJson(entity.getResponseJson())
                .embedding(entity.getEmbedding())
                .hitCount(entity.getHitCount())
                .createdAt(entity.getCreatedAt())
                .lastHitAt(entity.getLastHitAt())
                .isGolden(entity.getIsGolden())
                .requestTokens(extractRequestTokens(entity))
                .responseTokens(extractResponseTokens(entity))
                .estimatedCost(calculateCost(entity))
                .build();
    }

    private int extractResponseTokens(CacheEntryEntity entity) {
        // Try to extract from response JSON
        if (entity.getResponseJson() != null && entity.getResponseJson().has("usage")) {
            return entity.getResponseJson().get("usage").path("completion_tokens").asInt(0);
        }
        return 0;
    }

    private int extractRequestTokens(CacheEntryEntity entity) {
        if (entity.getResponseJson() != null && entity.getResponseJson().has("usage")) {
            return entity.getResponseJson().get("usage").path("prompt_tokens").asInt(0);
        }
        return 0;
    }

    private double calculateCost(CacheEntryEntity entity) {
        int responseTokens = extractResponseTokens(entity);
        double pricePerToken = getModelPrice(entity.getModel()) / 1000.0;
        return responseTokens * pricePerToken;
    }

    private double getModelPrice(String model) {
        // Match model prefix
        for (Map.Entry<String, Double> entry : MODEL_PRICING.entrySet()) {
            if (model != null && model.toLowerCase().contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        return 0.001; // Default price
    }

    private Map<String, Long> buildHitCountHistogram(List<CacheEntryEntity> entries) {
        Map<String, Long> histogram = new LinkedHashMap<>();
        histogram.put("1", 0L);
        histogram.put("2-5", 0L);
        histogram.put("6-10", 0L);
        histogram.put("11-50", 0L);
        histogram.put("50+", 0L);

        for (CacheEntryEntity entry : entries) {
            int hits = entry.getHitCount() != null ? entry.getHitCount() : 0;
            if (hits == 1) histogram.put("1", histogram.get("1") + 1);
            else if (hits <= 5) histogram.put("2-5", histogram.get("2-5") + 1);
            else if (hits <= 10) histogram.put("6-10", histogram.get("6-10") + 1);
            else if (hits <= 50) histogram.put("11-50", histogram.get("11-50") + 1);
            else histogram.put("50+", histogram.get("50+") + 1);
        }

        return histogram;
    }

    private Map<String, Long> buildAgeHistogram(List<CacheEntryEntity> entries, Instant now) {
        Map<String, Long> histogram = new LinkedHashMap<>();
        histogram.put("<1h", 0L);
        histogram.put("1-24h", 0L);
        histogram.put("1-7d", 0L);
        histogram.put("7-30d", 0L);
        histogram.put("30d+", 0L);

        for (CacheEntryEntity entry : entries) {
            long ageHours = Duration.between(entry.getCreatedAt(), now).toHours();
            if (ageHours < 1) histogram.put("<1h", histogram.get("<1h") + 1);
            else if (ageHours < 24) histogram.put("1-24h", histogram.get("1-24h") + 1);
            else if (ageHours < 168) histogram.put("1-7d", histogram.get("1-7d") + 1);
            else if (ageHours < 720) histogram.put("7-30d", histogram.get("7-30d") + 1);
            else histogram.put("30d+", histogram.get("30d+") + 1);
        }

        return histogram;
    }

    private Map<String, Long> buildScoreHistogram(List<CacheEntryEntity> entries) {
        // Placeholder - would need actual score tracking
        Map<String, Long> histogram = new LinkedHashMap<>();
        histogram.put("0.5-0.6", 0L);
        histogram.put("0.6-0.7", 0L);
        histogram.put("0.7-0.8", 0L);
        histogram.put("0.8-0.9", 0L);
        histogram.put("0.9-1.0", (long) entries.size());
        return histogram;
    }

    private List<CacheStatistics.ModelStatistics> buildModelStatistics(List<CacheEntryEntity> entries) {
        Map<String, List<CacheEntryEntity>> byModel = entries.stream()
                .collect(Collectors.groupingBy(CacheEntryEntity::getModel));

        return byModel.entrySet().stream()
                .map(e -> {
                    String model = e.getKey();
                    List<CacheEntryEntity> modelEntries = e.getValue();
                    long hits = modelEntries.stream()
                            .mapToLong(ent -> ent.getHitCount() != null ? ent.getHitCount() : 0)
                            .sum();
                    long tokensSaved = modelEntries.stream()
                            .mapToLong(ent -> {
                                int hitCount = ent.getHitCount() != null ? ent.getHitCount() : 0;
                                return (long) hitCount * extractResponseTokens(ent);
                            })
                            .sum();
                    double costSavings = modelEntries.stream()
                            .mapToDouble(ent -> {
                                int hitCount = ent.getHitCount() != null ? ent.getHitCount() : 0;
                                return hitCount * extractResponseTokens(ent) * getModelPrice(model) / 1000.0;
                            })
                            .sum();

                    return CacheStatistics.ModelStatistics.builder()
                            .model(model)
                            .entries(modelEntries.size())
                            .hits(hits)
                            .hitRate(hits > 0 ? 1.0 : 0.0) // Simplified
                            .tokensSaved(tokensSaved)
                            .costSavings(costSavings)
                            .build();
                })
                .sorted(Comparator.comparing(CacheStatistics.ModelStatistics::getHits).reversed())
                .collect(Collectors.toList());
    }

    private List<CacheStatistics.ModeStatistics> buildModeStatistics(List<CacheEntryEntity> entries) {
        Map<String, List<CacheEntryEntity>> byMode = entries.stream()
                .collect(Collectors.groupingBy(e -> e.getMode() != null ? e.getMode() : "unknown"));

        return byMode.entrySet().stream()
                .map(e -> {
                    String mode = e.getKey();
                    List<CacheEntryEntity> modeEntries = e.getValue();
                    long hits = modeEntries.stream()
                            .mapToLong(ent -> ent.getHitCount() != null ? ent.getHitCount() : 0)
                            .sum();

                    return CacheStatistics.ModeStatistics.builder()
                            .mode(mode)
                            .entries(modeEntries.size())
                            .hits(hits)
                            .hitRate(hits > 0 ? 1.0 : 0.0) // Simplified
                            .build();
                })
                .sorted(Comparator.comparing(CacheStatistics.ModeStatistics::getHits).reversed())
                .collect(Collectors.toList());
    }

    private CacheStatistics.RecentActivity buildRecentActivity(
            List<CacheEntryEntity> entries,
            AdvancedCacheService.CacheStats serviceStats) {

        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(Duration.ofHours(1));
        Instant oneDayAgo = now.minus(Duration.ofHours(24));

        // Count recent entries
        long newEntriesLast24Hours = entries.stream()
                .filter(e -> e.getCreatedAt().isAfter(oneDayAgo))
                .count();

        // Count recent hits (approximate based on lastHitAt)
        long hitsLast1Hour = entries.stream()
                .filter(e -> e.getLastHitAt() != null && e.getLastHitAt().isAfter(oneHourAgo))
                .mapToLong(e -> e.getHitCount() != null ? e.getHitCount() : 0)
                .sum();

        long hitsLast24Hours = entries.stream()
                .filter(e -> e.getLastHitAt() != null && e.getLastHitAt().isAfter(oneDayAgo))
                .mapToLong(e -> e.getHitCount() != null ? e.getHitCount() : 0)
                .sum();

        return CacheStatistics.RecentActivity.builder()
                .hitsLast1Hour(hitsLast1Hour)
                .hitsLast24Hours(hitsLast24Hours)
                .missesLast1Hour(0L) // Would need time-series tracking
                .missesLast24Hours(0L)
                .newEntriesLast24Hours(newEntriesLast24Hours)
                .build();
    }
}
