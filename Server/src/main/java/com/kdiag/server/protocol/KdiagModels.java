package com.kdiag.server.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Minimal kdiag/1.0 protocol models (MVP).
 *
 * Notes:
 * - We keep some fields flexible as Map to avoid over-fitting early.
 * - Client can send big artifacts; enforce size limits at server/config later.
 */
public final class KdiagModels {

    private KdiagModels() {
    }

    public static class KdiagChatRequest {
        @NotBlank
        @JsonProperty("protocol_version")
        private String protocol_version;

        @JsonProperty("conversation_id")
        @Size(max = 100)
        private String conversation_id;

        @NotNull
        @Valid
        private Message message;

        @Valid
        private Context context; // Spune despre CE obiecte vorbim (identitate)

        @Valid
        @Size(max = 5)
        private List<Artifact> artifacts; // Spune CE dovezi avem despre ele (date brute)

        private Map<String, Object> preferences;

        @JsonProperty("protocol_version")
        public String getProtocol_version() {
            return protocol_version;
        }

                private static final int MAX_CONVERSATION_ID_CHARS = 100;
                private static final int MAX_MESSAGE_TEXT_CHARS = 4000;
                private static final int MAX_ARTIFACTS_PER_REQUEST = 5;
                private static final int MAX_ARTIFACT_TARGET_CHARS = 255;
                private static final int MAX_ARTIFACT_CONTENT_CHARS = 10000;
        @JsonProperty("protocol_version")
        public void setProtocol_version(String protocol_version) {
            this.protocol_version = protocol_version;
        }

        @JsonProperty("conversation_id")
        public String getConversation_id() {
            return conversation_id;
        }

        @JsonProperty("conversation_id")
        public void setConversation_id(String conversation_id) {
            this.conversation_id = conversation_id;
        }

        public Message getMessage() {
            return message;
        }
        public void setMessage(Message message) {
            this.message = message;
        }

        public Context getContext() { 
            return context;
        }

        public void setContext(Context context) {
            this.context = context;
        }

        public List<Artifact> getArtifacts() {
            return artifacts;
        }

        public void setArtifacts(List<Artifact> artifacts) {
            this.artifacts = artifacts;
        }

        public Map<String, Object> getPreferences() {
            return preferences;
        }

        public void setPreferences(Map<String, Object> preferences) {
            this.preferences = preferences;
        }
    }

    public static class KdiagChatResponse {
        @JsonProperty("protocol_version")
        private String protocol_version;
        
        @JsonProperty("conversation_id")
        private String conversation_id;
        
        @JsonProperty("assistant_message")
        private AssistantMessage assistant_message;
        
        @JsonProperty("actions_requested")
        private List<ActionRequested> actions_requested;

        @JsonProperty("protocol_version")
        public String getProtocol_version() {
            return protocol_version;
        }

        @JsonProperty("protocol_version")
        public void setProtocol_version(String protocol_version) {
            this.protocol_version = protocol_version;
        }

        @JsonProperty("conversation_id")
        public String getConversation_id() {
            return conversation_id;
        }

        @JsonProperty("conversation_id")
        public void setConversation_id(String conversation_id) {
            this.conversation_id = conversation_id;
        }

        public AssistantMessage getAssistant_message() {
            return assistant_message;
        }

        public void setAssistant_message(AssistantMessage assistant_message) {
            this.assistant_message = assistant_message;
        }

        public List<ActionRequested> getActions_requested() {
            return actions_requested;
        }

        public void setActions_requested(List<ActionRequested> actions_requested) {
            this.actions_requested = actions_requested;
        }
    }

    public static class Message {
        @NotBlank
        private String role;

        @NotBlank
        @Size(max = 4000)
        private String text;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    public static class AssistantMessage {
        private String role = "assistant";
        private String text;
        private OffsetDateTime created_at = OffsetDateTime.now();

        public AssistantMessage() {
        }

        public AssistantMessage(String text) {
            this.text = text;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public OffsetDateTime getCreated_at() {
            return created_at;
        }

        public void setCreated_at(OffsetDateTime created_at) {
            this.created_at = created_at;
        }
    }

    public static class Context {
        private Map<String, Object> cluster;

        @Valid
        private List<TargetRef> targets;

        public Map<String, Object> getCluster() {
            return cluster;
        }

        public void setCluster(Map<String, Object> cluster) {
            this.cluster = cluster;
        }

        public List<TargetRef> getTargets() {
            return targets;
        }

        public void setTargets(List<TargetRef> targets) {
            this.targets = targets;
        }
    }

    public static class TargetRef {
        private String kind;
        private String namespace;
        private String name;

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Artifact {
        @NotBlank
        private String type;

        /**
         * Mostly informational (e.g., "pod/ns/name").
         */
        @Size(max = 255)
        private String target;

        private String container;

        /**
         * Raw content (logs/describe/events). Keep it as string for MVP.
         */
        @Size(max = 10000)
        private String content;

        /**
         * Optional severity/importance level (0/1/2).
         */
        private Integer level;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getContainer() {
            return container;
        }

        public void setContainer(String container) {
            this.container = container;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Integer getLevel() {
            return level;
        }

        public void setLevel(Integer level) {
            this.level = level;
        }
    }

    public static class ActionRequested {
        private String id;
        private String type;
        private String collector;
        private Map<String, Object> spec;
        private String why;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCollector() {
            return collector;
        }

        public void setCollector(String collector) {
            this.collector = collector;
        }

        public Map<String, Object> getSpec() {
            return spec;
        }

        public void setSpec(Map<String, Object> spec) {
            this.spec = spec;
        }

        public String getWhy() {
            return why;
        }

        public void setWhy(String why) {
            this.why = why;
        }
    }
}
