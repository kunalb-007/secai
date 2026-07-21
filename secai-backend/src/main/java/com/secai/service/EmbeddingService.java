package com.secai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Calls OpenAI text-embedding-3-small to convert text → 1536-dim float vectors.
 *
 * Batches inputs to minimise API round-trips (up to 100 texts per call).
 * Handles rate-limit retries with exponential backoff.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final String  apiKey;
    private final String  model;
    private final int     batchSize;
    private final String  baseUrl;
    private final int dimensions;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public EmbeddingService(

            @Value("${app.openai.embedding-api-key}")
            String apiKey,

            @Value("${app.openai.embedding-model}")
            String model,

            @Value("${app.openai.embedding-dimensions}")
            int dimensions,

            @Value("${app.openai.batch-size:100}")
            int batchSize,

            @Value("${app.openai.embedding-base-url}")
            String baseUrl
    ) {
        this.apiKey    = apiKey;
        this.model     = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.baseUrl   = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Embed a list of texts. Returns a list of float[] vectors in the same order.
     * Automatically batches into groups of batchSize to respect OpenAI limits.
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        List<float[]> results = new ArrayList<>(texts.size());

        // Split into batches
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            log.info("Embedding batch {}/{} ({} texts)",
                    (i / batchSize) + 1,
                    (int) Math.ceil((double) texts.size() / batchSize),
                    batch.size()
            );
            results.addAll(embedBatch(batch));
        }

        return results;
    }

    /**
     * Embed a single text. Convenience method for one-off queries (e.g. question embedding).
     */
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.get(0);
    }

    /**
     * Convert a float[] embedding to the pgvector string format: "[0.1,0.2,...]"
     * Used when passing embedding to native SQL queries.
     */
    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // ---- Private methods ----

    private List<float[]> embedBatch(List<String> texts) {
        int attempts = 0;
        int maxAttempts = 3;
        long delayMs = 1000;

        while (attempts < maxAttempts) {
            try {
                return callOpenAiEmbeddingApi(texts);
            } catch (RateLimitException e) {
                attempts++;
                if (attempts >= maxAttempts) throw new RuntimeException("OpenAI rate limit exceeded after retries", e);
                log.warn("Rate limited by OpenAI. Waiting {}ms before retry {}/{}", delayMs, attempts, maxAttempts);
                sleepMs(delayMs);
                delayMs *= 2;  // exponential backoff
            } catch (Exception e) {
                throw new RuntimeException("Embedding API call failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("Embedding failed after " + maxAttempts + " attempts");
    }

    private List<float[]> callOpenAiEmbeddingApi(List<String> texts) throws Exception {
        // Build request JSON — GitHub Models OpenAI-compatible endpoint
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);

        ArrayNode inputArray = requestBody.putArray("input");
        texts.forEach(inputArray::add);

// Only for OpenAI / GitHub Models
        if (!baseUrl.contains("localhost:11434")) {
            requestBody.put("dimensions", dimensions);
            requestBody.put("encoding_format", "float");
        }

        String requestJson = mapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        log.info("Calling embedding API for {} text(s)", texts.size());

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            throw new RateLimitException("OpenAI rate limit hit");
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "OpenAI API returned " + response.statusCode()
                            + ": " + response.body()
            );
        }

        // Parse response: { "data": [{ "index": 0, "embedding": [0.1, ...] }, ...] }
        JsonNode responseJson = mapper.readTree(response.body());
        JsonNode data = responseJson.path("data");

        if (!data.isArray() || data.isEmpty()) {
            throw new RuntimeException("OpenAI returned empty embedding data");
        }

        // Sort by index to ensure order matches input
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        items.sort(Comparator.comparingInt(n -> n.path("index").asInt()));

        List<float[]> embeddings = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode embArray = item.path("embedding");
            float[] vec = new float[embArray.size()];
            for (int i = 0; i < embArray.size(); i++) {
                vec[i] = (float) embArray.get(i).asDouble();
            }
            embeddings.add(vec);
        }

        log.info("Received {} embedding(s) from OpenAI",
                embeddings.size());
        return embeddings;
    }

    private static class RateLimitException extends RuntimeException {
        RateLimitException(String message) { super(message); }
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}