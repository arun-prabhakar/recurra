package com.recurra.service.embedding;

import ai.onnxruntime.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Local embedding service using ONNX Runtime.
 * Model: e5-small-v2 (384 dimensions)
 *
 * Target: < 50ms p95 latency for 512 tokens
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "recurra.embeddings", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalOnnxEmbeddingService implements EmbeddingService {

    private static final int MAX_LENGTH = 512;
    private static final int EMBEDDING_DIM = 384;
    private static final String MODEL_NAME = "e5-small-v2";

    @Value("${recurra.embeddings.model-path:src/main/resources/models/e5-small-v2.onnx}")
    private String modelPath;

    private OrtEnvironment env;
    private OrtSession session;
    private boolean ready = false;

    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing local ONNX embedding service...");
            log.info("Model path: {}", modelPath);

            // Create ONNX environment
            env = OrtEnvironment.getEnvironment();

            // Create session options
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            sessionOptions.setIntraOpNumThreads(4);
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            // Load model
            try {
                session = env.createSession(modelPath, sessionOptions);
                ready = true;
                log.info("ONNX embedding model loaded successfully");
                log.info("Model inputs: {}", session.getInputNames());
                log.info("Model outputs: {}", session.getOutputNames());
            } catch (Exception e) {
                log.error("Failed to load ONNX model from: {}", modelPath, e);
                log.warn("Embedding service will not be available");
                log.warn("To fix: Download e5-small-v2 model and place at: {}", modelPath);
                ready = false;
            }

        } catch (Exception e) {
            log.error("Error initializing embedding service", e);
            ready = false;
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (session != null) {
                session.close();
            }
            if (env != null) {
                env.close();
            }
            log.info("ONNX embedding service cleaned up");
        } catch (Exception e) {
            log.error("Error cleaning up embedding service", e);
        }
    }

    @Override
    public float[] embed(String text) {
        if (!ready) {
            log.warn("Embedding service not ready, returning zero vector");
            return new float[EMBEDDING_DIM];
        }

        if (text == null || text.isEmpty()) {
            return new float[EMBEDDING_DIM];
        }

        long startTime = System.nanoTime();

        try {
            // Tokenize (simplified - in production use proper tokenizer)
            long[] inputIds = tokenize(text);
            long[] attentionMask = createAttentionMask(inputIds.length);

            // Create input tensors
            long[] shape = {1, inputIds.length};

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape));

            // Run inference
            OrtSession.Result result = session.run(inputs);

            // Get output (last_hidden_state)
            float[][][] output = (float[][][]) result.get(0).getValue();

            // Mean pooling over sequence dimension
            float[] embedding = meanPooling(output[0], attentionMask);

            // L2 normalize
            embedding = normalize(embedding);

            // Cleanup
            for (OnnxTensor tensor : inputs.values()) {
                tensor.close();
            }
            result.close();

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsedMs > 50) {
                log.warn("Embedding generation took {}ms (> 50ms target)", elapsedMs);
            }

            return embedding;

        } catch (Exception e) {
            log.error("Error generating embedding", e);
            return new float[EMBEDDING_DIM];
        }
    }

    @Override
    public float[][] embedBatch(String[] texts) {
        // For now, just embed one by one
        // TODO: Implement true batch inference
        float[][] embeddings = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            embeddings[i] = embed(texts[i]);
        }
        return embeddings;
    }

    @Override
    public int dimensions() {
        return EMBEDDING_DIM;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    /**
     * Tokenize text (simplified).
     * In production, use proper WordPiece tokenizer from HuggingFace.
     */
    private long[] tokenize(String text) {
        // Simplified tokenization - just character codes
        // TODO: Use proper WordPiece tokenizer
        String normalized = text.toLowerCase().trim();

        if (normalized.length() > MAX_LENGTH) {
            normalized = normalized.substring(0, MAX_LENGTH);
        }

        long[] tokens = new long[Math.min(normalized.length(), MAX_LENGTH)];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = (long) normalized.charAt(i);
        }

        return tokens;
    }

    /**
     * Create attention mask (1 for real tokens, 0 for padding).
     */
    private long[] createAttentionMask(int length) {
        long[] mask = new long[length];
        Arrays.fill(mask, 1L);
        return mask;
    }

    /**
     * Mean pooling over sequence dimension.
     */
    private float[] meanPooling(float[][] hiddenStates, long[] attentionMask) {
        float[] pooled = new float[EMBEDDING_DIM];

        int seqLen = hiddenStates.length;

        for (int i = 0; i < seqLen; i++) {
            if (attentionMask[i] == 1) {
                for (int j = 0; j < EMBEDDING_DIM; j++) {
                    pooled[j] += hiddenStates[i][j];
                }
            }
        }

        // Divide by sequence length
        for (int j = 0; j < EMBEDDING_DIM; j++) {
            pooled[j] /= seqLen;
        }

        return pooled;
    }

    /**
     * L2 normalization.
     */
    private float[] normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);

        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }

        return vector;
    }
}
