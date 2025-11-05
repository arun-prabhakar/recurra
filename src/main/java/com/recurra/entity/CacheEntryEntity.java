package com.recurra.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.recurra.repository.converter.JsonNodeConverter;
import com.recurra.repository.converter.VectorConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for cache_entries table.
 * Stores cached AI responses with template-aware matching metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cache_entries", indexes = {
        @Index(name = "idx_tenant_exact_key", columnList = "tenant_id, exact_key"),
        @Index(name = "idx_tenant_simhash", columnList = "tenant_id, simhash"),
        @Index(name = "idx_tenant_model_mode", columnList = "tenant_id, model, mode")
})
public class CacheEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

    // Keys
    @Column(name = "exact_key", nullable = false, unique = true, length = 64)
    private String exactKey;

    @Column(name = "simhash", nullable = false)
    private Long simhash;

    // Embedding (384 dimensions)
    @Convert(converter = VectorConverter.class)
    @Column(name = "embedding", columnDefinition = "vector(384)")
    private float[] embedding;

    // Content
    @Column(name = "canonical_prompt", nullable = false, columnDefinition = "TEXT")
    private String canonicalPrompt;

    @Column(name = "raw_prompt_hmac", length = 64)
    private String rawPromptHmac;

    @Convert(converter = JsonNodeConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode requestJson;

    @Convert(converter = JsonNodeConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode responseJson;

    // Metadata
    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "temperature_bucket", length = 16)
    private String temperatureBucket;

    @Column(name = "mode", nullable = false, length = 32)
    private String mode;

    @Column(name = "tool_schema_hash", length = 64)
    private String toolSchemaHash;

    // Stats
    @Column(name = "hit_count")
    private Integer hitCount = 0;

    @Column(name = "last_hit_at")
    private Instant lastHitAt;

    // Flags
    @Column(name = "is_golden")
    private Boolean isGolden = false;

    @Column(name = "pii_present")
    private Boolean piiPresent = false;

    // Timestamps
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    // Extensible metadata
    @Convert(converter = JsonNodeConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private JsonNode metadata;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (tenantId == null) {
            tenantId = "default";
        }
        if (hitCount == null) {
            hitCount = 0;
        }
        if (isGolden == null) {
            isGolden = false;
        }
        if (piiPresent == null) {
            piiPresent = false;
        }
    }

    /**
     * Check if this entry is expired.
     */
    public boolean isExpired() {
        if (isGolden) {
            return false; // Golden entries never expire
        }
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    /**
     * Check if this entry is active (not expired).
     */
    public boolean isActive() {
        return !isExpired();
    }

    /**
     * Increment hit count.
     */
    public void incrementHitCount() {
        this.hitCount = (this.hitCount != null ? this.hitCount : 0) + 1;
        this.lastHitAt = Instant.now();
    }
}
