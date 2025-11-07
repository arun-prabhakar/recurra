package com.recurra.controller;

import com.recurra.model.dto.CacheEntryDetail;
import com.recurra.model.dto.CacheEntrySummary;
import com.recurra.model.dto.CacheStatistics;
import com.recurra.service.AdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin API for cache management and analytics.
 * Provides endpoints for exploring cache entries, viewing statistics, and managing the cache.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/cache")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Get paginated list of cache entries with optional filters.
     *
     * @param model Filter by model (optional)
     * @param tenant Filter by tenant (optional)
     * @param mode Filter by request mode (text, json, tools, etc.) (optional)
     * @param search Full-text search on canonical prompt (optional)
     * @param from Filter entries created after this timestamp (optional)
     * @param to Filter entries created before this timestamp (optional)
     * @param pageable Pagination parameters (page, size, sort)
     * @return Page of cache entry summaries
     */
    @GetMapping("/entries")
    public ResponseEntity<Page<CacheEntrySummary>> getEntries(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        log.info("Admin: Listing cache entries with filters - model={}, tenant={}, mode={}, search={}, from={}, to={}",
                model, tenant, mode, search, from, to);

        Page<CacheEntrySummary> entries = adminService.getEntries(
                model, tenant, mode, search, from, to, pageable);

        return ResponseEntity.ok(entries);
    }

    /**
     * Get detailed information about a specific cache entry.
     *
     * @param id Cache entry ID
     * @return Full cache entry details
     */
    @GetMapping("/entries/{id}")
    public ResponseEntity<CacheEntryDetail> getEntry(@PathVariable UUID id) {
        log.info("Admin: Getting cache entry details for id={}", id);

        return adminService.getEntryDetail(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a specific cache entry.
     *
     * @param id Cache entry ID
     * @return 204 No Content on success
     */
    @DeleteMapping("/entries/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable UUID id) {
        log.info("Admin: Deleting cache entry id={}", id);

        adminService.deleteEntry(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clear all cache entries (Redis + Postgres).
     * WARNING: This is destructive!
     *
     * @param confirm Must be "yes" to proceed
     * @return 204 No Content on success
     */
    @DeleteMapping("/clear")
    public ResponseEntity<?> clearCache(@RequestParam String confirm) {
        if (!"yes".equals(confirm)) {
            return ResponseEntity.badRequest()
                    .body("Must provide confirm=yes to clear cache");
        }

        log.warn("Admin: Clearing ALL cache entries");
        adminService.clearAllCache();

        return ResponseEntity.noContent().build();
    }

    /**
     * Get comprehensive cache statistics.
     *
     * @param from Start of time range (optional)
     * @param to End of time range (optional)
     * @return Cache statistics including hit rates, cost savings, and histograms
     */
    @GetMapping("/stats")
    public ResponseEntity<CacheStatistics> getStatistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        log.info("Admin: Getting cache statistics for range from={} to={}", from, to);

        CacheStatistics stats = adminService.getStatistics(from, to);
        return ResponseEntity.ok(stats);
    }

    /**
     * Get hit rate statistics grouped by time interval.
     *
     * @param interval Grouping interval: hour, day, week (default: day)
     * @param from Start of time range (optional)
     * @param to End of time range (optional)
     * @return Time-series hit rate data
     */
    @GetMapping("/stats/hit-rate")
    public ResponseEntity<?> getHitRateTimeSeries(
            @RequestParam(defaultValue = "day") String interval,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        log.info("Admin: Getting hit rate time series with interval={}, from={}, to={}",
                interval, from, to);

        return ResponseEntity.ok(adminService.getHitRateTimeSeries(interval, from, to));
    }

    /**
     * Get cost savings breakdown by model.
     *
     * @param from Start of time range (optional)
     * @param to End of time range (optional)
     * @return Cost savings per model
     */
    @GetMapping("/stats/cost-savings")
    public ResponseEntity<?> getCostSavings(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        log.info("Admin: Getting cost savings for range from={} to={}", from, to);

        return ResponseEntity.ok(adminService.getCostSavings(from, to));
    }

    /**
     * Mark a cache entry as "golden" (high-quality reference).
     *
     * @param id Cache entry ID
     * @return 204 No Content on success
     */
    @PostMapping("/entries/{id}/golden")
    public ResponseEntity<Void> markAsGolden(@PathVariable UUID id) {
        log.info("Admin: Marking cache entry as golden: id={}", id);

        adminService.markAsGolden(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Remove golden status from a cache entry.
     *
     * @param id Cache entry ID
     * @return 204 No Content on success
     */
    @DeleteMapping("/entries/{id}/golden")
    public ResponseEntity<Void> unmarkAsGolden(@PathVariable UUID id) {
        log.info("Admin: Unmarking cache entry as golden: id={}", id);

        adminService.unmarkAsGolden(id);
        return ResponseEntity.noContent().build();
    }
}
