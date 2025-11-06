package com.recurra.service.compatibility;

import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.RequestMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Detects the request mode to ensure cache compatibility.
 *
 * Mode detection prevents cache poisoning scenarios like:
 * - Returning plain text when JSON format is required
 * - Using wrong tool schema for function calls
 * - Schema validation failures
 *
 * Cache entries are ONLY matched within the same mode.
 */
@Slf4j
@Service
public class ModeDetector {

    /**
     * Detect request mode from the request structure.
     *
     * Priority (first match wins):
     * 1. JSON_SCHEMA - if response_format.type = "json_schema"
     * 2. JSON_OBJECT - if response_format.type = "json_object"
     * 3. TOOLS - if tools array present
     * 4. FUNCTION - if functions array present (legacy)
     * 5. TEXT - default
     *
     * @param request incoming chat completion request
     * @return detected mode
     */
    public RequestMode detect(ChatCompletionRequest request) {
        // Check response_format for JSON modes
        // Note: response_format is not yet modeled in ChatCompletionRequest
        // TODO: Add response_format field to ChatCompletionRequest model
        // For now, we'll detect TOOLS and FUNCTION modes

        // Check for tools (function calling v2)
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            log.debug("Detected TOOLS mode: {} tools present", request.getTools().size());
            return RequestMode.TOOLS;
        }

        // Check for functions (legacy)
        if (request.getFunctions() != null && !request.getFunctions().isEmpty()) {
            log.debug("Detected FUNCTION mode: {} functions present", request.getFunctions().size());
            return RequestMode.FUNCTION;
        }

        // Default to TEXT mode
        log.debug("Detected TEXT mode (default)");
        return RequestMode.TEXT;
    }

    /**
     * Check if two modes are compatible for caching.
     *
     * Currently, modes must match exactly. Future enhancement could allow
     * compatible mode matching (e.g., JSON_OBJECT and JSON_SCHEMA might be
     * compatible in some cases).
     *
     * @param requestMode mode of incoming request
     * @param cacheMode mode of cached entry
     * @return true if compatible
     */
    public boolean areCompatible(RequestMode requestMode, RequestMode cacheMode) {
        boolean compatible = requestMode == cacheMode;

        if (!compatible) {
            log.debug("Mode mismatch: request={}, cache={}", requestMode, cacheMode);
        }

        return compatible;
    }

    /**
     * Get mode as string for storage.
     *
     * @param mode request mode
     * @return lowercase string representation
     */
    public String modeToString(RequestMode mode) {
        return mode.name().toLowerCase();
    }

    /**
     * Parse mode from string.
     *
     * @param modeString stored mode string
     * @return parsed request mode, defaults to TEXT if unrecognized
     */
    public RequestMode stringToMode(String modeString) {
        if (modeString == null || modeString.isEmpty()) {
            return RequestMode.TEXT;
        }

        try {
            return RequestMode.valueOf(modeString.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognized mode string: {}, defaulting to TEXT", modeString);
            return RequestMode.TEXT;
        }
    }
}
