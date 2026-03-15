package com.kdiag.server.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kdiag.server.ai.AiEngine;
import com.kdiag.server.ai.AiEngine.AiResult;
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

    public ChatController(AiEngine aiEngine, HistoryService historyService) {
        this.aiEngine = aiEngine;
        this.historyService = historyService;
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
        logger.info("Received request: proto={} conv={} hasArtifacts={}", req.getProtocol_version(), req.getConversation_id(), (req.getArtifacts() != null && !req.getArtifacts().isEmpty()));
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

        AiResult result = aiEngine.solve(conversationId, userText, artifacts);

        KdiagChatResponse resp = new KdiagChatResponse();
        resp.setProtocol_version("kdiag/1.0");
        resp.setConversation_id(conversationId);
        resp.setAssistant_message(new KdiagModels.AssistantMessage(result.getAssistantText()));
        resp.setActions_requested(mapActions(result));
        return resp;
    }

    private List<KdiagModels.ActionRequested> mapActions(AiResult result) {
        if (result.getActions() == null || result.getActions().isEmpty()) {
            return null;
        }

        return result.getActions().stream()
            .<KdiagModels.ActionRequested>map(a -> {
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
