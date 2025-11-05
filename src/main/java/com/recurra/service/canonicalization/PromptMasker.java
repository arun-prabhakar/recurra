package com.recurra.service.canonicalization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks sensitive and variable content in prompts for template matching.
 *
 * Replaces:
 * - URLs → {URL}
 * - Emails → {EMAIL}
 * - Numbers → {NUM}
 * - Dates → {DATE}
 * - UUIDs → {UUID}
 * - Code identifiers → {VAR}
 *
 * Preserves structure while enabling fuzzy matching.
 */
@Slf4j
@Service
public class PromptMasker {

    // Regex patterns for masking (ordered by specificity)
    private static final List<MaskPattern> PATTERNS = Arrays.asList(
            // UUIDs (most specific first)
            new MaskPattern(
                    "UUID",
                    Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
                    "{UUID}"
            ),

            // URLs
            new MaskPattern(
                    "URL",
                    Pattern.compile("https?://[^\\s\\)\\]\\}\"'<>]+"),
                    "{URL}"
            ),

            // Email addresses
            new MaskPattern(
                    "EMAIL",
                    Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
                    "{EMAIL}"
            ),

            // ISO dates (YYYY-MM-DD)
            new MaskPattern(
                    "DATE",
                    Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
                    "{DATE}"
            ),

            // Dates (MM/DD/YYYY or DD/MM/YYYY)
            new MaskPattern(
                    "DATE",
                    Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b"),
                    "{DATE}"
            ),

            // IPv4 addresses
            new MaskPattern(
                    "IP",
                    Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
                    "{IP}"
            ),

            // Numbers with decimals
            new MaskPattern(
                    "NUM",
                    Pattern.compile("\\b\\d+\\.\\d+\\b"),
                    "{NUM}"
            ),

            // Large integers (likely IDs)
            new MaskPattern(
                    "NUM",
                    Pattern.compile("\\b\\d{4,}\\b"),
                    "{NUM}"
            ),

            // Phone numbers (US format)
            new MaskPattern(
                    "PHONE",
                    Pattern.compile("\\b\\d{3}[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b"),
                    "{PHONE}"
            ),

            // Credit card numbers (basic pattern)
            new MaskPattern(
                    "CARD",
                    Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"),
                    "{CARD}"
            ),

            // Hex strings (32+ chars, likely hashes/tokens)
            new MaskPattern(
                    "HASH",
                    Pattern.compile("\\b[0-9a-fA-F]{32,}\\b"),
                    "{HASH}"
            ),

            // File paths (Unix/Windows)
            new MaskPattern(
                    "PATH",
                    Pattern.compile("[/\\\\](?:[^/\\\\\\s]+[/\\\\])+[^/\\\\\\s]*"),
                    "{PATH}"
            )
    );

    // Pattern for code identifiers in backticks or code blocks
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```|`[^`]+`");
    private static final Pattern CODE_IDENTIFIER = Pattern.compile("\\b[a-z_][a-z0-9_]{2,}\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Mask sensitive content in text.
     *
     * @param text raw text
     * @return masked result
     */
    public MaskedPrompt mask(String text) {
        if (text == null || text.isEmpty()) {
            return MaskedPrompt.builder()
                    .masked("")
                    .rawHmac(DigestUtils.sha256Hex(""))
                    .metadata(Collections.emptyMap())
                    .build();
        }

        String masked = text;
        Map<String, List<String>> replacements = new HashMap<>();

        // Apply each pattern
        for (MaskPattern pattern : PATTERNS) {
            MaskResult result = applyPattern(masked, pattern);
            masked = result.getText();
            if (!result.getReplacements().isEmpty()) {
                replacements.put(pattern.getName(), result.getReplacements());
            }
        }

        // Mask code identifiers (variables in code blocks)
        masked = maskCodeIdentifiers(masked);

        // Generate HMAC of raw text for deduplication
        String rawHmac = DigestUtils.sha256Hex(text);

        return MaskedPrompt.builder()
                .masked(masked)
                .rawHmac(rawHmac)
                .metadata(replacements)
                .build();
    }

    /**
     * Apply a masking pattern to text.
     */
    private MaskResult applyPattern(String text, MaskPattern pattern) {
        StringBuilder result = new StringBuilder();
        List<String> replacements = new ArrayList<>();

        Matcher matcher = pattern.getPattern().matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            // Append text before match
            result.append(text, lastEnd, matcher.start());

            // Append placeholder
            result.append(pattern.getReplacement());

            // Record replacement
            replacements.add(matcher.group());

            lastEnd = matcher.end();
        }

        // Append remaining text
        result.append(text.substring(lastEnd));

        return new MaskResult(result.toString(), replacements);
    }

    /**
     * Mask variable names in code blocks.
     */
    private String maskCodeIdentifiers(String text) {
        Matcher codeMatcher = CODE_BLOCK.matcher(text);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (codeMatcher.find()) {
            // Append text before code block
            result.append(text, lastEnd, codeMatcher.start());

            // Get code block
            String codeBlock = codeMatcher.group();

            // Mask identifiers within code block
            String maskedCode = maskIdentifiersInCode(codeBlock);
            result.append(maskedCode);

            lastEnd = codeMatcher.end();
        }

        // Append remaining text
        result.append(text.substring(lastEnd));

        return result.toString();
    }

    /**
     * Mask identifiers within a code snippet.
     * Preserves structure (keywords, syntax) but masks variable names.
     */
    private String maskIdentifiersInCode(String code) {
        // Don't mask keywords
        Set<String> keywords = Set.of(
                "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
                "return", "function", "def", "class", "import", "from", "as", "try",
                "catch", "finally", "throw", "async", "await", "const", "let", "var",
                "public", "private", "protected", "static", "void", "int", "string",
                "boolean", "true", "false", "null", "undefined", "new", "this", "super"
        );

        Matcher matcher = CODE_IDENTIFIER.matcher(code);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            String identifier = matcher.group();

            result.append(code, lastEnd, matcher.start());

            // Don't mask keywords
            if (keywords.contains(identifier.toLowerCase())) {
                result.append(identifier);
            } else {
                result.append("{VAR}");
            }

            lastEnd = matcher.end();
        }

        result.append(code.substring(lastEnd));
        return result.toString();
    }

    /**
     * Check if text contains PII (personally identifiable information).
     */
    public boolean containsPii(String text) {
        if (text == null) {
            return false;
        }

        // Check for email, phone, credit card patterns
        for (MaskPattern pattern : PATTERNS) {
            if (pattern.getName().equals("EMAIL") ||
                    pattern.getName().equals("PHONE") ||
                    pattern.getName().equals("CARD")) {

                if (pattern.getPattern().matcher(text).find()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Masked prompt result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaskedPrompt {
        /**
         * Masked text with placeholders.
         */
        private String masked;

        /**
         * HMAC-SHA256 of raw text for deduplication.
         */
        private String rawHmac;

        /**
         * Metadata about replacements (for debugging).
         */
        private Map<String, List<String>> metadata;
    }

    /**
     * Pattern definition for masking.
     */
    @AllArgsConstructor
    private static class MaskPattern {
        private final String name;
        private final Pattern pattern;
        private final String replacement;

        public String getName() {
            return name;
        }

        public Pattern getPattern() {
            return pattern;
        }

        public String getReplacement() {
            return replacement;
        }
    }

    /**
     * Result of applying a mask pattern.
     */
    @AllArgsConstructor
    private static class MaskResult {
        private final String text;
        private final List<String> replacements;

        public String getText() {
            return text;
        }

        public List<String> getReplacements() {
            return replacements;
        }
    }
}
