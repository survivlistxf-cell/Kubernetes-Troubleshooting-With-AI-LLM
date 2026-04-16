package com.example.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chats")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String userMessage;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String aiResponse;

    /**
     * Client-side conversation id (UUID string) to group related chat exchanges.
     */
    @Column(name = "conversation_id", length = 100)
    private String conversationId;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @Column
    private Integer feedback = 0; // 0 = none, 1 = like, -1 = dislike

    @PrePersist
    protected void onCreate() {
        this.createdAt = java.time.LocalDateTime.now();
    }

    // Getters
    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getAiResponse() {
        return aiResponse;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Integer getFeedback() {
        return feedback;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public void setAiResponse(String aiResponse) {
        this.aiResponse = aiResponse;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void setFeedback(Integer feedback) {
        this.feedback = feedback;
    }

}
