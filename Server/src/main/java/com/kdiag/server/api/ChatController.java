package com.kdiag.server.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.kdiag.server.ai.AiEngine;
import com.kdiag.server.ai.AiEngine.AiResult;
import com.kdiag.server.ai.stream.StreamChunk;
import com.kdiag.server.ai.feedback.FeedbackRetrievalService;
import com.kdiag.server.ai.history.HistoryService;
import com.kdiag.server.ai.history.HistoryService.HistoryEntry;
import com.kdiag.server.protocol.KdiagModels;
import com.kdiag.server.protocol.KdiagModels.KdiagChatRequest;
import com.kdiag.server.protocol.KdiagModels.KdiagChatResponse;

@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final AiEngine aiEngine;
    private final HistoryService historyService;
    private final com.kdiag.server.repositories.ProblemResolutionRepository resolutionRepository;
    private final FeedbackRetrievalService feedbackRetrievalService;
    private final ObjectMapper objectMapper;

    public ChatController(AiEngine aiEngine, HistoryService historyService,
                          com.kdiag.server.repositories.ProblemResolutionRepository resolutionRepository,
                          FeedbackRetrievalService feedbackRetrievalService,
                          ObjectMapper objectMapper) {
        this.aiEngine = aiEngine;
        this.historyService = historyService;
        this.resolutionRepository = resolutionRepository;
        this.feedbackRetrievalService = feedbackRetrievalService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/history/{conversationId}/feedback")
    public void setFeedback(@PathVariable String conversationId,
                            @RequestBody java.util.Map<String, Integer> payload) {
        Integer score = payload.get("score");
        if (score == null) return;

        // 1. Existing ProblemResolution feedback update (dynamic-search path)
        resolutionRepository.findTopByConversationIdOrderByCreatedAtDesc(conversationId).ifPresent(res -> {
            res.setFeedback(score);
            resolutionRepository.save(res);
            logger.info("Feedback set for conv {}: {}", conversationId, score);
        });

        // 2. CBR feedback routing — record in qa_feedback and trigger embedding on like
        try {
            if (score > 0) {
                feedbackRetrievalService.onPositiveFeedback(conversationId);
            } else if (score < 0) {
                feedbackRetrievalService.onNegativeFeedback(conversationId);
            }
        } catch (Exception e) {
            // Feedback recording must never break the HTTP response to the client
            logger.warn("CBR feedback routing failed for conv {}: {}", conversationId, e.getMessage());
        }
    }

    @GetMapping("/history/list")
    public Set<String> listHistories() {
        return historyService.getActiveIds();
    }

    @GetMapping("/history/{id}")
    public List<HistoryEntry> getHistory(@PathVariable String id) {
        return historyService.getHistory(id);
    }

    @PostMapping("/chat")
    public KdiagChatResponse chat(@Valid @RequestBody KdiagChatRequest req) {
        logger.info("Incoming Request: conv_id={} protocol={}", req.getConversation_id(), req.getProtocol_version());
        if (req.getMessage() != null) {
            logger.info("Message: {}", req.getMessage().getText());
        }
        logger.info("Received request: proto={} conv={} hasArtifacts={}", req.getProtocol_version(),
                req.getConversation_id(), (req.getArtifacts() != null && !req.getArtifacts().isEmpty()));
        if (!"kdiag/1.0".equalsIgnoreCase(req.getProtocol_version())) {
            throw new IllegalArgumentException("Unsupported protocol_version: " + req.getProtocol_version());
        }

        String conversationId = req.getConversation_id();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        // {
        // "protocol_version": "kdiag/1.0",
        // "conversation_id": "conv-123456",
        // "message": {
        // "role": "user",
        // "text": "De ce nu pornește podul meu de backend?"
        // },
        // "artifacts": [
        // {
        // "type": "pod_describe",
        // "target": "pod/elearning/backend-6f745b6fb5-jfsjv",
        // "content": "Name: backend-6f... \nStatus: Pending... \nEvents: ...",
        // "level": 1
        // },
        // {
        // "type": "pod_logs",
        // "target": "pod/elearning/backend-6f745b6fb5-jfsjv",
        // "content": "[ERROR] Could not connect to database at 10.96.0.1...",
        // "level": 2
        // }
        // ]
        // }

        String userText = req.getMessage() == null ? null : req.getMessage().getText();
        List<KdiagModels.Artifact> artifacts = req.getArtifacts();
        boolean recordExchange = !Boolean.FALSE.equals(req.getRecordExchange());
        // When ephemeral=true (e.g. title generation from backend/ChatService), pass null
        // conversationId so nothing is persisted to history.
        boolean isEphemeral = Boolean.TRUE.equals(req.getEphemeral());

        AiResult result = isEphemeral
            ? aiEngine.generateTitle(userText)
            : aiEngine.solve(conversationId, userText, artifacts, recordExchange);

        KdiagChatResponse resp = new KdiagChatResponse();
        resp.setProtocol_version("kdiag/1.0");
        resp.setConversation_id(conversationId);
        resp.setAssistant_message(new KdiagModels.AssistantMessage(result.getAssistantText()));
        resp.setActions_requested(mapActions(result));
        return resp;
    }

    // -------------------------------------------------------------------------
    // SSE streaming endpoint
    // -------------------------------------------------------------------------

    /**
     * Streams the assistant response as Server-Sent Events.
     *
     * <p>Event types:
     * <ul>
     *   <li>{@code meta}  – first event; JSON payload {@code {conversationId, protocolVersion}}</li>
     *   <li>{@code chunk} – one token/fragment of the assistant reply, JSON-wrapped as
     *       {@code {"text":"..."}} so leading/trailing whitespace survives SSE transport
     *       (the W3C SSE spec strips a single leading space from raw {@code data:} payloads,
     *       which silently ate the leading spaces on Ollama tokens like " of", " the", …)</li>
     *   <li>{@code done}  – signals end of stream; empty data</li>
     *   <li>{@code error} – emitted instead of done on failure</li>
     * </ul>
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody KdiagChatRequest req) {
        if (!"kdiag/1.0".equalsIgnoreCase(req.getProtocol_version())) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("Unsupported protocol_version: " + req.getProtocol_version())
                    .build());
        }

        String conversationId = req.getConversation_id();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        final String convId = conversationId;

        String userText = req.getMessage() == null ? null : req.getMessage().getText();
        List<KdiagModels.Artifact> artifacts = req.getArtifacts();
        boolean recordExchange = !Boolean.FALSE.equals(req.getRecordExchange());
        // When ephemeral=true (e.g. title generation from backend/ChatService), pass null
        // conversationId so nothing is persisted to history.
        boolean isEphemeral = Boolean.TRUE.equals(req.getEphemeral());

        logger.info("SSE stream request: conv={} text={}", convId,
                userText == null ? "<null>" : userText.substring(0, Math.min(80, userText.length())));

        ServerSentEvent<String> metaEvent = ServerSentEvent.<String>builder()
                .event("meta")
                .data("{\"conversationId\":\"" + convId + "\",\"protocolVersion\":\"kdiag/1.0\"}")
                .build();

        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                .event("done")
                .data("")
                .build();

        final JsonStringEncoder jsonEnc = JsonStringEncoder.getInstance();

        // Short-circuit for ephemeral requests (title generation): skip the full streaming
        // pipeline entirely — no RAG, CBR, history, embeddings. Return as a single chunk.
        if (isEphemeral) {
            AiResult titleResult = aiEngine.generateTitle(userText);
            String text = titleResult.getAssistantText() != null ? titleResult.getAssistantText() : "";
            String titlePayload = "{\"text\":\"" + new String(jsonEnc.quoteAsString(text)) + "\"}";
            return Flux.concat(
                    Flux.just(metaEvent),
                    Flux.just(ServerSentEvent.<String>builder().event("chunk").data(titlePayload).build()),
                    Flux.just(doneEvent));
        }

        Flux<ServerSentEvent<String>> tokenStream = aiEngine.solveStream(convId, userText, artifacts, recordExchange)
                .flatMap(chunk -> {
                    try {
                        if (chunk.type() == StreamChunk.Type.TOKEN) {
                            // JSON-wrap so whitespace-only or leading-space tokens survive the SSE
                            // round-trip through the backend proxy.  See javadoc above.
                            String tokenText = chunk.text() != null ? chunk.text() : "";
                            String payload = "{\"text\":\"" + new String(jsonEnc.quoteAsString(tokenText)) + "\"}";
                            return Flux.just(ServerSentEvent.<String>builder()
                                    .event("chunk")
                                    .data(payload)
                                    .build());
                        } else if (chunk.type() == StreamChunk.Type.STATUS) {
                            String payload = objectMapper.writeValueAsString(
                                    Map.of("code", chunk.code(), "label", chunk.label()));
                            return Flux.just(ServerSentEvent.<String>builder()
                                    .event("status")
                                    .data(payload)
                                    .build());
                        }
                        return Flux.empty();
                    } catch (Exception e) {
                        logger.error("Failed to serialize SSE chunk for conversation {}", convId, e);
                        return Flux.empty();
                    }
                })
                .onErrorResume(e -> {
                    logger.error("SSE stream error for conversation {}", convId, e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("Streaming failed: " + e.getMessage())
                            .build());
                });

        return Flux.concat(Flux.just(metaEvent), tokenStream, Flux.just(doneEvent));
    }

    private List<KdiagModels.ActionRequested> mapActions(AiResult result) {
        if (result.getActions() == null || result.getActions().isEmpty()) {
            return null;
        }

        return result.getActions().stream().<KdiagModels.ActionRequested>map(a -> {
            KdiagModels.ActionRequested ar = new KdiagModels.ActionRequested();
            ar.setId(a.getId());
            ar.setType(a.getType());
            ar.setCollector(a.getCollector());
            ar.setSpec(a.getSpec());
            ar.setWhy(a.getWhy());
            return ar;
        }).toList();
    }
}
