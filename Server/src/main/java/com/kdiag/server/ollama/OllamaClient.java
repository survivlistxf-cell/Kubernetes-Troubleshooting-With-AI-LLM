package com.kdiag.server.ollama;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal Ollama HTTP client.
 *
 * Uses Ollama's OpenAI-compatible endpoint:
 * POST {baseUrl}/v1/chat/completions
 */
@Component
public class OllamaClient {

    private static final Logger logger = LoggerFactory.getLogger(OllamaClient.class);
    private final WebClient webClient;

    private final String model;
    private final double temperature;
    private final Duration timeout;

    public OllamaClient(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.model:llama3.1}") String model,
            @Value("${ollama.temperature:0.2}") double temperature,
            @Value("${ollama.timeout-seconds:60}") long timeoutSeconds) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.model = model;
        this.temperature = temperature;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public String chat(List<Map<String, String>> messages) {
        Map<String, Object> req = Map.of(
                "model", model,
                "temperature", temperature,
                "stream", false,
                "messages", messages);

        try {
            // Trim individual message content for cleaner logs
            List<Map<String, String>> logMessages = messages.stream()
                    .map(m -> Map.of(
                            "role", m.getOrDefault("role", "unknown"),
                            "content", truncate(m.getOrDefault("content", ""), 100)))
                    .toList();
            logger.info("Request: model={} messages={}", model, logMessages);
        } catch (Exception ignored) {
        }

        OllamaChatResponse resp = webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(OllamaChatResponse.class)
                .timeout(timeout)
                .block();

        if (resp == null || resp.choices == null || resp.choices.isEmpty()) {
            return null;
        }

        OllamaChatResponse.Choice first = resp.choices.get(0);
        if (first == null || first.message == null) {
            return null;
        }
        return first.message.content;
    }

    public static class OllamaChatResponse {
        public List<Choice> choices;

        public static class Choice {
            public Message message;
        }

        public static class Message {
            public String role;
            public String content;
        }
    }

    private String truncate(String s, int max) {
        if (s == null)
            return "";
        if (s.length() <= max)
            return s;
        return s.substring(0, max) + "...";
    }
}
