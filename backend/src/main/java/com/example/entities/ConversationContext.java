package com.example.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "conversation_context",
    indexes = {
        @Index(name = "idx_ctx_conversation_id", columnList = "conversation_id"),
        @Index(name = "idx_ctx_user_id", columnList = "user_id"),
        @Index(name = "idx_ctx_created_at", columnList = "created_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Client-side conversation id (UUID string) to group related contexts.
     */
    @Column(name = "conversation_id", nullable = false, length = 100)
    private String conversationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * 0 = summary, 1 = evidence, 2 = extended dump
     */
    @Column(name = "level", nullable = false)
    private Integer level;

    @Column(name = "source", nullable = false, length = 100)
    private String source;

    @Column(name = "payload_json", columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = java.time.LocalDateTime.now();
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public User getUser() {
        return user;
    }

    public Integer getLevel() {
        return level;
    }

    public String getSource() {
        return source;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
