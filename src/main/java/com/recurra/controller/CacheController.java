package com.recurra.controller;

import com.recurra.service.AdvancedCacheService;
import com.recurra.service.ProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Cache management controller.
 * Provides statistics and management for Redis + Postgres cache layers.
 */
@Slf4j
@RestController
@RequestMapping("/v1/cache")
public class CacheController {

    private final ProxyService proxyService;

    public CacheController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    /**
     * Get cache statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        AdvancedCacheService.CacheStats stats = proxyService.getCacheStats();

        return ResponseEntity.ok(Map.of(
                "exact_entries", stats.getExactEntries(),
                "template_entries", stats.getTemplateEntries(),
                "active_entries", stats.getActiveEntries(),
                "total_hits", stats.getTotalHits(),
                "status", "healthy"
        ));
    }

    /**
     * Clear the cache (Redis + Postgres).
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, String>> clearCache() {
        log.info("Cache clear requested");
        proxyService.clearCache();

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Cache cleared (Redis + Postgres)"
        ));
    }
}
