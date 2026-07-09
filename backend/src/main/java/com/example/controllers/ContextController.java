package com.example.controllers;

import com.example.entities.ConversationContext;
import com.example.entities.User;
import com.example.repositories.ConversationContextRepository;
import com.example.repositories.UserRepository;
import com.example.utils.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class ContextController {

    @Autowired
    private ConversationContextRepository conversationContextRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Ingest structured diagnostic context (kdiag/1.0).
     * Expected fields (minimum): protocol_version, conversation_id, artifacts[].
     */
    @PostMapping("/context")
    public ResponseEntity<?> ingest(@RequestBody Object request) {
        try {
            String json = objectMapper.writeValueAsString(request);

            String conversationId = null;
            Integer level = 0;
            String source = "unknown";
            String userIdValue = null;

            if (request instanceof Map<?, ?> map) {
                conversationId = Utils.asString(map.get("conversation_id"));
                source = Utils.asString(map.get("source"));
                if (source == null)
                    source = "unknown";

                Object uid = map.get("user_id");
                if (uid == null)
                    uid = map.get("userId");
                userIdValue = Utils.asString(uid);
                // Token uid wins over the client-supplied value (IDOR prevention).
                Long resolved = com.example.security.CurrentUser.resolve(userIdValue);
                userIdValue = resolved != null ? String.valueOf(resolved) : null;

                // Try to infer level from first artifact.level
                Object artifacts = map.get("artifacts");
                if (artifacts instanceof java.util.List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map<?, ?> firstMap) {
                        Object lvl = firstMap.get("level");
                        if (lvl instanceof Number n) {
                            level = n.intValue();
                        } else if (lvl != null) {
                            try {
                                level = Integer.parseInt(String.valueOf(lvl));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }

            if (conversationId == null || conversationId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Missing conversation_id"));
            }

            ConversationContext ctx = new ConversationContext();
            ctx.setConversationId(conversationId);
            ctx.setLevel(level);
            ctx.setSource(source);
            ctx.setPayloadJson(json);

            if (userIdValue != null) {
                try {
                    Long userId = Long.parseLong(userIdValue);
                    Optional<User> userOpt = userRepository.findById(userId);
                    userOpt.ifPresent(ctx::setUser);
                } catch (NumberFormatException ignored) {
                }
            }

            ConversationContext saved = conversationContextRepository.save(ctx);

            return ResponseEntity.ok(Map.of(
                    "message", "Context received",
                    "contextId", saved.getId(),
                    "conversationId", saved.getConversationId(),
                    "level", saved.getLevel(),
                    "source", saved.getSource()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Failed to ingest context",
                    "error", e.getMessage()));
        }
    }
}
