package com.recurra.service;

import com.recurra.model.CacheControlContext;
import com.recurra.model.CacheHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Parses cache control headers from HTTP requests.
 *
 * Allows clients to control cache behavior per request using headers like:
 * - x-cache-bypass: Skip cache lookup
 * - x-cache-store: Control response storage
 * - x-cache-mode: Prefer exact or template matching
 * - x-model-compat: Override model compatibility rules
 * - x-cache-experiment: Tag for A/B testing
 */
@Slf4j
@Service
public class CacheControlParser {

    /**
     * Parse cache control context from HTTP headers.
     *
     * @param headers HTTP request headers
     * @return parsed cache control context (never null)
     */
    public CacheControlContext parse(HttpHeaders headers) {
        CacheControlContext.CacheControlContextBuilder builder = CacheControlContext.builder();

        // Parse bypass
        String bypass = headers.getFirst(CacheHeaders.CACHE_BYPASS);
        if (bypass != null) {
            builder.bypass(parseBoolean(bypass, false));
            if (parseBoolean(bypass, false)) {
                log.debug("Cache bypass requested via header");
            }
        }

        // Parse store
        String store = headers.getFirst(CacheHeaders.CACHE_STORE);
        if (store != null) {
            builder.store(parseBoolean(store, true));
            if (!parseBoolean(store, true)) {
                log.debug("Cache storage disabled via header");
            }
        }

        // Parse mode
        String mode = headers.getFirst(CacheHeaders.CACHE_MODE);
        if (mode != null) {
            CacheControlContext.CacheMode parsedMode = parseCacheMode(mode);
            builder.mode(parsedMode);
            log.debug("Cache mode override: {}", parsedMode);
        }

        // Parse model compatibility
        String modelCompat = headers.getFirst(CacheHeaders.MODEL_COMPAT);
        if (modelCompat != null) {
            CacheControlContext.ModelCompatPolicy policy = parseModelCompatPolicy(modelCompat);
            builder.modelCompat(policy);
            log.debug("Model compatibility override: {}", policy);
        }

        // Parse experiment
        String experiment = headers.getFirst(CacheHeaders.EXPERIMENT);
        if (experiment != null && !experiment.isBlank()) {
            builder.experiment(experiment.trim());
            log.debug("Experiment tag: {}", experiment);
        }

        return builder.build();
    }

    /**
     * Parse boolean from string.
     * Accepts: true/false, 1/0, yes/no, on/off (case-insensitive)
     */
    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        String normalized = value.trim().toLowerCase();

        return switch (normalized) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> {
                log.warn("Invalid boolean value: {}, using default: {}", value, defaultValue);
                yield defaultValue;
            }
        };
    }

    /**
     * Parse cache mode from string.
     */
    private CacheControlContext.CacheMode parseCacheMode(String value) {
        if (value == null || value.isBlank()) {
            return CacheControlContext.CacheMode.BOTH;
        }

        String normalized = value.trim().toUpperCase();

        try {
            return CacheControlContext.CacheMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cache mode: {}, using BOTH", value);
            return CacheControlContext.CacheMode.BOTH;
        }
    }

    /**
     * Parse model compatibility policy from string.
     */
    private CacheControlContext.ModelCompatPolicy parseModelCompatPolicy(String value) {
        if (value == null || value.isBlank()) {
            return CacheControlContext.ModelCompatPolicy.STRICT;
        }

        String normalized = value.trim().toUpperCase();

        try {
            return CacheControlContext.ModelCompatPolicy.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid model compat policy: {}, using STRICT", value);
            return CacheControlContext.ModelCompatPolicy.STRICT;
        }
    }
}
