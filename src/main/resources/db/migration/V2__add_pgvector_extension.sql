-- Add pgvector extension and embedding column

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Add embedding column (384 dimensions for e5-small-v2)
ALTER TABLE cache_entries
    DROP COLUMN IF EXISTS embedding_placeholder,
    ADD COLUMN embedding vector(384);

-- Create IVFFlat index for approximate nearest neighbor search
-- Using cosine distance operator (<=>)
-- lists=100 means 100 clusters (good for 10k-1M vectors)
CREATE INDEX idx_embedding_ivfflat ON cache_entries
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- Also create a btree index on embedding for exact lookups
CREATE INDEX idx_embedding_not_null ON cache_entries(id)
    WHERE embedding IS NOT NULL;

-- Comment
COMMENT ON COLUMN cache_entries.embedding IS '384-dimensional semantic embedding vector (e5-small-v2)';
COMMENT ON INDEX idx_embedding_ivfflat IS 'IVFFlat index for fast approximate nearest neighbor search';
