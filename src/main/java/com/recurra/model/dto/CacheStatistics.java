package com.recurra.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Comprehensive cache statistics for admin dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheStatistics {

    /**
     * Total number of cache entries (Postgres).
     */
    private long totalEntries;

    /**
     * Total number of exact cache entries (Redis).
     */
    private long totalExactEntries;

    /**
     * Total cache hits across all requests.
     */
    private long totalHits;

    /**
     * Total cache misses.
     */
    private long totalMisses;

    /**
     * Cache hit rate (0.0-1.0).
     */
    private double hitRate;

    /**
     * Total tokens saved from cache hits.
     */
    private long tokensSaved;

    /**
     * Estimated cost savings in USD.
     */
    private double costSavings;

    /**
     * Average match score for template matches.
     */
    private double avgMatchScore;

    /**
     * Average cache age in seconds.
     */
    private double avgCacheAgeSeconds;

    /**
     * Hit count histogram (buckets: 1, 2-5, 6-10, 11-50, 50+).
     */
    private Map<String, Long> hitCountHistogram;

    /**
     * Cache age histogram (buckets: <1h, 1-24h, 1-7d, 7-30d, 30d+).
     */
    private Map<String, Long> ageHistogram;

    /**
     * Match score histogram (buckets: 0.5-0.6, 0.6-0.7, 0.7-0.8, 0.8-0.9, 0.9-1.0).
     */
    private Map<String, Long> scoreHistogram;

    /**
     * Breakdown by model.
     */
    private List<ModelStatistics> byModel;

    /**
     * Breakdown by request mode.
     */
    private List<ModeStatistics> byMode;

    /**
     * Recent cache activity (last 24 hours).
     */
    private RecentActivity recentActivity;

    /**
     * Statistics for a specific model.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelStatistics {
        private String model;
        private long entries;
        private long hits;
        private double hitRate;
        private long tokensSaved;
        private double costSavings;
    }

    /**
     * Statistics for a specific request mode.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModeStatistics {
        private String mode;
        private long entries;
        private long hits;
        private double hitRate;
    }

    /**
     * Recent cache activity metrics.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentActivity {
        private long hitsLast1Hour;
        private long hitsLast24Hours;
        private long missesLast1Hour;
        private long missesLast24Hours;
        private long newEntriesLast24Hours;
    }
}
