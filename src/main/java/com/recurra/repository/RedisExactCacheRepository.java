package com.recurra.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recurra.config.RecurraProperties;
import com.recurra.model.CachedResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Redis-based exact cache repository with compression.
 * Key pattern: cache:exact:{tenant}:{sha256}
 */
@Slf4j
@Repository
public class RedisExactCacheRepository {

    private static final String KEY_PREFIX = "cache:exact:";
    private static final String DEFAULT_TENANT = "default";

    private final RedisTemplate<String, byte[]> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RecurraProperties properties;

    public RedisExactCacheRepository(
            RedisTemplate<String, byte[]> redisTemplate,
            ObjectMapper objectMapper,
            RecurraProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Get cached response by exact key.
     *
     * @param exactKey SHA256 hash of canonical request
     * @return cached response if present and not expired
     */
    public Optional<CachedResponse> get(String exactKey) {
        return get(DEFAULT_TENANT, exactKey);
    }

    /**
     * Get cached response by exact key with tenant.
     *
     * @param tenantId tenant identifier
     * @param exactKey SHA256 hash of canonical request
     * @return cached response if present and not expired
     */
    public Optional<CachedResponse> get(String tenantId, String exactKey) {
        try {
            String redisKey = buildKey(tenantId, exactKey);
            byte[] compressed = redisTemplate.opsForValue().get(redisKey);

            if (compressed == null) {
                log.debug("Redis cache miss: {}", redisKey);
                return Optional.empty();
            }

            // Decompress and deserialize
            CachedResponse cached = decompress(compressed);

            // Update hit count and last access time (async to not block)
            updateHitStats(redisKey, cached);

            log.debug("Redis cache hit: {}, hit_count={}", redisKey, cached.getMetadata().getHitCount());
            return Optional.of(cached);

        } catch (Exception e) {
            log.error("Error retrieving from Redis cache: key={}", exactKey, e);
            return Optional.empty();
        }
    }

    /**
     * Store response in cache with TTL.
     *
     * @param exactKey SHA256 hash of canonical request
     * @param cached   cached response with metadata
     */
    public void put(String exactKey, CachedResponse cached) {
        put(DEFAULT_TENANT, exactKey, cached);
    }

    /**
     * Store response in cache with TTL and tenant.
     *
     * @param tenantId tenant identifier
     * @param exactKey SHA256 hash of canonical request
     * @param cached   cached response with metadata
     */
    public void put(String tenantId, String exactKey, CachedResponse cached) {
        try {
            String redisKey = buildKey(tenantId, exactKey);

            // Initialize metadata if not present
            if (cached.getMetadata() == null) {
                cached.setMetadata(CachedResponse.CacheMetadata.builder()
                        .createdAt(Instant.now())
                        .hitCount(0)
                        .build());
            } else if (cached.getMetadata().getCreatedAt() == null) {
                cached.getMetadata().setCreatedAt(Instant.now());
            }

            // Compress and store
            byte[] compressed = compress(cached);

            // Determine TTL based on model
            Duration ttl = getTtlForModel(cached.getMetadata().getSourceModel());

            redisTemplate.opsForValue().set(redisKey, compressed, ttl);

            log.debug("Stored in Redis cache: key={}, ttl={}, size={}KB",
                    redisKey, ttl, compressed.length / 1024);

        } catch (Exception e) {
            log.error("Error storing to Redis cache: key={}", exactKey, e);
            // Don't throw - cache failures shouldn't break requests
        }
    }

    /**
     * Delete entry from cache.
     *
     * @param exactKey SHA256 hash of canonical request
     */
    public void delete(String exactKey) {
        delete(DEFAULT_TENANT, exactKey);
    }

    /**
     * Delete entry from cache with tenant.
     *
     * @param tenantId tenant identifier
     * @param exactKey SHA256 hash of canonical request
     */
    public void delete(String tenantId, String exactKey) {
        try {
            String redisKey = buildKey(tenantId, exactKey);
            redisTemplate.delete(redisKey);
            log.debug("Deleted from Redis cache: {}", redisKey);
        } catch (Exception e) {
            log.error("Error deleting from Redis cache: key={}", exactKey, e);
        }
    }

    /**
     * Clear all cache entries (for testing or manual purge).
     */
    public void clear() {
        try {
            var keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Cleared {} entries from Redis cache", keys.size());
            }
        } catch (Exception e) {
            log.error("Error clearing Redis cache", e);
        }
    }

    /**
     * Check if key exists in cache.
     *
     * @param exactKey SHA256 hash of canonical request
     * @return true if key exists
     */
    public boolean exists(String exactKey) {
        return exists(DEFAULT_TENANT, exactKey);
    }

    /**
     * Check if key exists in cache with tenant.
     *
     * @param tenantId tenant identifier
     * @param exactKey SHA256 hash of canonical request
     * @return true if key exists
     */
    public boolean exists(String tenantId, String exactKey) {
        try {
            String redisKey = buildKey(tenantId, exactKey);
            return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));
        } catch (Exception e) {
            log.error("Error checking Redis cache existence: key={}", exactKey, e);
            return false;
        }
    }

    /**
     * Build Redis key from tenant and exact key.
     */
    private String buildKey(String tenantId, String exactKey) {
        return KEY_PREFIX + tenantId + ":" + exactKey;
    }

    /**
     * Compress cached response using GZIP.
     */
    private byte[] compress(CachedResponse cached) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {

            byte[] json = objectMapper.writeValueAsBytes(cached);
            gzipOut.write(json);
            gzipOut.finish();

            return baos.toByteArray();
        }
    }

    /**
     * Decompress and deserialize cached response.
     */
    private CachedResponse decompress(byte[] compressed) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             GZIPInputStream gzipIn = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIn.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }

            byte[] json = baos.toByteArray();
            return objectMapper.readValue(json, CachedResponse.class);
        }
    }

    /**
     * Update hit statistics asynchronously.
     */
    private void updateHitStats(String redisKey, CachedResponse cached) {
        try {
            // Increment hit count
            Integer currentHits = cached.getMetadata().getHitCount();
            cached.getMetadata().setHitCount(currentHits != null ? currentHits + 1 : 1);
            cached.getMetadata().setLastHitAt(Instant.now());

            // Async update (fire-and-forget)
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] compressed = compress(cached);
                    Duration remainingTtl = redisTemplate.getExpire(redisKey);
                    if (remainingTtl != null && remainingTtl.getSeconds() > 0) {
                        redisTemplate.opsForValue().set(redisKey, compressed, remainingTtl);
                    }
                } catch (Exception e) {
                    log.warn("Failed to update hit stats for {}", redisKey, e);
                }
            });

        } catch (Exception e) {
            log.warn("Error preparing hit stats update", e);
        }
    }

    /**
     * Get TTL for model based on configuration.
     */
    private Duration getTtlForModel(String model) {
        if (model == null) {
            return properties.getCache().getExpireAfterWrite();
        }

        // TODO: Add model-family-specific TTL configuration
        // For now, use global TTL
        return properties.getCache().getExpireAfterWrite();
    }
}
