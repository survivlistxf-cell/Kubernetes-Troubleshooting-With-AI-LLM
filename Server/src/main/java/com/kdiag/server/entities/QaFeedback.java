package com.kdiag.server.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persists every (question, answer) exchange so that positive feedback can later
 * trigger embedding capture and case-based retrieval hints.
 *
 * <p><b>Embedding column design note (PGVECTOR):</b> the {@code embedding} column
 * is a native {@code vector(768)} (pgvector extension). Hibernate 6 has no vector
 * type, so the field is mapped as a String with {@code insertable=false,
 * updatable=false} and all reads/writes go through native queries in
 * {@link com.kdiag.server.repositories.QaFeedbackRepository}. The column type and
 * the HNSW cosine index are created by the versioned migration
 * {@code db_migrations/2026-05-12_qa_feedback_pgvector.sql}; the extension itself
 * ({@code CREATE EXTENSION IF NOT EXISTS vector}) is ensured at database init
 * (init.sql / postgres-init-configmap) and both runtime images
 * ({@code pgvector/pgvector:pg16}) ship it. The HNSW index is what makes the
 * {@code findSimilarByEmbeddingWithDistance} query fast — without it, ANN search
 * degrades to a full sequential scan.
 */
@Entity
@Table(name = "qa_feedback",
       indexes = {
           @Index(name = "qa_feedback_conversation_id", columnList = "conversation_id"),
           @Index(name = "qa_feedback_feedback_pos",    columnList = "feedback")
       })
public class QaFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", length = 100, nullable = false)
    private String conversationId;

    @Column(name = "user_question", columnDefinition = "TEXT", nullable = false)
    private String userQuestion;

    @Column(name = "ai_response", columnDefinition = "TEXT", nullable = false)
    private String aiResponse;

    /**
     * pgvector {@code vector(768)} column (see class javadoc). Read as the text
     * literal {@code [d0,d1,...,d767]}; never written by Hibernate — updated only
     * via native query in {@link com.kdiag.server.repositories.QaFeedbackRepository}.
     */
    @Column(columnDefinition = "vector(768)", insertable = false, updatable = false)
    private String embedding;

    /** 0 = neutral, 1 = liked, -1 = disliked */
    @Column(nullable = false)
    private Integer feedback = 0;

    @Column(name = "source_urls", columnDefinition = "TEXT")
    private String sourceUrls;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public QaFeedback() {
    }

    public QaFeedback(String conversationId, String userQuestion, String aiResponse, String sourceUrls) {
        this.conversationId = conversationId;
        this.userQuestion   = userQuestion;
        this.aiResponse     = aiResponse;
        this.sourceUrls     = sourceUrls;
        this.feedback       = 0;
        this.createdAt      = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // -------------------------------------------------------------------------
    // Getters & setters
    // -------------------------------------------------------------------------

    public Long getId() { return id; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getUserQuestion() { return userQuestion; }
    public void setUserQuestion(String userQuestion) { this.userQuestion = userQuestion; }

    public String getAiResponse() { return aiResponse; }
    public void setAiResponse(String aiResponse) { this.aiResponse = aiResponse; }

    /** May be null if the embedding job has not run yet or failed. */
    public String getEmbedding() { return embedding; }

    public Integer getFeedback() { return feedback; }
    public void setFeedback(Integer feedback) { this.feedback = feedback; }

    public String getSourceUrls() { return sourceUrls; }
    public void setSourceUrls(String sourceUrls) { this.sourceUrls = sourceUrls; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
