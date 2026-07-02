package com.example.controllers;

import com.example.services.AiForwardingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Set;

/**
 * Proxies the AI server's runtime RAG configuration to the frontend Settings page.
 *
 * <p>The AI server exposes {@code /v1/config/rag-mode} without authentication, but it is
 * not reachable from the browser; this controller re-exposes it under {@code /api/ai/**},
 * where the standard JWT filter applies, so only logged-in users can flip the mode.
 *
 * <p>Modes ({@code none | static | dynamic}) map to the evaluation configurations from
 * the thesis (no RAG / static RAG, air-gapped / static + dynamic retrieval).
 */
@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AiConfigController {

    private static final Logger logger = LoggerFactory.getLogger(AiConfigController.class);
    private static final Set<String> VALID_MODES = Set.of("none", "static", "dynamic");

    private final AiForwardingService aiService;

    public AiConfigController(AiForwardingService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/rag-mode")
    public ResponseEntity<?> getRagMode() {
        try {
            return ResponseEntity.ok(aiService.getRagMode());
        } catch (RestClientException e) {
            logger.warn("Could not read RAG mode from AI server: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "AI server unreachable"));
        }
    }

    @PostMapping("/rag-mode")
    public ResponseEntity<?> setRagMode(@RequestParam String value) {
        // Validate here as well so an invalid value never leaves the backend.
        String mode = value == null ? "" : value.trim().toLowerCase();
        if (!VALID_MODES.contains(mode)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid mode — expected none | static | dynamic"));
        }
        try {
            Map<?, ?> result = aiService.setRagMode(mode);
            logger.info("RAG mode switched to '{}'", mode);
            return ResponseEntity.ok(result);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", "AI server rejected the mode change"));
        } catch (RestClientException e) {
            logger.warn("Could not set RAG mode on AI server: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "AI server unreachable"));
        }
    }
}
