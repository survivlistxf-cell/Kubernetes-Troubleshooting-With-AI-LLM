package com.example.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Service
public class AiForwardingService {

    private static final Logger logger = LoggerFactory.getLogger(AiForwardingService.class);
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 65_000;

    public record ForwardResult(String text, String conversationId) {
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.server.base-url:http://localhost:8090}")
    private String aiServerBaseUrl;

    public AiForwardingService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Connect timeout keeps the request from hanging while establishing the TCP connection.
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        // Read timeout is slightly above Ollama's own timeout so the backend can surface a clear failure.
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    public String getAiServerBaseUrl() {
        return aiServerBaseUrl;
    }

    /**
     * Check AI server health. Returns the health response map, or throws on
     * failure.
     */
    public Map<?, ?> checkHealth() {
        return restTemplate.getForObject(aiServerBaseUrl + "/health", Map.class);
    }

    /**
     * Forward a user message to the AI server.
     *
     * @return AI response text, or "__AI_HTTP_ERROR__<code>" on 4xx/5xx, or null if
     *         unreachable.
     */
    public ForwardResult forward(String userIdValue, String conversationId, String userMessage, Object attachmentsObj,
            String requestId) {
        logger.info("[{}] Backend Forwarding: user={} conv={}", requestId, userIdValue, conversationId);
        try {
            List<Map<String, Object>> artifacts = processIncomingAttachments(attachmentsObj, requestId);

            if (conversationId == null || conversationId.isBlank()) {
                conversationId = UUID.randomUUID().toString();
            }

            String safeText = userMessage == null ? "" : userMessage.trim();
            if (safeText.isBlank()) {
                return null;
            }

            // Aici cream mesajul care va fi trimis catre serverul AI
            // Folosind protocolul kdiag/1.0
            Map<String, Object> messageObj = Map.of("role", "user", "text", safeText);

            Map<String, Object> kdiag = (artifacts == null || artifacts.isEmpty())
                    ? Map.of("protocol_version", "kdiag/1.0", "conversation_id", conversationId, "message", messageObj)
                    : Map.of("protocol_version", "kdiag/1.0", "conversation_id", conversationId, "message", messageObj,
                            "artifacts", artifacts);

            // Aici logam mesajul care va fi trimis catre serverul AI
            logOutgoingPayload(kdiag, requestId);
            // ===FARA ATASAMENTE===
            // {
            // "protocol_version": "kdiag/1.0",
            // "conversation_id": "550e8400-e29b-41d4-a716-446655440000",
            // "message": {
            // "role": "user",
            // "text": "Salut! Poți să mă ajuți cu o comandă de Kubernetes?"
            // }
            // }
            // ===CU ATASAMENTE===
            // {
            // "protocol_version": "kdiag/1.0",
            // "conversation_id": "550e8400-e29b-41d4-a716-446655440000",
            // "message":
            // {
            // "role": "user",
            // "text": "Salut! Poți să mă ajuți cu o comandă de Kubernetes?"
            // },
            // "artifacts":
            // [
            // {
            // "type": "file",
            // "target": "attachment.txt",
            // "content": "Hello, this is a test file.",
            // "mime_type": "text/plain"
            // }
            // ]
            // }

            // creeaza header-ul pentru request
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // creeaza entity-ul pentru request
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(kdiag, headers);

            @SuppressWarnings("rawtypes") // suprima avertismentul ca Map este generic
            ResponseEntity<Map> exchange = restTemplate.postForEntity(aiServerBaseUrl + "/v1/chat", entity, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = exchange.getBody();
            if (resp == null)
                return null;

            String aiConversationId = String.valueOf(resp.get("conversation_id"));
            if (aiConversationId != null && !"null".equals(aiConversationId)) {
                logger.info("[{}] AI Server returned conversationId: {}", requestId, aiConversationId);
                conversationId = aiConversationId;
            }

            return new ForwardResult(extractAssistantText(resp), conversationId);
        } catch (HttpStatusCodeException e) {
            logger.error("[{}] AI Forward error {}: {}", requestId, e.getStatusCode(), e.getResponseBodyAsString());
            return new ForwardResult("ERR:" + e.getStatusCode(), conversationId);
        } catch (ResourceAccessException e) {
            logger.error("[{}] AI server unreachable", requestId, e);
            return new ForwardResult("ERR:UNREACHABLE", conversationId);
        } catch (RestClientException e) {
            logger.error("[{}] AI server forward failed", requestId, e);
            return new ForwardResult("ERR:UNREACHABLE", conversationId);
        } catch (Exception e) {
            logger.error("[{}] AI Forward exception", requestId, e);
            return new ForwardResult("ERR:INTERNAL", conversationId);
        }
    }

    public boolean isAiHttpError(String response) {
        return response != null && response.startsWith("__AI_HTTP_ERROR__");
    }

    public void submitFeedback(String conversationId, Integer score) {
        try {
            String url = aiServerBaseUrl + "/v1/history/" + conversationId + "/feedback";
            restTemplate.postForEntity(url, Map.of("score", score), Void.class);
            logger.info("Forwarded feedback for {} -> {}", conversationId, score);
        } catch (Exception e) {
            logger.error("Failed to forward feedback for {}", conversationId, e);
        }
    }

    public String extractHttpErrorCode(String response) {
        return response.substring("__AI_HTTP_ERROR__".length());
    }

    // Handle-uieste raspunsul LLM-ului
    private String extractAssistantText(Map<String, Object> resp) {
        // Extragem direct obiectul "assistant_message"
        if (resp.get("assistant_message") instanceof Map<?, ?> msg) {
            Object text = msg.get("text");
            if (text != null) {
                return String.valueOf(text);
            }
        }
        return null;
    }

    private List<Map<String, Object>> processIncomingAttachments(Object attachmentsObj, String requestId) {
        if (attachmentsObj != null) {
            logger.info("[{}] processIncomingAttachments: type={}", requestId, attachmentsObj.getClass().getName());
        }
        if (!(attachmentsObj instanceof List<?> list) || list.isEmpty())
            return List.of();

        logger.info("[{}] processIncomingAttachments: found {} items", requestId, list.size());
        try {
            List<Map<String, Object>> artifacts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> artifact = new LinkedHashMap<>();

                    // Map generic attachment fields to AI-expected kdiag metadata
                    // toString(obiect, valoare implicita)
                    // Daca valoarea cheii din map.get() e null, returneaza valoarea implicita
                    // Daca valoarea cheii din map.get() e diferita de String, o transforma in
                    // String
                    String name = Objects.toString(map.get("name"), "attachment");
                    String type = Objects.toString(map.get("type"), "text/plain");
                    String content = Objects.toString(map.get("content"), "");

                    // Enrich content with full kubectl commands if possible
                    content = enrichArtifactContent(content);

                    // Asa se asteapta serverul sa primeasca datele
                    // folosind protocolul kdiag/1.0
                    // daca nu le am trimite asa, ar ignora datele
                    artifact.put("type", "file");
                    artifact.put("target", name);
                    artifact.put("content", content);
                    // Pass along mime type if the AI server can use it
                    artifact.put("mime_type", type);

                    artifacts.add(artifact);
                }
            }
            return artifacts;
        } catch (Exception e) {
            logger.error("[{}] Failed to process incoming attachments", requestId, e);
            return List.of();
        }
    }

