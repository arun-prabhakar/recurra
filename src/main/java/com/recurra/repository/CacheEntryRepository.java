package com.recurra.repository;

import com.recurra.entity.CacheEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for cache entries with custom queries for template matching.
 */
@Repository
public interface CacheEntryRepository extends JpaRepository<CacheEntryEntity, UUID> {

    /**
     * Find entry by exact key.
     */
    Optional<CacheEntryEntity> findByExactKey(String exactKey);

    /**
     * Find entry by exact key and tenant.
     */
    Optional<CacheEntryEntity> findByTenantIdAndExactKey(String tenantId, String exactKey);

    /**
     * Find candidates by SimHash bucket (Hamming distance).
     * Returns entries where Hamming distance is within threshold.
     */
    @Query(value = """
            SELECT * FROM cache_entries
            WHERE tenant_id = :tenantId
              AND mode = :mode
              AND model = :model
              AND bit_count(simhash # :simhash) <= :hammingThreshold
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY bit_count(simhash # :simhash), hit_count DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CacheEntryEntity> findSimHashCandidates(
            @Param("tenantId") String tenantId,
            @Param("mode") String mode,
            @Param("model") String model,
            @Param("simhash") Long simhash,
            @Param("hammingThreshold") int hammingThreshold,
            @Param("limit") int limit
    );

    /**
     * Find candidates by embedding similarity (cosine distance).
     * Uses pgvector's <=> operator for cosine distance.
     */
    @Query(value = """
            SELECT * FROM cache_entries
            WHERE tenant_id = :tenantId
              AND mode = :mode
              AND model = :model
              AND embedding IS NOT NULL
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY embedding <=> cast(:embedding as vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<CacheEntryEntity> findEmbeddingCandidates(
            @Param("tenantId") String tenantId,
            @Param("mode") String mode,
            @Param("model") String model,
            @Param("embedding") String embedding, // PGvector format string
            @Param("limit") int limit
    );

    /**
     * Find candidates using both SimHash and embedding similarity.
     * This combines structural and semantic matching.
     */
    @Query(value = """
            WITH simhash_candidates AS (
                SELECT id, simhash,
                       bit_count(simhash # :simhash) AS hamming_distance
                FROM cache_entries
                WHERE tenant_id = :tenantId
                  AND mode = :mode
                  AND model = :model
                  AND bit_count(simhash # :simhash) <= :hammingThreshold
                  AND (expires_at IS NULL OR expires_at > NOW())
                LIMIT :candidateLimit
            )
            SELECT e.*,
                   1 - (e.embedding <=> cast(:embedding as vector)) AS cosine_similarity
            FROM cache_entries e
            INNER JOIN simhash_candidates sc ON e.id = sc.id
            WHERE e.embedding IS NOT NULL
              AND (e.embedding <=> cast(:embedding as vector)) < :cosineThreshold
            ORDER BY (e.embedding <=> cast(:embedding as vector))
            LIMIT :limit
            """, nativeQuery = true)
    List<CacheEntryEntity> findHybridCandidates(
            @Param("tenantId") String tenantId,
            @Param("mode") String mode,
            @Param("model") String model,
            @Param("simhash") Long simhash,
            @Param("hammingThreshold") int hammingThreshold,
            @Param("embedding") String embedding,
            @Param("cosineThreshold") double cosineThreshold,
            @Param("candidateLimit") int candidateLimit,
            @Param("limit") int limit
    );

    /**
     * Search entries by text (full-text search on canonical prompt).
     */
    @Query(value = """
            SELECT * FROM cache_entries
            WHERE tenant_id = :tenantId
              AND to_tsvector('english', canonical_prompt) @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank(to_tsvector('english', canonical_prompt),
                            plainto_tsquery('english', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CacheEntryEntity> searchByText(
            @Param("tenantId") String tenantId,
            @Param("query") String query,
            @Param("limit") int limit
    );

    /**
     * Find all entries by model.
     */
    Page<CacheEntryEntity> findByTenantIdAndModel(
            String tenantId,
            String model,
            Pageable pageable
    );

    /**
     * Find all entries by mode.
     */
    Page<CacheEntryEntity> findByTenantIdAndMode(
            String tenantId,
            String mode,
            Pageable pageable
    );

    /**
     * Find golden entries.
     */
    List<CacheEntryEntity> findByTenantIdAndIsGoldenTrue(String tenantId);

    /**
     * Find expired entries.
     */
    @Query("SELECT e FROM CacheEntryEntity e WHERE e.expiresAt IS NOT NULL AND e.expiresAt < :now AND e.isGolden = false")
    List<CacheEntryEntity> findExpiredEntries(@Param("now") Instant now);

    /**
     * Delete expired entries.
     */
    @Modifying
    @Query("DELETE FROM CacheEntryEntity e WHERE e.expiresAt IS NOT NULL AND e.expiresAt < :now AND e.isGolden = false")
    int deleteExpiredEntries(@Param("now") Instant now);

    /**
     * Count entries by tenant.
     */
    long countByTenantId(String tenantId);

    /**
     * Count active entries (not expired).
     */
    @Query("SELECT COUNT(e) FROM CacheEntryEntity e WHERE e.tenantId = :tenantId AND (e.expiresAt IS NULL OR e.expiresAt > :now)")
    long countActiveEntries(@Param("tenantId") String tenantId, @Param("now") Instant now);

    /**
     * Get total hits for tenant.
     */
    @Query("SELECT COALESCE(SUM(e.hitCount), 0) FROM CacheEntryEntity e WHERE e.tenantId = :tenantId")
    long sumHitCountByTenantId(@Param("tenantId") String tenantId);

    /**
     * Get average hit count.
     */
    @Query("SELECT COALESCE(AVG(e.hitCount), 0) FROM CacheEntryEntity e WHERE e.tenantId = :tenantId")
    double avgHitCountByTenantId(@Param("tenantId") String tenantId);

    /**
     * Find entries created after a certain time.
     */
    List<CacheEntryEntity> findByTenantIdAndCreatedAtAfter(String tenantId, Instant after);

    /**
     * Find entries accessed recently.
     */
    @Query("SELECT e FROM CacheEntryEntity e WHERE e.tenantId = :tenantId AND e.lastHitAt > :after ORDER BY e.lastHitAt DESC")
    List<CacheEntryEntity> findRecentlyAccessed(@Param("tenantId") String tenantId, @Param("after") Instant after);
}
