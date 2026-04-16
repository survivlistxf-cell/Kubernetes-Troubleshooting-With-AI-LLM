package com.example.controllers;

import com.example.entities.Chat;
import com.example.entities.Conversation;
import com.example.entities.User;
import com.example.repositories.UserRepository;
import com.example.services.AiForwardingService;
import com.example.services.ChatService;
import com.example.utils.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final AiForwardingService aiService;
    private final UserRepository userRepository;

    public ChatController(ChatService chatService, AiForwardingService aiService, UserRepository userRepository) {
        this.chatService = chatService;
        this.aiService = aiService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> request) {
        String requestId = UUID.randomUUID().toString(); // id pentru mesaj, pentru a putea fi traced back, unic per
                                                         // mesaj, este efemer, nu persista in baza de date
        String userId = Utils.asString(request.get("userId"));
        String userMessage = Utils.extractUserMessage(request);
        String conversationId = Utils.asString(request.get("conversationId")); // identificatorul intregii conversatii,
                                                                               // apare in baza de date
        Object attachmentsObj = request.get("attachments");

        if (userMessage == null || userMessage.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Message cannot be empty",
                    "expected", "{ message: string } OR { message: { text: string } }"));
        }

        // Forward to AI server
        AiForwardingService.ForwardResult forwardResult = aiService.forward(userId, conversationId, userMessage,
                attachmentsObj, requestId);
        String aiResponse = forwardResult != null ? forwardResult.text() : null;
        if (forwardResult != null && forwardResult.conversationId() != null) {
            conversationId = forwardResult.conversationId();
        }

        if (aiService.isAiHttpError(aiResponse)) {
            return ResponseEntity.status(502).body(Map.of(
                    "message", "AI backend rejected the request",
                    "status", aiService.extractHttpErrorCode(aiResponse)));
        }

        if (aiResponse == null) {
            logger.warn("[{}] AI forward unavailable; using legacy fallback", requestId);
            aiResponse = chatService.generateFallbackResponse(userMessage);
        } else {
            logger.info("[{}] AI forward OK", requestId);
        }

        // Salveaza conversatia in baza de date
        Map<String, Object> persistResult = chatService.persistChat(userId, conversationId, userMessage, aiResponse,
                attachmentsObj);
        // {
        // "conversationId": "550e8400-e29b-41d4-a716-446655440000",
        // "chatId": 142,
        // "attachments": [
        // {
        // "id": 89,
        // "name": "config-eroare.yaml",
        // "type": "text/x-yaml",
        // "size": 1024
        // }
        // ]
        // } Asta returneaza functia persistChat'

        // In conv se salveaza conversationId trimis de frontend sau
        // cel salvat din baza de date
        // Daca ramane salvat cel din frontend, nu se va putea
        // gasi conversatia in istoric pentru ca e redundant
        String conv = persistResult != null
                ? String.valueOf(persistResult.getOrDefault("conversationId", conversationId))
                : conversationId;
        Object chatId = persistResult != null ? persistResult.get("chatId") : null;
        Object attachmentsMeta = persistResult != null ? persistResult.get("attachments") : List.of();

        // Auto-generate title after first message
        chatService.autoGenerateTitleIfNeeded(conv, userId, requestId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("response", aiResponse);
        response.put("conversationId", conv);
        response.put("chatId", chatId);
        response.put("attachments", attachmentsMeta);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/ai-health")
    public ResponseEntity<?> aiHealth() {
        try {
            Map<?, ?> resp = aiService.checkHealth();
            return ResponseEntity.ok(Map.of("aiBaseUrl", aiService.getAiServerBaseUrl(), "health", resp));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "aiBaseUrl", aiService.getAiServerBaseUrl(), "message", "AI server unreachable", "error",
                    e.getMessage()));
        }
    }

    @PostMapping("/context")
    public ResponseEntity<?> ingestContext(@RequestBody Object request) {
        try {
            String json = new ObjectMapper().writeValueAsString(request);
            String userIdValue = null;
            if (request instanceof Map<?, ?> map) {
                Object direct = map.get("user_id");
                if (direct == null)
                    direct = map.get("userId");
                if (direct != null)
                    userIdValue = String.valueOf(direct);
            }

            if (userIdValue != null) {
                try {
                    Long uid = Long.parseLong(userIdValue);
                    userRepository.findById(uid).ifPresent(user -> chatService.saveSimpleChat(user, json, "[context]"));
                } catch (NumberFormatException ignored) {
                }
            }

            return ResponseEntity.ok(Map.of("message", "Context received", "persisted", userIdValue != null));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Failed to ingest context", "error", e.getMessage()));
        }
    }

    // ── Save chat (legacy) ──

    @PostMapping("/save")
    public ResponseEntity<?> saveChat(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        String userMessage = request.get("userMessage");
        String aiResponse = request.get("aiResponse");

        if (userId == null || userMessage == null || aiResponse == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Missing required fields"));
        }

        Optional<User> userOpt = userRepository.findById(Long.parseLong(userId));
        if (userOpt.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));

        Chat saved = chatService.saveSimpleChat(userOpt.get(), userMessage, aiResponse);
        return ResponseEntity.ok(Map.of("message", "Chat saved successfully", "chatId", saved.getId()));
    }

    // ── Conversations list ──

    @GetMapping("/conversations")
    public ResponseEntity<?> listConversations(@RequestParam Long userId) {
        Optional<User> userOpt = chatService.findUser(userId);
        if (userOpt.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));

        List<Map<String, Object>> out = chatService.listConversations(userOpt.get());
        return ResponseEntity.ok(Map.of("conversations", out, "count", out.size()));
    }

    // ── Conversation messages (resume) ──

    @GetMapping("/conversation/{conversationId}/messages")
    public ResponseEntity<?> getConversationMessages(@PathVariable String conversationId, @RequestParam Long userId) {
        if (conversationId == null || conversationId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));

        Optional<User> userOpt = chatService.findUser(userId);
        if (userOpt.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));

        Conversation conv = chatService.findConversation(conversationId).orElse(null);
        if (conv == null || conv.getUser() == null || !userId.equals(conv.getUser().getId()))
            return ResponseEntity.status(404).body(Map.of("message", "Conversation not found"));

        try {
            List<Map<String, Object>> chatDtos = chatService.getChatDtosWithAttachments(conversationId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conversationId", conversationId);
            result.put("title", conv.getTitle() != null ? conv.getTitle() : "");
            result.put("chats", chatDtos);
            result.put("count", chatDtos.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Internal Server Error",
                    "message", e.getMessage() != null ? e.getMessage() : "null",
                    "trace", sw.toString()));
        }
    }

    // ── Delete conversation ──

    @DeleteMapping("/conversation/{conversationId}")
    public ResponseEntity<?> deleteConversation(@PathVariable String conversationId) {
        if (conversationId == null || conversationId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));

        try {
            boolean existed = chatService.conversationExists(conversationId);
            chatService.deleteConversation(conversationId);
            return ResponseEntity.ok(Map.of("deleted", true, "conversationId", conversationId, "existed", existed));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("deleted", false, "conversationId", conversationId, "error", e.getMessage()));
        }
    }

    // ── Edit title ──

    @PatchMapping("/conversation/{conversationId}/title")
    public ResponseEntity<?> updateConversationTitle(
            @PathVariable String conversationId, @RequestParam Long userId, @RequestBody Map<String, Object> body) {
        if (conversationId == null || conversationId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));

        String title = Utils.asString(body.get("title"));
        if (title == null || title.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "title required"));
        if (title.length() > 255)
            title = title.substring(0, 255);

        Optional<User> userOpt = chatService.findUser(userId);
        if (userOpt.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));

        Conversation conv = chatService.findConversation(conversationId).orElse(null);
        if (conv == null || conv.getUser() == null || !userId.equals(conv.getUser().getId()))
            return ResponseEntity.status(404).body(Map.of("message", "Conversation not found"));

        chatService.updateTitle(conv, title);
        return ResponseEntity.ok(Map.of("conversationId", conversationId, "title", conv.getTitle()));
    }

    // ── Regenerate title ──

    @PostMapping("/conversation/{conversationId}/title:regenerate")
    public ResponseEntity<?> regenerateTitle(@PathVariable String conversationId, @RequestParam Long userId) {
        if (conversationId == null || conversationId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));

        Optional<User> userOpt = chatService.findUser(userId);
        if (userOpt.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));

        Conversation conv = chatService.findConversation(conversationId).orElse(null);
        if (conv == null || conv.getUser() == null || !userId.equals(conv.getUser().getId()))
            return ResponseEntity.status(404).body(Map.of("message", "Conversation not found"));

        String finalTitle = chatService.regenerateTitle(conversationId, String.valueOf(userId),
                UUID.randomUUID().toString(), true);
        return ResponseEntity.ok(Map.of("conversationId", conversationId, "title", finalTitle));
    }

    @PostMapping("/conversation/{conversationId}/feedback")
    public ResponseEntity<?> submitFeedback(@PathVariable String conversationId, @RequestBody Map<String, Integer> body) {
        Integer score = body.get("score");
        if (score == null) return ResponseEntity.badRequest().body(Map.of("message", "score required"));
        
        chatService.updateFeedback(conversationId, score);
        aiService.submitFeedback(conversationId, score);
        return ResponseEntity.ok(Map.of("success", true));
    }

}
