package com.example.controllers;

import com.example.entities.Conversation;
import com.example.entities.User;
import com.example.security.CurrentUser;
import com.example.services.AiForwardingService;
import com.example.services.ChatService;
import com.example.utils.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final AiForwardingService aiService;

    public ChatController(ChatService chatService, AiForwardingService aiService) {
        this.chatService = chatService;
        this.aiService = aiService;
    }

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> request) {
        String requestId = UUID.randomUUID().toString(); // id pentru mesaj, pentru a putea fi traced back, unic per
                                                         // mesaj, este efemer, nu persista in baza de date
        // userId-ul efectiv vine din JWT (claim uid); valoarea din body e acceptata
        // doar daca coincide — altfel 403 (IDOR prevention).
        Long effectiveUserId = CurrentUser.resolve(Utils.asString(request.get("userId")));
        if (effectiveUserId == null && CurrentUser.id() != null) {
            return ResponseEntity.status(403).body(Map.of("message", "userId does not match authenticated user"));
        }
        String userId = effectiveUserId != null ? String.valueOf(effectiveUserId) : null;
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
        if (forwardResult != null && forwardResult.conversationId() != null) {
            conversationId = forwardResult.conversationId();
        }

        // AI server-ul a raspuns, dar cu 4xx/5xx: nu salvam nimic, semnalam clientului.
        if (forwardResult != null && forwardResult.errorKind() == AiForwardingService.ErrorKind.HTTP) {
            return ResponseEntity.status(502).body(Map.of(
                    "message", "AI backend rejected the request",
                    "status", forwardResult.httpStatus()));
        }

        String aiResponse = forwardResult != null ? forwardResult.text() : null;

        // UNREACHABLE / INTERNAL sau raspuns gol: nu inventam o diagnoza in gateway.
        // Degradarea gratioasa reala e in AI server (AiEngine.fallbackAnswer); daca nici
        // acela nu raspunde, utilizatorul trebuie sa afle ca serviciul e indisponibil,
        // nu sa primeasca un raspuns generic salvat in istoric ca si cum ar fi o diagnoza.
        if (aiResponse == null || aiResponse.isBlank()) {
            logger.warn("[{}] AI server unavailable (kind={}); returning 503", requestId,
                    forwardResult != null ? forwardResult.errorKind() : "NO_RESULT");
            return ResponseEntity.status(503).body(Map.of(
                    "message", "AI service is temporarily unavailable. Please try again in a moment."));
        }
        logger.info("[{}] AI forward OK", requestId);

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

    // ── Streaming SSE endpoint ──

    /**
     * SSE proxy: forwards the request to the AI server's streaming endpoint and
     * relays events to the browser.  Persists the full accumulated response to DB
     * once the stream completes (or is cancelled).
     *
     * <p>Event types forwarded verbatim: {@code meta}, {@code chunk}, {@code done}, {@code error}.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody Map<String, Object> request) {
        String requestId = UUID.randomUUID().toString();
        Long effectiveUserId = CurrentUser.resolve(Utils.asString(request.get("userId")));
        if (effectiveUserId == null && CurrentUser.id() != null) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("userId does not match authenticated user").build());
        }
        String userId = effectiveUserId != null ? String.valueOf(effectiveUserId) : null;
        String userMessage = Utils.extractUserMessage(request);
        String conversationId = Utils.asString(request.get("conversationId"));
        Object attachmentsObj = request.get("attachments");

        if (userMessage == null || userMessage.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("Message cannot be empty").build());
        }

        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        final String finalConvId = conversationId;
        final String finalUserId = userId;
        final String finalUserMessage = userMessage;
        final Object finalAttachmentsObj = attachmentsObj;

        StringBuilder responseBuffer = new StringBuilder();
        final ObjectMapper chunkMapper = new ObjectMapper();

        return aiService.forwardStream(finalUserMessage, finalAttachmentsObj, finalConvId, requestId)
                // Intercept chunk events to accumulate the full response for persistence.
                // Chunks are JSON-wrapped ({"text":"..."}) by the AI server so that
                // leading/trailing whitespace inside chat-model tokens (" of", " the", …)
                // survives the SSE transport — the W3C spec strips a single leading
                // space from raw data: payloads, which would otherwise concatenate
                // streamed tokens into a single space-less blob.
                .doOnNext(sse -> {
                    if ("chunk".equals(sse.event())) {
                        String data = sse.data();
                        if (data == null) return;
                        try {
                            String text = chunkMapper.readTree(data).path("text").asText("");
                            responseBuffer.append(text);
                        } catch (Exception parseErr) {
                            // Fallback: tolerate legacy/raw payloads so persistence is not lost
                            responseBuffer.append(data);
                        }
                    }
                    // Capture the server-assigned conversationId from the meta event
                    // (the AI server may confirm or reassign it)
                })
                // After stream ends (complete / cancel / error), persist to DB
                .doFinally(signal -> {
                    String aiResponse = responseBuffer.toString().trim();
                    if (aiResponse.isBlank()) return;
                    try {
                        Map<String, Object> persistResult = chatService.persistChat(
                                finalUserId, finalConvId, finalUserMessage, aiResponse, finalAttachmentsObj);
                        String conv = persistResult != null
                                ? String.valueOf(persistResult.getOrDefault("conversationId", finalConvId))
                                : finalConvId;
                        chatService.autoGenerateTitleIfNeeded(conv, finalUserId, UUID.randomUUID().toString());
                        logger.info("[{}] Streamed chat persisted for conv={}", requestId, conv);
                    } catch (Exception e) {
                        logger.error("[{}] Failed to persist streamed chat", requestId, e);
                    }
                });
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

    // NOTE: ingestia de context structurat (kdiag/1.0) traieste exclusiv in
    // ContextController (POST /api/context) — duplicatul de aici a fost eliminat
    // ca sa existe o singura implementare de intretinut. La fel si POST /save
    // (legacy): scria in DB fara conversatie si nu era apelat de nimeni.

    // ── Conversations list ──

    @GetMapping("/conversations")
    public ResponseEntity<?> listConversations(@RequestParam(required = false) Long userId) {
        userId = CurrentUser.resolve(userId);
        if (userId == null) {
            return ResponseEntity.status(403).body(Map.of("message", "userId does not match authenticated user"));
        }
        Optional<User> userOpt = chatService.findUser(userId);
        if (userOpt.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));

        List<Map<String, Object>> out = chatService.listConversations(userOpt.get());
        return ResponseEntity.ok(Map.of("conversations", out, "count", out.size()));
    }

    // ── Conversation messages (resume) ──

    @GetMapping("/conversation/{conversationId}/messages")
    public ResponseEntity<?> getConversationMessages(@PathVariable String conversationId,
            @RequestParam(required = false) Long userId) {
        if (conversationId == null || conversationId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));

        userId = CurrentUser.resolve(userId);
        if (userId == null) {
            return ResponseEntity.status(403).body(Map.of("message", "userId does not match authenticated user"));
        }
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
            // Stack trace-ul ramane doar in log — nu expunem structura interna clientului.
            logger.error("Failed to load conversation messages for conv={}", conversationId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Internal Server Error",
                    "message", "Failed to load conversation messages"));
        }
    }

    // ── Delete conversation ──

    @DeleteMapping("/conversation/{conversationId}")
    public ResponseEntity<?> deleteConversation(@PathVariable String conversationId) {
        if (conversationId == null || conversationId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));

        // Ownership check: only the owner (from the JWT) may delete a conversation.
        // 404 (not 403) so the endpoint doesn't leak which conversation ids exist.
        Long tokenUserId = CurrentUser.id();
        if (tokenUserId != null) {
            Conversation conv = chatService.findConversation(conversationId).orElse(null);
            if (conv != null && (conv.getUser() == null || !tokenUserId.equals(conv.getUser().getId())))
                return ResponseEntity.status(404).body(Map.of("message", "Conversation not found"));
        }

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
            @PathVariable String conversationId, @RequestParam(required = false) Long userId,
            @RequestBody Map<String, Object> body) {
        if (conversationId == null || conversationId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));

        userId = CurrentUser.resolve(userId);
        if (userId == null) {
            return ResponseEntity.status(403).body(Map.of("message", "userId does not match authenticated user"));
        }

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
    public ResponseEntity<?> regenerateTitle(@PathVariable String conversationId,
            @RequestParam(required = false) Long userId) {
        if (conversationId == null || conversationId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));

        userId = CurrentUser.resolve(userId);
        if (userId == null) {
            return ResponseEntity.status(403).body(Map.of("message", "userId does not match authenticated user"));
        }

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

        // Ownership check (same rationale as delete: 404, not 403).
        Long tokenUserId = CurrentUser.id();
        if (tokenUserId != null) {
            Conversation conv = chatService.findConversation(conversationId).orElse(null);
            if (conv != null && (conv.getUser() == null || !tokenUserId.equals(conv.getUser().getId())))
                return ResponseEntity.status(404).body(Map.of("message", "Conversation not found"));
        }

        chatService.updateFeedback(conversationId, score);
        aiService.submitFeedback(conversationId, score);
        return ResponseEntity.ok(Map.of("success", true));
    }

}
