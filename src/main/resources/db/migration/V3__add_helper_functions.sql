-- Helper functions and triggers for cache management

-- Function to auto-update last_hit_at timestamp
CREATE OR REPLACE FUNCTION update_last_hit_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_hit_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to update last_hit_at when hit_count changes
CREATE TRIGGER trigger_update_last_hit_at
    BEFORE UPDATE OF hit_count ON cache_entries
    FOR EACH ROW
    WHEN (NEW.hit_count > OLD.hit_count)
    EXECUTE FUNCTION update_last_hit_at();

-- Function to clean up expired entries
CREATE OR REPLACE FUNCTION cleanup_expired_entries()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM cache_entries
    WHERE expires_at IS NOT NULL
      AND expires_at < NOW()
      AND is_golden = FALSE;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to get cache statistics
CREATE OR REPLACE FUNCTION get_cache_stats()
RETURNS TABLE (
    total_entries BIGINT,
    active_entries BIGINT,
    expired_entries BIGINT,
    golden_entries BIGINT,
    avg_hit_count NUMERIC,
    total_hits BIGINT,
    entries_by_mode JSONB,
    entries_by_model JSONB
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        COUNT(*)::BIGINT as total_entries,
        COUNT(*) FILTER (WHERE expires_at IS NULL OR expires_at > NOW())::BIGINT as active_entries,
        COUNT(*) FILTER (WHERE expires_at IS NOT NULL AND expires_at < NOW())::BIGINT as expired_entries,
        COUNT(*) FILTER (WHERE is_golden = TRUE)::BIGINT as golden_entries,
        ROUND(AVG(hit_count), 2) as avg_hit_count,
        SUM(hit_count)::BIGINT as total_hits,
        jsonb_object_agg(mode, mode_count) as entries_by_mode,
        jsonb_object_agg(model, model_count) as entries_by_model
    FROM cache_entries
    CROSS JOIN LATERAL (
        SELECT mode, COUNT(*) as mode_count
        FROM cache_entries
        GROUP BY mode
    ) mode_stats
    CROSS JOIN LATERAL (
        SELECT model, COUNT(*) as model_count
        FROM cache_entries
        GROUP BY model
    ) model_stats;
END;
$$ LANGUAGE plpgsql;

-- Function to compute Hamming distance between two 64-bit integers
CREATE OR REPLACE FUNCTION hamming_distance(a BIGINT, b BIGINT)
RETURNS INTEGER AS $$
BEGIN
    RETURN bit_count(a # b);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

COMMENT ON FUNCTION hamming_distance IS 'Calculate Hamming distance between two 64-bit SimHash values';

-- View for cache analytics
CREATE OR REPLACE VIEW cache_analytics AS
SELECT
    tenant_id,
    model,
    mode,
    COUNT(*) as entry_count,
    SUM(hit_count) as total_hits,
    ROUND(AVG(hit_count), 2) as avg_hits_per_entry,
    MAX(hit_count) as max_hits,
    MIN(created_at) as oldest_entry,
    MAX(created_at) as newest_entry,
    COUNT(*) FILTER (WHERE is_golden = TRUE) as golden_count,
    COUNT(*) FILTER (WHERE last_hit_at > NOW() - INTERVAL '24 hours') as active_last_24h
FROM cache_entries
GROUP BY tenant_id, model, mode
ORDER BY total_hits DESC;

COMMENT ON VIEW cache_analytics IS 'Aggregated cache statistics by tenant, model, and mode';
