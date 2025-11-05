-- Initial schema for Recurra cache entries

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Enable pgcrypto for HMAC
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Cache entries table
CREATE TABLE cache_entries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',

    -- Keys
    exact_key VARCHAR(64) NOT NULL UNIQUE,
    simhash BIGINT NOT NULL,

    -- Embedding (384 dimensions for e5-small-v2)
    -- Note: vector type will be added in V2 migration
    embedding_placeholder TEXT,

    -- Content
    canonical_prompt TEXT NOT NULL,
    raw_prompt_hmac VARCHAR(64),
    request_json JSONB NOT NULL,
    response_json JSONB NOT NULL,

    -- Metadata
    model VARCHAR(128) NOT NULL,
    temperature_bucket VARCHAR(16),
    mode VARCHAR(32) NOT NULL,
    tool_schema_hash VARCHAR(64),

    -- Stats
    hit_count INTEGER DEFAULT 0,
    last_hit_at TIMESTAMP,

    -- Flags
    is_golden BOOLEAN DEFAULT FALSE,
    pii_present BOOLEAN DEFAULT FALSE,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP,

    -- Extensible metadata
    metadata JSONB
);

-- Indexes for performance
CREATE INDEX idx_tenant_exact_key ON cache_entries(tenant_id, exact_key);
CREATE INDEX idx_tenant_simhash ON cache_entries(tenant_id, simhash);
CREATE INDEX idx_tenant_model_mode ON cache_entries(tenant_id, model, mode);
CREATE INDEX idx_expires_at ON cache_entries(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_golden ON cache_entries(tenant_id) WHERE is_golden = TRUE;
CREATE INDEX idx_created_at ON cache_entries(created_at DESC);

-- Partial index for non-expired entries
CREATE INDEX idx_active_entries ON cache_entries(tenant_id, model, mode)
    WHERE (expires_at IS NULL OR expires_at > NOW());

-- GIN index for JSONB metadata
CREATE INDEX idx_metadata_gin ON cache_entries USING gin(metadata);
CREATE INDEX idx_request_json_gin ON cache_entries USING gin(request_json);

-- Full text search on canonical prompt
CREATE INDEX idx_canonical_prompt_fts ON cache_entries
    USING gin(to_tsvector('english', canonical_prompt));

-- Comment on table
COMMENT ON TABLE cache_entries IS 'Stores cached AI responses with template-aware matching';
COMMENT ON COLUMN cache_entries.exact_key IS 'SHA-256 hash of canonicalized request';
COMMENT ON COLUMN cache_entries.simhash IS '64-bit structural fingerprint for similarity matching';
COMMENT ON COLUMN cache_entries.canonical_prompt IS 'Normalized prompt with placeholders';
COMMENT ON COLUMN cache_entries.raw_prompt_hmac IS 'HMAC of raw prompt for deduplication';
COMMENT ON COLUMN cache_entries.mode IS 'Request mode: text, json_object, json_schema, tools, function';
COMMENT ON COLUMN cache_entries.tool_schema_hash IS 'SHA-256 hash of tool definitions';
COMMENT ON COLUMN cache_entries.is_golden IS 'Pinned entry that never expires';
