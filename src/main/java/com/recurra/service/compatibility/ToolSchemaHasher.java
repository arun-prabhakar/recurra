package com.recurra.service.compatibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.TreeMap;

/**
 * Computes deterministic hashes of tool/function schemas for cache matching.
 *
 * Ensures that cached responses with tool calls are only used when the
 * exact same tools are available in the request.
 *
 * Example problem this solves:
 * - Request 1: tools=[get_weather, send_email]
 * - Request 2: tools=[get_weather, delete_file]
 * → Should NOT match! Different tool schemas.
 */
@Slf4j
@Service
public class ToolSchemaHasher {

    private final ObjectMapper objectMapper;

    public ToolSchemaHasher() {
        // Configure ObjectMapper for deterministic JSON serialization
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.objectMapper.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
    }

    /**
     * Compute hash of tools array.
     *
     * The hash is deterministic and based on:
     * - Tool names
     * - Tool descriptions
     * - Parameter schemas (names, types, required fields)
     *
     * Order-independent: tools=[A, B] produces same hash as tools=[B, A]
     *
     * @param tools list of tool definitions (as generic objects)
     * @return SHA-256 hash (hex), or null if no tools
     */
    public String hashTools(List<Object> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        try {
            // Sort tools by name for order-independence
            TreeMap<String, Object> sortedTools = new TreeMap<>();

            for (int i = 0; i < tools.size(); i++) {
                Object tool = tools.get(i);

                // Extract tool name for sorting
                String toolName = extractToolName(tool);
                if (toolName == null) {
                    toolName = "tool_" + i;  // Fallback
                }

                sortedTools.put(toolName, tool);
            }

            // Serialize to canonical JSON
            String canonicalJson = objectMapper.writeValueAsString(sortedTools);

            // Compute SHA-256 hash
            String hash = DigestUtils.sha256Hex(canonicalJson);

            log.debug("Computed tool schema hash: {} for {} tools", hash.substring(0, 8), tools.size());

            return hash;

        } catch (Exception e) {
            log.error("Failed to hash tools", e);
            return null;
        }
    }

    /**
     * Compute hash of functions array (legacy).
     *
     * @param functions list of function definitions
     * @return SHA-256 hash (hex), or null if no functions
     */
    public String hashFunctions(List<Object> functions) {
        // Functions use same hashing logic as tools
        return hashTools(functions);
    }

    /**
     * Check if two tool hashes are compatible.
     *
     * Currently requires exact match. Future enhancement could allow
     * subset matching (e.g., request uses subset of cached tools).
     *
     * @param requestHash hash of request tools
     * @param cacheHash hash of cached tools
     * @return true if compatible
     */
    public boolean areCompatible(String requestHash, String cacheHash) {
        // Null hashes are compatible (no tools)
        if (requestHash == null && cacheHash == null) {
            return true;
        }

        // One has tools, other doesn't - not compatible
        if (requestHash == null || cacheHash == null) {
            log.debug("Tool hash mismatch: request={}, cache={}", requestHash, cacheHash);
            return false;
        }

        // Exact hash match required
        boolean compatible = requestHash.equals(cacheHash);

        if (!compatible) {
            log.debug("Tool hash mismatch: request={}, cache={}",
                    requestHash.substring(0, 8), cacheHash.substring(0, 8));
        }

        return compatible;
    }

    /**
     * Extract tool name from tool definition.
     * Handles both Map and POJO structures.
     */
    @SuppressWarnings("unchecked")
    private String extractToolName(Object tool) {
        try {
            if (tool instanceof java.util.Map) {
                java.util.Map<String, Object> toolMap = (java.util.Map<String, Object>) tool;

                // OpenAI tools format: {type: "function", function: {name: "..."}}
                if (toolMap.containsKey("function")) {
                    Object function = toolMap.get("function");
                    if (function instanceof java.util.Map) {
                        Object name = ((java.util.Map<String, Object>) function).get("name");
                        if (name != null) {
                            return name.toString();
                        }
                    }
                }

                // Direct format: {name: "..."}
                if (toolMap.containsKey("name")) {
                    return toolMap.get("name").toString();
                }
            }

            // Fallback: use reflection to find "name" field
            try {
                java.lang.reflect.Method getName = tool.getClass().getMethod("getName");
                Object name = getName.invoke(tool);
                if (name != null) {
                    return name.toString();
                }
            } catch (Exception ignored) {
                // Reflection failed, continue
            }

        } catch (Exception e) {
            log.warn("Failed to extract tool name from: {}", tool.getClass().getSimpleName());
        }

        return null;
    }
}
