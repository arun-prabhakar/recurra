package com.recurra.service.embedding;

/**
 * Service for generating text embeddings.
 * Implementations can be local (ONNX) or remote (OpenAI, Cohere).
 */
public interface EmbeddingService {

    /**
     * Generate embedding vector for text.
     *
     * @param text input text
     * @return embedding vector (float array)
     */
    float[] embed(String text);

    /**
     * Generate embeddings for multiple texts (batch).
     *
     * @param texts input texts
     * @return array of embedding vectors
     */
    float[][] embedBatch(String[] texts);

    /**
     * Get embedding dimensions.
     *
     * @return number of dimensions in output vector
     */
    int dimensions();

    /**
     * Get model name/identifier.
     *
     * @return model name
     */
    String modelName();

    /**
     * Check if service is ready.
     *
     * @return true if ready to embed
     */
    boolean isReady();
}