    private String enrichArtifactContent(String content) {
        if (content == null || content.isBlank())
            return content;

        // Pattern for Pod: === POD DETAILS: namespace/name ===
        Pattern podPattern = Pattern.compile("=== POD DETAILS: ([^/]+)/([^\\s]+) ===");
        Matcher podMatcher = podPattern.matcher(content);
        if (podMatcher.find()) {
            String ns = podMatcher.group(1);
            String name = podMatcher.group(2);

            content = content.replace("--- kubectl describe ---",
                    "--- kubectl describe pod " + name + " -n " + ns + " ---\n# kubectl describe pod " + name + " -n "
                            + ns);
            content = content.replace("--- kubectl get pod -o json ---",
                    "--- kubectl get pod " + name + " -n " + ns + " -o json ---\n# kubectl get pod " + name + " -n "
                            + ns + " -o json");
            content = content.replace("--- Logs (tail 200) ---",
                    "--- kubectl logs " + name + " -n " + ns + " --tail=200 ---\n# kubectl logs " + name + " -n " + ns
                            + " --tail=200");
            content = content.replace("--- Events ---",
                    "--- kubectl get events -n " + ns + " --field-selector involvedObject.name=" + name
                            + " ---\n# kubectl get events -n " + ns + " --field-selector involvedObject.name=" + name);
        }

        // Pattern for Node: === NODE DETAILS: name ===
        Pattern nodePattern = Pattern.compile("=== NODE DETAILS: ([^\\s]+) ===");
        Matcher nodeMatcher = nodePattern.matcher(content);
        if (nodeMatcher.find()) {
            String name = nodeMatcher.group(1);

            content = content.replace("--- kubectl describe ---",
                    "--- kubectl describe node " + name + " ---\n# kubectl describe node " + name);
            content = content.replace("--- kubectl get node -o json ---",
                    "--- kubectl get node " + name + " -o json ---\n# kubectl get node " + name + " -o json");
            content = content.replace("--- Events ---",
                    "--- kubectl get events --field-selector involvedObject.name=" + name
                            + " ---\n# kubectl get events --field-selector involvedObject.name=" + name);
        }

        return content;
    }

    private void logOutgoingPayload(Map<String, Object> kdiag, String requestId) {
        try {
            String preview = objectMapper.writeValueAsString(trimLargeStrings(kdiag, 500));
            logger.debug("[{}] Forwarding to AI: {}", requestId, preview);
        } catch (Exception ignored) {
        }
    }

    private static Map<String, Object> trimLargeStrings(Map<String, Object> input, int maxLen) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                out.put(e.getKey(), s.length() > maxLen ? s.substring(0, maxLen) + "…" : s);
            } else if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                out.put(e.getKey(), trimLargeStrings(cast, maxLen));
            } else if (v instanceof List<?> list) {
                out.put(e.getKey(), "[" + list.size() + " item(s)]");
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
