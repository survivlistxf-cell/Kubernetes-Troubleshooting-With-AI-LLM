package com.kdiag.server.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kdiag.server.ai.AiEngine;
import com.kdiag.server.ai.AiEngine.AiResult;
import com.kdiag.server.protocol.KdiagModels;
import com.kdiag.server.protocol.KdiagModels.KdiagChatRequest;
import com.kdiag.server.protocol.KdiagModels.KdiagChatResponse;

@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private final AiEngine aiEngine;

    public ChatController(AiEngine aiEngine) {
        this.aiEngine = aiEngine;
    }

    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public KdiagChatResponse chat(@Valid @RequestBody KdiagChatRequest request) {
        // Validate protocol quickly
        if (!"kdiag/1.0".equalsIgnoreCase(request.getProtocol_version())) {
            throw new IllegalArgumentException("Unsupported protocol_version: " + request.getProtocol_version());
        }

        String conversationId = request.getConversation_id();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        String userText = request.getMessage() == null ? null : request.getMessage().getText();
        List<KdiagModels.Artifact> artifacts = request.getArtifacts();

        AiResult result = aiEngine.solve(userText, artifacts);

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

        return result.getActions().stream().map(a -> {
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
