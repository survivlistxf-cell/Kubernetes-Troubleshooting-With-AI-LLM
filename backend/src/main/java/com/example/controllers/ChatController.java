package com.example.controllers;

import com.example.entities.Chat;
import com.example.entities.Conversation;
import com.example.entities.User;
import com.example.repositories.ChatRepository;
import com.example.repositories.ConversationRepository;
import com.example.repositories.ConversationContextRepository;
import com.example.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.*;

import com.example.entities.ConversationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class ChatController {
    
    @Autowired
    private ChatRepository chatRepository;
    
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationContextRepository conversationContextRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ai.server.base-url:http://localhost:8090}")
    private String aiServerBaseUrl;
    
    @PostMapping
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> request) {
        String requestId = UUID.randomUUID().toString();

        String userId = asString(request.get("userId"));
        String userMessage = extractUserMessage(request);
        String conversationId = asString(request.get("conversationId"));
        
        if (userMessage == null || userMessage.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Message cannot be empty",
                    "expected", "{ message: string } OR { message: { text: string } }",
                    "example", Map.of("message", "hello")
            ));
        }

        String aiResponse = tryForwardToAiServer(userId, conversationId, userMessage, requestId);
        if (aiResponse != null && aiResponse.startsWith("__AI_HTTP_ERROR__")) {
            // AI server is reachable but rejected the request (400/500 etc).
            // Don't mask this by falling back to legacy; surface an actionable error.
            String code = aiResponse.substring("__AI_HTTP_ERROR__".length());
            return ResponseEntity.status(502).body(Map.of(
                    "message", "AI backend rejected the request",
                    "status", code,
                    "hint", "Check AI server logs for validation error at /v1/chat. Ensure protocol_version,message.role,message.text are non-empty."
            ));
        }

        if (aiResponse == null) {
            // Fallback only when AI server isn't reachable.
            System.err.println("[" + requestId + "] AI forward unavailable; using legacy fallback");
            aiResponse = generateResponse(userMessage);
        } else {
            System.out.println("[" + requestId + "] AI forward OK");
        }

        // Persist chat (and ensure conversation metadata exists).
        String conv = persistChat(userId, conversationId, userMessage, aiResponse);

        // Auto-generate a conversation title ONLY once: after the first assistant reply of a conversation.
        // IMPORTANT: do NOT overwrite a title that was edited by the user.
        try {
            if (conv != null && !conv.isBlank()) {
                Conversation c = conversationRepository.findById(conv).orElse(null);
                if (c != null && !Boolean.TRUE.equals(c.getTitleCustom())) {
                    // Run only when the conversation has exactly 1 persisted chat row (the one we just saved).
                    // This makes it "first message only".
                    List<Chat> chatsDesc = chatRepository.findByConversationIdOrderByCreatedAtDesc(conv);
                    if (chatsDesc != null && chatsDesc.size() == 1) {
                        // AI title is optional; if AI fails, keep existing/heuristic.
                        regenerateTitleInternal(conv, userId, requestId);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return ResponseEntity.ok(Map.of(
                "response", aiResponse,
                "conversationId", conv
        ));
    }

    /**
     * Debug endpoint: verify backend -> AI server connectivity and supported response schema.
     */
    @GetMapping("/ai-health")
    public ResponseEntity<?> aiHealth() {
        try {
            Map<?, ?> resp = restTemplate.getForObject(aiServerBaseUrl + "/health", Map.class);
            return ResponseEntity.ok(Map.of(
                    "aiBaseUrl", aiServerBaseUrl,
                    "health", resp
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "aiBaseUrl", aiServerBaseUrl,
                    "message", "AI server unreachable",
                    "error", e.getMessage()
            ));
        }
    }

    private String tryForwardToAiServer(String userIdValue, String conversationId, String userMessage, String requestId) {
        try {
            List<Map<String, Object>> artifacts = buildArtifactsFromLatestContext(userIdValue, requestId);

            // AI server validates request.message and (in practice) expects a conversation_id.
            // The frontend already generates a conversationId for pods context; we'll reuse it here.
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = UUID.randomUUID().toString();
            }

            String safeText = userMessage == null ? "" : userMessage.trim();
            if (safeText.isBlank()) {
                // Should not happen because chat() validated, but guard anyway.
                return null;
            }

            // Bridge the legacy /api/chat payload to AI server kdiag/1.0 payload.
            // AI server expects message as an OBJECT: { role, text }
            Map<String, Object> message = Map.of(
                    "role", "user",
                    "text", safeText
            );

            // Artifacts coming from persisted context may contain nested objects in fields that
            // the AI server models expect as Strings (e.g., content/target/container). To avoid
            // hard failures (400 JSON parse error), sanitize artifacts to only forward primitive/string values.
            List<Map<String, Object>> safeArtifacts = sanitizeArtifactsForAi(artifacts, requestId);

            Map<String, Object> kdiag = (safeArtifacts == null || safeArtifacts.isEmpty())
                    ? Map.of(
                            "protocol_version", "kdiag/1.0",
                            "conversation_id", conversationId,
                            "message", message
                    )
                    : Map.of(
                            "protocol_version", "kdiag/1.0",
                            "conversation_id", conversationId,
                            "message", message,
                            "artifacts", safeArtifacts
                    );

            // Debug: log outgoing payload in a compact way (trim very large strings)
            try {
                String preview = new ObjectMapper().writeValueAsString(trimLargeStrings(kdiag, 500));
                System.out.println("[" + requestId + "] Forwarding to AI: " + preview);
            } catch (Exception ignored) {
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(kdiag, headers);

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> exchange = restTemplate.postForEntity(
            aiServerBaseUrl + "/v1/chat",
            entity,
            Map.class
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = exchange.getBody();

            if (resp == null) {
                return null;
            }

            // Support both shapes (older/newer AI server builds):
            // - { assistant_message: { text: ... } }
            // - { assistant: { text: ... } }
            Object assistantMessage = resp.get("assistant_message");
            if (!(assistantMessage instanceof Map<?, ?>)) {
                assistantMessage = resp.get("assistant");
            }

            if (assistantMessage instanceof Map<?, ?> msg) {
                Object text = msg.get("text");
                if (text != null) {
                    return String.valueOf(text);
                }
            }
            return null;
        } catch (HttpStatusCodeException e) {
            // AI server reachable but request invalid or server errored.
            // Log body to make debugging easy.
            String body = e.getResponseBodyAsString();
            HttpStatusCode status = e.getStatusCode();
            System.err.println("[" + requestId + "] AI server HTTP " + status + " for /v1/chat: " + body);
            // Don't fallback to legacy for 4xx/5xx; returning null triggers fallback.
            // Caller will decide whether to fallback or surface an error.
            return "__AI_HTTP_ERROR__" + status.value();
        } catch (ResourceAccessException e) {
            // Connection refused / timeout, etc.
            System.err.println("[" + requestId + "] AI server unreachable: " + e.getMessage());
            return null;
        } catch (RestClientException e) {
            System.err.println("[" + requestId + "] AI server forward failed: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("[" + requestId + "] AI forward internal error: " + e.getMessage());
            return null;
        }
    }

    private static List<Map<String, Object>> sanitizeArtifactsForAi(List<Map<String, Object>> artifacts, String requestId) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }

        try {
            return artifacts.stream().map(a -> {
                if (a == null) {
                    return null;
                }

                // Only forward known keys and coerce to String when possible.
                Object type = a.get("type");
                Object target = a.get("target");
                Object container = a.get("container");
                Object content = a.get("content");

                // Mandatory: type
                String typeStr = type == null ? null : String.valueOf(type);
                if (typeStr == null || typeStr.isBlank()) {
                    return null;
                }

                Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("type", typeStr);
                if (target != null) out.put("target", String.valueOf(target));
                if (container != null) out.put("container", String.valueOf(container));

                // content must be string; if it's an object/array, stringify it to keep AI server happy
                if (content != null) {
                    out.put("content", String.valueOf(content));
                }
                return out;
            }).filter(m -> m != null).toList();
        } catch (Exception e) {
            System.err.println("[" + requestId + "] Failed to sanitize artifacts; dropping artifacts. Error: " + e.getMessage());
            return List.of();
        }
    }

    private static Map<String, Object> trimLargeStrings(Map<String, Object> input, int maxLen) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                out.put(e.getKey(), s.length() > maxLen ? s.substring(0, maxLen) + "…" : s);
            } else if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                out.put(e.getKey(), trimLargeStrings(cast, maxLen));
            } else if (v instanceof List<?> list) {
                // don't deep-trim lists (could be huge); keep a short marker
                out.put(e.getKey(), "[" + list.size() + " item(s)]");
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /**
     * Reads the latest saved pods context for the user (if any) and maps it to AI-server artifacts.
     *
     * We keep it minimal: if the ingested payload contains `artifacts`, we forward them as-is.
     */
    private List<Map<String, Object>> buildArtifactsFromLatestContext(String userIdValue, String requestId) {
        if (userIdValue == null || userIdValue.isBlank()) {
            return List.of();
        }

        try {
            Long userId = Long.parseLong(userIdValue);
            Optional<ConversationContext> latestOpt = conversationContextRepository
                    .findFirstByUserIdOrderByCreatedAtDesc(userId);

            if (latestOpt.isEmpty()) {
                return List.of();
            }

            String payloadJson = latestOpt.get().getPayloadJson();
            if (payloadJson == null || payloadJson.isBlank()) {
                return List.of();
            }

            JsonNode root = new ObjectMapper().readTree(payloadJson);
            JsonNode artifactsNode = root.get("artifacts");
            if (artifactsNode == null || !artifactsNode.isArray() || artifactsNode.isEmpty()) {
                return List.of();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> artifacts = new ObjectMapper().convertValue(artifactsNode, List.class);
            return artifacts == null ? List.of() : artifacts;
        } catch (NumberFormatException e) {
            System.err.println("[" + requestId + "] Invalid userId for context lookup: " + userIdValue);
            return List.of();
        } catch (Exception e) {
            System.err.println("[" + requestId + "] Failed to load latest context: " + e.getMessage());
            return List.of();
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    /**
     * Supports both legacy payload:
     *   { "message": "..." }
     * and kdiag-ish payload:
     *   { "message": { "text": "..." } }
     */
    private static String extractUserMessage(Map<String, Object> request) {
        Object msg = request.get("message");

        if (msg instanceof String s) {
            return s;
        }

        if (msg instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text != null) {
                return String.valueOf(text);
            }
        }

        // Support shape: { message: { role: 'user', text: '...' } } (same as above)
        // AND: { message: { content: '...' } } (some clients)
        if (msg instanceof Map<?, ?> map2) {
            Object content = map2.get("content");
            if (content != null) {
                return String.valueOf(content);
            }
        }

        return null;
    }

    /**
     * Ingest structured diagnostic context (kdiag/1.0) and attach it to the user's conversation.
     *
     * For now we persist the entire JSON payload as the userMessage, with a fixed aiResponse marker.
     * This keeps changes minimal while enabling future server-side enrichment and retrieval.
     */
    @PostMapping("/context")
    public ResponseEntity<?> ingestContext(@RequestBody Object request) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(request);

            // Extract user_id/userId if present
            String userIdValue = null;
            if (request instanceof Map<?, ?> map) {
                Object direct = map.get("user_id");
                if (direct == null) {
                    direct = map.get("userId");
                }
                if (direct != null) {
                    userIdValue = String.valueOf(direct);
                }
            }

            if (userIdValue != null) {
                try {
                    Long userId = Long.parseLong(userIdValue);
                    Optional<User> userOpt = userRepository.findById(userId);
                    if (userOpt.isPresent()) {
                        Chat chat = new Chat();
                        chat.setUser(userOpt.get());
                        chat.setUserMessage(json);
                        chat.setAiResponse("[context]");
                        chatRepository.save(chat);
                    }
                } catch (NumberFormatException ignored) {
                    // If user id isn't parseable, we just won't persist.
                }
            }

            return ResponseEntity.ok(Map.of(
                "message", "Context received",
                "persisted", userIdValue != null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "Failed to ingest context",
                "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/save")
    public ResponseEntity<?> saveChat(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        String userMessage = request.get("userMessage");
        String aiResponse = request.get("aiResponse");
        
        if (userId == null || userMessage == null || aiResponse == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Missing required fields"));
        }
        
        Optional<User> userOpt = userRepository.findById(Long.parseLong(userId));
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));
        }
        
        Chat chat = new Chat();
        chat.setUser(userOpt.get());
        chat.setUserMessage(userMessage);
        chat.setAiResponse(aiResponse);
        
        Chat savedChat = chatRepository.save(chat);
        
        return ResponseEntity.ok(Map.of(
            "message", "Chat saved successfully",
            "chatId", savedChat.getId()
        ));
    }
    
    @GetMapping("/history/{userId}")
    public ResponseEntity<?> getChatHistory(@PathVariable Long userId) {
        Optional<User> userOpt = Optional.ofNullable(userId).flatMap(userRepository::findById);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));
        }
        
        List<Chat> chats = chatRepository.findByUserOrderByCreatedAtDesc(userOpt.get());

        // Provide a lightweight conversation index to enable resume
        // (group by conversationId, take most recent message per conversation),
        // and provide a compact conversation summary list with title + lastUpdated.
        Map<String, Chat> latestByConversation = new java.util.LinkedHashMap<>();
        for (Chat c : chats) {
            String conv = c.getConversationId();
            if (conv == null || conv.isBlank()) {
                continue;
            }
            // chats is ordered desc already; first hit is latest.
            latestByConversation.putIfAbsent(conv, c);
        }

        // Build conversation summaries (id, title, lastUpdated, snippet)
        // Title comes from normalized Conversation table when available.
        List<Map<String, Object>> convoSummaries = new java.util.ArrayList<>();
        for (Chat latest : latestByConversation.values()) {
            String conv = latest.getConversationId();

            String title = null;
            try {
                if (conv != null && !conv.isBlank()) {
                    title = conversationRepository.findById(conv)
                            .map(Conversation::getTitle)
                            .orElse(null);
                }
            } catch (Exception ignored) {
            }

            if (title == null || title.isBlank()) {
                title = deriveConversationTitle(latest.getUserMessage());
            }

            Map<String, Object> s = new java.util.LinkedHashMap<>();
            s.put("conversationId", conv);
            s.put("title", title);
            s.put("lastMessage", latest.getUserMessage());
            // Prefer conversation.updatedAt when possible
            try {
                if (conv != null && !conv.isBlank()) {
                    java.time.LocalDateTime updatedAt = conversationRepository.findById(conv)
                            .map(Conversation::getUpdatedAt)
                            .orElse(null);
                    s.put("lastUpdated", updatedAt != null ? updatedAt : latest.getCreatedAt());
                } else {
                    s.put("lastUpdated", latest.getCreatedAt());
                }
            } catch (Exception ignored) {
                s.put("lastUpdated", latest.getCreatedAt());
            }
            convoSummaries.add(s);
        }

        // If older rows have null conversationId, treat each as its own thread.
        // We'll still return them in chats for backward compatibility.
        return ResponseEntity.ok(Map.of(
                "chats", chats,
                "count", chats.size(),
                "conversations", convoSummaries
        ));
    }

    /**
     * Convenience endpoint used by the UI (query form) to avoid hard-coding path params.
     * Example: GET /api/chat/history?userId=123
     */
    @GetMapping("/history")
    public ResponseEntity<?> getChatHistoryByQuery(@RequestParam Long userId) {
        return getChatHistory(userId);
    }

    private String persistChat(String userIdValue, String conversationId, String userMessage, String aiResponse) {
        if (userIdValue == null) {
            return null;
        }

        try {
        Long userId = Long.parseLong(userIdValue);

        // Make conversation id stable and return it.
        final String conv = (conversationId == null || conversationId.isBlank())
            ? UUID.randomUUID().toString()
            : conversationId;

        userRepository.findById(userId).ifPresent(user -> {
                // Make conversation id effectively-final for use inside the lambda.
                // Ensure there's a normalized conversation row.
                ensureConversation(user, conv, userMessage);

                // Load the latest conversation metadata so we can denormalize into chats.
        final String convId = conv;
        Conversation convoRow = (convId == null || convId.isBlank())
            ? null
            : conversationRepository.findById(convId).orElse(null);
                String convoTitle = convoRow != null ? convoRow.getTitle() : deriveConversationTitle(userMessage);
                Boolean titleCustom = convoRow != null ? convoRow.getTitleCustom() : Boolean.FALSE;

                Chat chat = new Chat();
                chat.setUser(user);
                chat.setConversationId(conv);
                chat.setUserMessage(userMessage);
                chat.setAiResponse(aiResponse);
                chat.setConversationTitle(convoTitle);
                chat.setTitleCustom(titleCustom);

                chatRepository.save(chat);
            });
            return conv;
        } catch (NumberFormatException e) {
            System.err.println("Invalid userId for chat persistence: " + userIdValue);
            return null;
        }
    }

    /** Keep the denormalized chats.(conversation_title,is_title_custom) in sync for a conversation. */
    private void syncChatDenormalizedTitle(String conversationId, String title, Boolean titleCustom) {
        if (conversationId == null || conversationId.isBlank()) return;
        try {
            List<Chat> chats = chatRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
            if (chats == null || chats.isEmpty()) return;
            for (Chat ch : chats) {
                ch.setConversationTitle(title);
                ch.setTitleCustom(titleCustom);
            }
            chatRepository.saveAll(chats);
        } catch (Exception ignored) {
        }
    }

    private static String deriveConversationTitle(String userMessage) {
        if (userMessage == null) return "Conversatie";

        // 1) Normalize to one line
        String oneLine = userMessage.replaceAll("\r?\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (oneLine.isBlank()) return "Conversatie";

        // 2) Strip common filler / prompts and keep a short summary (not full message)
        // Remove leading polite openers
        oneLine = oneLine.replaceAll("^(?i)(salut|buna|bună|hey|hello|hi)[,!\\s]+", "").trim();

        // If user pasted logs, keep it generic.
        if (oneLine.length() > 220 || oneLine.contains("Exception") || oneLine.contains("Traceback") || oneLine.contains("kubectl")) {
            return "Diagnosticare / Troubleshooting";
        }

        // Take first sentence-ish chunk
        String cut = oneLine;
        int dot = indexOfFirst(cut, ".", "?", "!", ";");
        if (dot > 20) {
            cut = cut.substring(0, dot).trim();
        }

        // Reduce to first 6-8 words to avoid echoing the full prompt
        String[] words = cut.split("\\s+");
        int keep = Math.min(words.length, 8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        String title = sb.toString().trim();

        // Cap absolute length (DB column is 255, but keep UI concise)
        if (title.length() > 60) title = title.substring(0, 57) + "...";
        if (title.isBlank()) return "Conversatie";
        // Capitalize first letter
        return title.substring(0, 1).toUpperCase() + title.substring(1);
    }

    private static int indexOfFirst(String s, String... needles) {
        if (s == null) return -1;
        int best = -1;
        for (String n : needles) {
            int idx = s.indexOf(n);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private void ensureConversation(User user, String conversationId, String userMessage) {
        try {
            if (conversationId == null || conversationId.isBlank()) {
                return;
            }

            Conversation c = conversationRepository.findById(conversationId).orElse(null);
            if (c == null) {
                c = new Conversation();
                c.setConversationId(conversationId);
                c.setUser(user);
                c.setTitle(deriveConversationTitle(userMessage));
                c.setTitleCustom(false);
            }

            // Never overwrite a user-customized title during normal chat persistence.
            if (!Boolean.TRUE.equals(c.getTitleCustom())) {
                if (c.getTitle() == null || c.getTitle().isBlank() || "Conversation".equals(c.getTitle())) {
                    c.setTitle(deriveConversationTitle(userMessage));
                }
            }

            // Touch updatedAt on each message.
            c.setUpdatedAt(java.time.LocalDateTime.now());
            conversationRepository.save(c);
        } catch (Exception ignored) {
            // Keep chat flow working even if conversation metadata can't be persisted.
        }
    }

    /** Delete a whole conversation (all chats + stored contexts) by conversation id. */
    @DeleteMapping("/conversation/{conversationId}")
    @Transactional
    public ResponseEntity<?> deleteConversation(@PathVariable String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));
        }

        try {
            // Idempotency: deleting an already-deleted conversation should be OK.
            boolean existed = conversationRepository.existsById(conversationId)
                    || chatRepository.existsByConversationId(conversationId);

            // Remove chat rows and any stored contexts for the conversation.
            conversationContextRepository.deleteByConversationId(conversationId);
            chatRepository.deleteByConversationId(conversationId);

            // Flush deletions before deleting conversation row to avoid FK issues.
            try {
                conversationContextRepository.flush();
                chatRepository.flush();
            } catch (Exception ignored) {
            }

            if (conversationRepository.existsById(conversationId)) {
                conversationRepository.deleteById(conversationId);
            }

            return ResponseEntity.ok(Map.of(
                    "deleted", true,
                    "conversationId", conversationId,
                    "existed", existed
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "deleted", false,
                    "conversationId", conversationId,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage()
            ));
        }
    }

    /** List normalized conversations for a user (titles-only view). */
    @GetMapping("/conversations")
    public ResponseEntity<?> listConversations(@RequestParam Long userId) {
        Optional<User> userOpt = Optional.ofNullable(userId).flatMap(userRepository::findById);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));
        }

        User user = userOpt.get();

        // 1) Start with normalized conversations table
        List<Conversation> convRows = conversationRepository.findByUserOrderByUpdatedAtDesc(user);
        java.util.Map<String, Conversation> byId = new java.util.LinkedHashMap<>();
        for (Conversation c : convRows) {
            if (c == null || c.getConversationId() == null || c.getConversationId().isBlank()) continue;
            byId.putIfAbsent(c.getConversationId(), c);
        }

        // 2) Backfill: if there are chats with conversationId but no Conversation row (older data), include them.
        // We use the latest chat per conversationId as source for title + updatedAt.
        List<Chat> chatsDesc = chatRepository.findByUserOrderByCreatedAtDesc(user);
        java.util.Map<String, Chat> latestChatByConv = new java.util.LinkedHashMap<>();
        for (Chat c : chatsDesc) {
            String convId = c.getConversationId();
            if (convId == null || convId.isBlank()) continue;
            // chatsDesc is ordered desc => first seen is latest.
            latestChatByConv.putIfAbsent(convId, c);
        }

        for (java.util.Map.Entry<String, Chat> e : latestChatByConv.entrySet()) {
            String convId = e.getKey();
            if (byId.containsKey(convId)) continue;

            Chat latest = e.getValue();
            Conversation c = new Conversation();
            c.setConversationId(convId);
            c.setUser(user);
            c.setTitle(deriveConversationTitle(latest.getUserMessage()));
            c.setCreatedAt(latest.getCreatedAt());
            c.setUpdatedAt(latest.getCreatedAt());

            // Persist so subsequent calls are fast and title becomes editable.
            try {
                conversationRepository.save(c);
            } catch (Exception ignored) {
                // Still include it in response even if persistence fails.
            }

            byId.put(convId, c);
        }

        // 3) Return summaries sorted by updatedAt desc (best effort)
        List<Conversation> all = new java.util.ArrayList<>(byId.values());
        all.sort((a, b) -> {
            java.time.LocalDateTime au = a.getUpdatedAt();
            java.time.LocalDateTime bu = b.getUpdatedAt();
            if (au == null && bu == null) return 0;
            if (au == null) return 1;
            if (bu == null) return -1;
            return bu.compareTo(au);
        });

        List<Map<String, Object>> out = all.stream().map(c -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("conversationId", c.getConversationId());
            m.put("title", c.getTitle());
            m.put("createdAt", c.getCreatedAt());
            m.put("updatedAt", c.getUpdatedAt());
            return m;
        }).toList();

        return ResponseEntity.ok(Map.of("conversations", out, "count", out.size()));
    }

    /** Load the messages belonging to a conversation (for resume/replay). */
    @GetMapping("/conversation/{conversationId}/messages")
    public ResponseEntity<?> getConversationMessages(@PathVariable String conversationId, @RequestParam Long userId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));
        }
        Optional<User> userOpt = Optional.ofNullable(userId).flatMap(userRepository::findById);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));
        }

        // Basic ownership check: conversation must belong to user.
        Conversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null || conv.getUser() == null || !userId.equals(conv.getUser().getId())) {
            return ResponseEntity.status(404).body(Map.of("message", "Conversation not found"));
        }

        List<Chat> chats = chatRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
        // return asc for client rendering
        java.util.Collections.reverse(chats);
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "title", conv.getTitle(),
                "chats", chats,
                "count", chats.size()
        ));
    }

    /** Manually set/edit a conversation title. */
    @PatchMapping("/conversation/{conversationId}/title")
    public ResponseEntity<?> updateConversationTitle(
            @PathVariable String conversationId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body
    ) {
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));
        }
        String title = asString(body.get("title"));
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "title required"));
        }
        if (title.length() > 255) {
            title = title.substring(0, 255);
        }

        Optional<User> userOpt = Optional.ofNullable(userId).flatMap(userRepository::findById);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));
        }

        Conversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null || conv.getUser() == null || !userId.equals(conv.getUser().getId())) {
            return ResponseEntity.status(404).body(Map.of("message", "Conversation not found"));
        }

        conv.setTitle(title.trim());
        conv.setTitleCustom(true);
        conv.setUpdatedAt(java.time.LocalDateTime.now());
        conversationRepository.save(conv);
        syncChatDenormalizedTitle(conversationId, conv.getTitle(), conv.getTitleCustom());
        return ResponseEntity.ok(Map.of("conversationId", conversationId, "title", conv.getTitle()));
    }

    /**
     * Regenerate a conversation title by asking the AI server (Ollama behind the gateway).
     * This is OPTIONAL: if AI is unreachable or rejects the request, we fall back to a heuristic.
     */
    @PostMapping("/conversation/{conversationId}/title:regenerate")
    public ResponseEntity<?> regenerateConversationTitle(@PathVariable String conversationId, @RequestParam Long userId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "conversationId required"));
        }

        Optional<User> userOpt = Optional.ofNullable(userId).flatMap(userRepository::findById);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "User not found"));
        }

        Conversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null || conv.getUser() == null || !userId.equals(conv.getUser().getId())) {
            return ResponseEntity.status(404).body(Map.of("message", "Conversation not found"));
        }

        String requestId = UUID.randomUUID().toString();
        // Explicit user action: allow overriding even if title was custom.
        String finalTitle = regenerateTitleInternal(conversationId, String.valueOf(userId), requestId, true);
        return ResponseEntity.ok(Map.of("conversationId", conversationId, "title", finalTitle));
    }

    /** Internal helper used by both the public endpoint and the auto-title flow in chat(). */
    private String regenerateTitleInternal(String conversationId, String userIdValue, String requestId) {
        return regenerateTitleInternal(conversationId, userIdValue, requestId, false);
    }

    private String regenerateTitleInternal(String conversationId, String userIdValue, String requestId, boolean force) {
        try {
            // Basic guards
            if (conversationId == null || conversationId.isBlank()) {
                return null;
            }

            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv == null) {
                return null;
            }

            // Don't overwrite a user-edited title via auto-regeneration unless forced.
            if (!force && Boolean.TRUE.equals(conv.getTitleCustom())) {
                return conv.getTitle();
            }

            // Build a tiny summary prompt from the most recent N turns.
            List<Chat> chatsDesc = chatRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
            int maxTurns = 12;
            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (Chat c : chatsDesc) {
                if (used >= maxTurns) break;
                String um = c.getUserMessage();
                String ar = c.getAiResponse();
                if (um != null && !um.isBlank()) {
                    sb.append("User: ").append(um.replaceAll("\\r?\\n", " ")).append("\n");
                }
                if (ar != null && !ar.isBlank()) {
                    sb.append("Assistant: ").append(ar.replaceAll("\\r?\\n", " ")).append("\n");
                }
                used++;
            }

            String transcript = sb.toString().trim();
            String prompt = "Generate a short Romanian title (max 8 words) that summarizes this conversation. " +
                    "Return ONLY the title, no quotes, no punctuation at the end.\n\n" + transcript;

            // Ask AI server
            String aiTitle = tryForwardToAiServer(userIdValue, conversationId, prompt, requestId);
            if (aiTitle != null && aiTitle.startsWith("__AI_HTTP_ERROR__")) {
                aiTitle = null;
            }

            String finalTitle;
            if (aiTitle == null || aiTitle.isBlank()) {
                // fallback: heuristic based on first user message
                java.util.Collections.reverse(chatsDesc);
                String firstUser = null;
                for (Chat c : chatsDesc) {
                    if (c.getUserMessage() != null && !c.getUserMessage().isBlank()) {
                        firstUser = c.getUserMessage();
                        break;
                    }
                }
                finalTitle = deriveConversationTitle(firstUser);
            } else {
                finalTitle = aiTitle.replaceAll("\\r?\\n", " ").trim();
                if (finalTitle.length() > 255) finalTitle = finalTitle.substring(0, 255);
            }

            conv.setTitle(finalTitle);
            // AI-generated title is not considered custom.
            conv.setTitleCustom(false);
            conv.setUpdatedAt(java.time.LocalDateTime.now());
            conversationRepository.save(conv);
            syncChatDenormalizedTitle(conversationId, finalTitle, false);
            return finalTitle;
        } catch (Exception e) {
            return null;
        }
    }

    private String generateResponse(String message) {
        String lowerMessage = message.toLowerCase();
        
        if (lowerMessage.contains("kubernetes") || lowerMessage.contains("k8s")) {
            return "Kubernetes is an open-source container orchestration platform that automates many of the manual processes involved in deploying, managing, and scaling containerized applications.";
        } else if (lowerMessage.contains("docker")) {
            return "Docker is a containerization platform that packages your application and all its dependencies into a standardized unit called a container, making it easier to deploy across different environments.";
        } else if (lowerMessage.contains("hello") || lowerMessage.contains("hi")) {
            return "Hello! I'm Kubexplain, your AI assistant for Kubernetes and cloud infrastructure questions. How can I help you today?";
        } else if (lowerMessage.contains("help")) {
            return "I can help you with questions about:\n- Kubernetes (K8s)\n- Docker\n- Container orchestration\n- Cloud infrastructure\n- DevOps practices\n\nWhat would you like to know?";
        } else {
            return "That's an interesting question! I'm still learning about that topic. Please try asking me about Kubernetes, Docker, or cloud infrastructure. You can also type 'help' for more information.";
        }
    }
}
