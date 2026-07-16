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
 * Calls the GitHub Models OpenAI-compatible chat completion endpoint.
 * Drop-in replacement for OpenAI — same request/response format.
 *
 * Switch back to real OpenAI: change base-url in application.yml to
 * https://api.openai.com/v1 and set OPENAI_API_KEY env var.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final String     apiKey;
    private final String     chatModel;
    private final String     baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public LlmService(
            @Value("${app.openai.api-key}")    String apiKey,
            @Value("${app.openai.chat-model}") String chatModel,
            @Value("${app.openai.base-url}")   String baseUrl
    ) {
        this.apiKey     = apiKey;
        this.chatModel  = chatModel;
        this.baseUrl    = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Send a single prompt to the chat model, get back the text response.
     *
     * @param systemPrompt  The system context (instructions for the AI)
     * @param userMessage   The user message (the actual question + context)
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
        // Build OpenAI-compatible chat request
        ObjectNode body = mapper.createObjectNode();
        body.put("model", chatModel);
        body.put("max_tokens", 150);
        body.put("temperature", 0.0);   // low temp for factual answers

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
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        log.info("Calling chat completion API using model {}",
                chatModel);

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            throw new RateLimitException("GitHub Models rate limit hit");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Chat API returned " + response.statusCode() + ": " + response.body()
            );
        }

        JsonNode json = mapper.readTree(response.body());
        String content = json
                .path("choices").get(0)
                .path("message")
                .path("content")
                .asText("");

        log.info("Received chat completion response");

        return content;
    }

    private static class RateLimitException extends RuntimeException {
        RateLimitException(String m) { super(m); }
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}