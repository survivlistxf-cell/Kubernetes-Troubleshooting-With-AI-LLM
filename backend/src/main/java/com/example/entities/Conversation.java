package com.example.entities;

import jakarta.persistence.*;

@Entity
@Table(name = "conversations", indexes = {
        @Index(name = "idx_conv_user_id", columnList = "user_id"),
        @Index(name = "idx_conv_updated_at", columnList = "updated_at")
})
public class Conversation {

    /**
     * We use the client-generated conversationId (UUID string) as the primary key.
     * This keeps the existing frontend id stable and avoids migrations from numeric
     * ids.
     */
    @Id
    @Column(name = "conversation_id", length = 100)
    private String conversationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "title", length = 255)
    private String title;

    /**
     * When true, the title was explicitly edited by the user and should not be
     * overwritten
     * by the auto-title heuristics/AI regeneration.
     */
    @Column(name = "is_title_custom")
    private Boolean titleCustom;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (this.createdAt == null)
            this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = java.time.LocalDateTime.now();
    }

    public Conversation() {
    }

    public Conversation(String conversationId, User user, String title, java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt) {
        this.conversationId = conversationId;
        this.user = user;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Boolean getTitleCustom() {
        return titleCustom;
    }

    public void setTitleCustom(Boolean titleCustom) {
        this.titleCustom = titleCustom;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
