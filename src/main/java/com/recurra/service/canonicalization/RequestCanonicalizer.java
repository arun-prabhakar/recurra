package com.recurra.service.canonicalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recurra.model.ChatCompletionRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Canonicalizes chat completion requests for stable cache keys.
 *
 * Steps:
 * 1. Remove null/default values
 * 2. Sort JSON keys recursively
 * 3. Round floating point numbers
 * 4. Normalize whitespace in strings
 * 5. Generate SHA-256 hash
 *
 * Target: Same logical request → same canonical form → same hash
 */
@Slf4j
@Service
public class RequestCanonicalizer {

    private final ObjectMapper objectMapper;

    // Default values to remove (OpenAI defaults)
    private static final Map<String, Object> DEFAULTS = Map.of(
            "temperature", 1.0,
            "top_p", 1.0,
            "n", 1,
            "stream", false,
            "presence_penalty", 0.0,
            "frequency_penalty", 0.0
    );

    private static final int FLOAT_PRECISION = 2; // Round to 2 decimal places

    public RequestCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Generate canonical hash for request.
     *
     * @param request chat completion request
     * @return SHA-256 hash (64 hex chars)
     */
    public String generateHash(ChatCompletionRequest request) {
        try {
            String canonical = canonicalize(request);
            return DigestUtils.sha256Hex(canonical);
        } catch (Exception e) {
            log.error("Error generating canonical hash", e);
            return null;
        }
    }

    /**
     * Generate canonical JSON string for request.
     *
     * @param request chat completion request
     * @return canonical JSON string
     */
    public String canonicalize(ChatCompletionRequest request) throws Exception {
        // Convert to JsonNode
        JsonNode node = objectMapper.valueToTree(request);

        // Canonicalize
        JsonNode canonical = canonicalizeNode(node);

        // Serialize with sorted keys (Jackson's SORT_PROPERTIES_ALPHABETICALLY doesn't work recursively)
        return serializeCanonical(canonical);
    }

    /**
     * Recursively canonicalize a JSON node.
     */
    private JsonNode canonicalizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isObject()) {
            return canonicalizeObject((ObjectNode) node);
        } else if (node.isArray()) {
            return canonicalizeArray((ArrayNode) node);
        } else if (node.isNumber()) {
            return canonicalizeNumber(node);
        } else if (node.isTextual()) {
            return objectMapper.getNodeFactory().textNode(normalizeString(node.asText()));
        } else {
            return node;
        }
    }

    /**
     * Canonicalize object node.
     * - Remove null values
     * - Remove default values
     * - Sort keys
     * - Recursively canonicalize values
     */
    private JsonNode canonicalizeObject(ObjectNode node) {
        ObjectNode canonical = objectMapper.createObjectNode();

        // Get sorted field names
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        Collections.sort(fieldNames);

        // Process each field
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);

            // Skip null values
            if (value == null || value.isNull()) {
                continue;
            }

            // Skip default values
            if (isDefaultValue(fieldName, value)) {
                continue;
            }

            // Recursively canonicalize
            JsonNode canonicalValue = canonicalizeNode(value);
            if (canonicalValue != null && !canonicalValue.isNull()) {
                canonical.set(fieldName, canonicalValue);
            }
        }

        return canonical;
    }

    /**
     * Canonicalize array node.
     */
    private JsonNode canonicalizeArray(ArrayNode node) {
        ArrayNode canonical = objectMapper.createArrayNode();

        for (JsonNode element : node) {
            JsonNode canonicalElement = canonicalizeNode(element);
            if (canonicalElement != null) {
                canonical.add(canonicalElement);
            }
        }

        return canonical;
    }

    /**
     * Canonicalize number (round floats to precision).
     */
    private JsonNode canonicalizeNumber(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node;
        }

        // Round to fixed precision
        double value = node.asDouble();
        BigDecimal rounded = BigDecimal.valueOf(value)
                .setScale(FLOAT_PRECISION, RoundingMode.HALF_UP);

        return objectMapper.getNodeFactory().numberNode(rounded.doubleValue());
    }

    /**
     * Normalize string (trim, collapse whitespace).
     */
    private String normalizeString(String text) {
        if (text == null) {
            return "";
        }

        return text
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * Check if value matches default and should be omitted.
     */
    private boolean isDefaultValue(String fieldName, JsonNode value) {
        Object defaultValue = DEFAULTS.get(fieldName);
        if (defaultValue == null) {
            return false;
        }

        if (defaultValue instanceof Number) {
            if (value.isNumber()) {
                double actual = value.asDouble();
                double expected = ((Number) defaultValue).doubleValue();
                return Math.abs(actual - expected) < 0.0001;
            }
        } else if (defaultValue instanceof Boolean) {
            if (value.isBoolean()) {
                return value.asBoolean() == (Boolean) defaultValue;
            }
        } else {
            return Objects.equals(value.asText(), defaultValue.toString());
        }

        return false;
    }

    /**
     * Serialize JsonNode to canonical string with sorted keys.
     */
    private String serializeCanonical(JsonNode node) throws Exception {
        StringBuilder sb = new StringBuilder();
        serializeNode(node, sb);
        return sb.toString();
    }

    private void serializeNode(JsonNode node, StringBuilder sb) throws Exception {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isObject()) {
            sb.append("{");
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);

            boolean first = true;
            for (String fieldName : fieldNames) {
                if (!first) {
                    sb.append(",");
                }
                first = false;

                sb.append("\"").append(escapeJson(fieldName)).append("\":");
                serializeNode(node.get(fieldName), sb);
            }
            sb.append("}");
        } else if (node.isArray()) {
            sb.append("[");
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                serializeNode(element, sb);
            }
            sb.append("]");
        } else if (node.isTextual()) {
            sb.append("\"").append(escapeJson(node.asText())).append("\"");
        } else if (node.isNumber()) {
            sb.append(node.asText());
        } else if (node.isBoolean()) {
            sb.append(node.asBoolean());
        }
    }

    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Generate canonical hash with custom salt (for HMAC).
     */
    public String generateHmac(ChatCompletionRequest request, String secret) {
        try {
            String canonical = canonicalize(request);
            return DigestUtils.sha256Hex(canonical + secret);
        } catch (Exception e) {
            log.error("Error generating HMAC", e);
            return null;
        }
    }
}
