package com.recurra.service.similarity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SimHash generator for creating 64-bit structural fingerprints.
 * Similar texts produce similar fingerprints (low Hamming distance).
 *
 * Algorithm:
 * 1. Tokenize text (words + character n-grams)
 * 2. Hash each token to 64-bit value
 * 3. Weight each bit by token importance (TF-IDF-like)
 * 4. Generate final fingerprint from accumulated bits
 *
 * Target: < 5ms p95 latency
 */
@Slf4j
@Service
public class SimHashGenerator {

    private static final int HASH_BITS = 64;
    private static final int NGRAM_SIZE = 3;

    // Common stop words to downweight
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "be", "been",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "can", "this", "that", "these", "those"
    );

    // Patterns for tokenization
    private static final Pattern WORD_PATTERN = Pattern.compile("\\w+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Generate 64-bit SimHash fingerprint for text.
     *
     * @param text input text
     * @return 64-bit hash value
     */
    public long generate(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }

        long startTime = System.nanoTime();

        try {
            // Normalize text
            String normalized = normalize(text);

            // Extract tokens with weights
            Map<String, Integer> tokens = tokenize(normalized);

            // Generate fingerprint
            long fingerprint = computeFingerprint(tokens);

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsedMs > 5) {
                log.warn("SimHash generation took {}ms (> 5ms target) for {} chars",
                        elapsedMs, text.length());
            }

            return fingerprint;

        } catch (Exception e) {
            log.error("Error generating SimHash", e);
            return 0L;
        }
    }

    /**
     * Calculate Hamming distance between two SimHash values.
     * Hamming distance = number of differing bits.
     *
     * @param hash1 first hash
     * @param hash2 second hash
     * @return Hamming distance (0-64)
     */
    public int hammingDistance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    /**
     * Check if two hashes are similar (Hamming distance within threshold).
     *
     * @param hash1 first hash
     * @param hash2 second hash
     * @param threshold maximum Hamming distance (default: 6)
     * @return true if similar
     */
    public boolean isSimilar(long hash1, long hash2, int threshold) {
        return hammingDistance(hash1, hash2) <= threshold;
    }

    /**
     * Normalize text for consistent hashing.
     */
    private String normalize(String text) {
        return text
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Tokenize text and compute weights.
     * Uses word tokens + character n-grams.
     */
    private Map<String, Integer> tokenize(String text) {
        Map<String, Integer> tokens = new HashMap<>();

        // Word tokens
        String[] words = WHITESPACE.split(text);
        for (String word : words) {
            if (word.length() < 2) continue;

            int weight = computeWeight(word, words.length);
            tokens.merge(word, weight, Integer::sum);
        }

        // Character n-grams for better similarity on typos
        if (text.length() >= NGRAM_SIZE) {
            for (int i = 0; i <= text.length() - NGRAM_SIZE; i++) {
                String ngram = text.substring(i, i + NGRAM_SIZE);
                // N-grams have lower weight than words
                tokens.merge(ngram, 1, Integer::sum);
            }
        }

        return tokens;
    }

    /**
     * Compute weight for a token (TF-IDF-like).
     * Stop words get lower weight.
     */
    private int computeWeight(String token, int totalTokens) {
        // Base weight
        int weight = 10;

        // Downweight stop words
        if (STOP_WORDS.contains(token)) {
            weight = 2;
        }

        // Upweight longer tokens (more distinctive)
        if (token.length() > 8) {
            weight += 5;
        }

        // Boost technical terms (contain numbers or special patterns)
        if (token.matches(".*\\d.*") || token.matches(".*[_-].*")) {
            weight += 3;
        }

        return weight;
    }

    /**
     * Compute 64-bit fingerprint from weighted tokens.
     */
    private long computeFingerprint(Map<String, Integer> tokens) {
        // Accumulator for each bit position
        int[] bitVector = new int[HASH_BITS];

        // For each token, hash it and accumulate weighted bits
        for (Map.Entry<String, Integer> entry : tokens.entrySet()) {
            String token = entry.getKey();
            int weight = entry.getValue();

            long hash = murmurHash64(token);

            // For each bit in the hash
            for (int i = 0; i < HASH_BITS; i++) {
                if ((hash & (1L << i)) != 0) {
                    bitVector[i] += weight;  // Bit is 1
                } else {
                    bitVector[i] -= weight;  // Bit is 0
                }
            }
        }

        // Generate final fingerprint
        long fingerprint = 0L;
        for (int i = 0; i < HASH_BITS; i++) {
            if (bitVector[i] > 0) {
                fingerprint |= (1L << i);
            }
        }

        return fingerprint;
    }

    /**
     * MurmurHash3 64-bit variant.
     * Fast, good distribution, non-cryptographic.
     */
    private long murmurHash64(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        long seed = 0x1234567890ABCDEFL;

        long h1 = seed;
        long h2 = seed;

        final long c1 = 0x87c37b91114253d5L;
        final long c2 = 0x4cf5ad432745937fL;

        int length = data.length;
        int nblocks = length / 16;

        // Process 16-byte blocks
        for (int i = 0; i < nblocks; i++) {
            int idx = i * 16;

            long k1 = getLong(data, idx);
            long k2 = getLong(data, idx + 8);

            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        // Process remaining bytes
        long k1 = 0;
        long k2 = 0;

        int tail = nblocks * 16;
        switch (length & 15) {
            case 15: k2 ^= ((long) data[tail + 14]) << 48;
            case 14: k2 ^= ((long) data[tail + 13]) << 40;
            case 13: k2 ^= ((long) data[tail + 12]) << 32;
            case 12: k2 ^= ((long) data[tail + 11]) << 24;
            case 11: k2 ^= ((long) data[tail + 10]) << 16;
            case 10: k2 ^= ((long) data[tail + 9]) << 8;
            case 9:  k2 ^= ((long) data[tail + 8]);
                k2 *= c2;
                k2 = Long.rotateLeft(k2, 33);
                k2 *= c1;
                h2 ^= k2;

            case 8:  k1 ^= ((long) data[tail + 7]) << 56;
            case 7:  k1 ^= ((long) data[tail + 6]) << 48;
            case 6:  k1 ^= ((long) data[tail + 5]) << 40;
            case 5:  k1 ^= ((long) data[tail + 4]) << 32;
            case 4:  k1 ^= ((long) data[tail + 3]) << 24;
            case 3:  k1 ^= ((long) data[tail + 2]) << 16;
            case 2:  k1 ^= ((long) data[tail + 1]) << 8;
            case 1:  k1 ^= ((long) data[tail]);
                k1 *= c1;
                k1 = Long.rotateLeft(k1, 31);
                k1 *= c2;
                h1 ^= k1;
        }

        // Finalization
        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;

        return h1;
    }

    private long getLong(byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24)
                | (((long) data[offset + 4] & 0xff) << 32)
                | (((long) data[offset + 5] & 0xff) << 40)
                | (((long) data[offset + 6] & 0xff) << 48)
                | (((long) data[offset + 7] & 0xff) << 56);
    }

    private long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
