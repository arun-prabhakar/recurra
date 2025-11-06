package com.recurra.model;

/**
 * Request mode classification for cache compatibility validation.
 *
 * Different modes have different cache requirements:
 * - TEXT: Plain chat completion
 * - JSON_OBJECT: Requires JSON response format
 * - JSON_SCHEMA: Requires specific JSON schema validation
 * - TOOLS: Uses tool calling (function calling v2)
 * - FUNCTION: Legacy function calling (deprecated)
 *
 * Cache entries are only matched within the same mode to prevent:
 * - TEXT response being returned for JSON request
 * - Wrong tool schema being used
 * - Schema validation failures
 */
public enum RequestMode {
    /**
     * Plain text completion without special formatting requirements.
     */
    TEXT,

    /**
     * Response must be valid JSON (OpenAI response_format: {type: "json_object"}).
     */
    JSON_OBJECT,

    /**
     * Response must conform to specific JSON schema
     * (OpenAI response_format: {type: "json_schema", json_schema: {...}}).
     */
    JSON_SCHEMA,

    /**
     * Request includes tools array (function calling v2).
     * Tool schema must match for cache hit.
     */
    TOOLS,

    /**
     * Request includes functions array (legacy, deprecated).
     * Function schema must match for cache hit.
     */
    FUNCTION
}
