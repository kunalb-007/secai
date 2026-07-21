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

/**
 * Calls any OpenAI-compatible chat completion endpoint.
 *
 * Supports three backends via application.yml:
 *
 *   1. GitHub Models (default for dev/staging):
 *      base-url: https://models.inference.ai.azure.com
 *      api-key:  ${GITHUB_TOKEN}
 *      chat-model: gpt-4o-mini
 *
 *   2. Ollama local (for local testing without API costs):
 *      base-url: http://localhost:11434/v1
 *      api-key:  ollama          ← Ollama ignores the key but the header must be present
 *      chat-model: qwen2.5:7b
 *
 *   3. OpenAI (for production):
 *      base-url: https://api.openai.com/v1
 *      api-key:  ${OPENAI_API_KEY}
 *      chat-model: gpt-4o-mini
 *
 * Switch between them by changing application.yml or setting env vars —
 * no code changes required.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final String     apiKey;
    private final String     chatModel;
    private final String     baseUrl;
    private final int        timeoutSeconds;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public LlmService(
            @Value("${app.openai.api-key}")              String apiKey,
            @Value("${app.openai.chat-model}")           String chatModel,
            @Value("${app.openai.base-url}")             String baseUrl,
            /**
             * Timeout in seconds for a single LLM completion call.
             *
             * Why configurable:
             *   - OpenAI/GitHub Models: 30s is plenty (server-side GPU, fast inference)
             *   - Ollama local on RTX 3050: first call loads model (~20s) + generates (~10s)
             *     = up to 120s. Subsequent calls are faster (~7-10s for 150 tokens).
             *   Set in application.yml per profile. Default 30s keeps prod behaviour unchanged.
             */
            @Value("${app.openai.timeout-seconds:30}")   int timeoutSeconds
    ) {
        this.apiKey         = apiKey;
        this.chatModel      = chatModel;
        this.baseUrl        = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient     = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Send a single prompt to the configured chat model.
     *
     * @param systemPrompt  System-level instructions
     * @param userMessage   The user message containing context + question
     * @return              The model's text response
     */
    public String complete(String systemPrompt, String userMessage) {
        int attempts = 0;
        int maxAttempts = 3;
        long delayMs = 2000;

        while (attempts < maxAttempts) {
            try {
                return callChatApi(systemPrompt, userMessage);
            } catch (RateLimitException e) {
                attempts++;
                if (attempts >= maxAttempts) {
                    throw new RuntimeException("LLM rate limit exceeded after retries", e);
                }
                log.warn("Rate limited. Waiting {}ms before retry {}/{}", delayMs, attempts, maxAttempts);
                sleepMs(delayMs);
                delayMs *= 2;
            } catch (Exception e) {
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("LLM call failed after " + maxAttempts + " attempts");
    }

    private String callChatApi(String systemPrompt, String userMessage) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", chatModel);
        body.put("max_tokens", 150);
        body.put("temperature", 0.0);

        // Ollama-specific: set stream=false explicitly to get a single JSON response.
        // OpenAI ignores this field when not streaming, so it's safe for all backends.
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                // Use the configurable timeout — critical for Ollama which loads the model
                // on first request. 30s default for cloud APIs, 120s for local Ollama.
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        log.info("Calling {} via {}", chatModel, baseUrl);

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            throw new RateLimitException("Rate limit hit");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Chat API returned HTTP " + response.statusCode()
                            + ". Body: " + response.body().substring(0, Math.min(200, response.body().length()))
            );
        }

        JsonNode json = mapper.readTree(response.body());

        // Guard against malformed responses — both OpenAI and Ollama should always
        // return choices[0].message.content, but defensive parsing prevents NPE.
        JsonNode choices = json.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException(
                    "LLM returned no choices. Full response: "
                            + response.body().substring(0, Math.min(500, response.body().length()))
            );
        }

        String content = choices.get(0)
                .path("message")
                .path("content")
                .asText("");

        if (content.isBlank()) {
            throw new RuntimeException("LLM returned empty content in choices[0].message.content");
        }

        log.info("LLM response received ({} chars)", content.length());
        return content;
    }

    private static class RateLimitException extends RuntimeException {
        RateLimitException(String m) { super(m); }
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}