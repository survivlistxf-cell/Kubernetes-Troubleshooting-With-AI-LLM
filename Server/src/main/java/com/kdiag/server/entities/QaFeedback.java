package com.kdiag.server.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persists every (question, answer) exchange so that positive feedback can later
 * trigger embedding capture and case-based retrieval hints.
 *
 * <p><b>Embedding column design note (PGVECTOR-AWARE):</b> the {@code embedding}
 * column is logically a {@code vector(768)} from the pgvector extension, but it
 * is declared as plain {@code TEXT} in the JPA mapping so that {@code ddl-auto=update}
 * works on any vanilla PostgreSQL instance — including local dev installs that
 * don't have pgvector compiled in.  Hibernate 6 has no native vector type anyway,
 * so the field is treated as a String with {@code insertable=false, updatable=false}
 * and all reads/writes go through native queries in
 * {@link com.kdiag.server.repositories.QaFeedbackRepository}.
 *
 * <p><b>Switching back to native vector(768) once pgvector is installed:</b>
 * <ol>
 *   <li>Make sure the extension is available: {@code CREATE EXTENSION IF NOT EXISTS vector;}
 *       — done automatically by {@link com.kdiag.server.PgVectorExtensionInitializer}
 *       at boot when the binary is present.</li>
 *   <li>Change the {@code @Column} below from {@code columnDefinition = "TEXT"} back to
 *       {@code columnDefinition = "vector(768)"}.</li>
 *   <li>On an existing DB, also run a one-off migration:
 *       <pre>
 *       ALTER TABLE qa_feedback
 *         ALTER COLUMN embedding TYPE vector(768) USING NULL;
 *       CREATE INDEX IF NOT EXISTS qa_feedback_embedding_hnsw
 *         ON qa_feedback USING hnsw (embedding vector_cosine_ops)
 *         WHERE embedding IS NOT NULL;
 *       </pre>
 *       (the HNSW index is what makes the {@code findSimilarByEmbeddingWithDistance}
 *       query fast — without it, ANN search degrades to a full sequential scan).</li>
 * </ol>
 *
 * <p>While the column is plain {@code TEXT}, the native queries in
 * {@code QaFeedbackRepository} that use {@code CAST(... AS vector)} or the
 * {@code <=>} cosine-distance operator will fail at runtime — the feedback
 * similarity-retrieval feature is therefore effectively disabled.  The rest of
 * the application is unaffected: regular Q&amp;A logging still works.
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
     * Embedding value, stored as text literal {@code [d0,d1,...,d767]}.
     * Never written by Hibernate; updated only via native query in {@link com.kdiag.server.repositories.QaFeedbackRepository}.
     *
     * <p>TEMPORARY FALLBACK: declared as {@code TEXT} so Hibernate's auto-DDL works on
     * any Postgres instance.  TO RE-ENABLE PGVECTOR: change
     * {@code columnDefinition = "TEXT"} → {@code columnDefinition = "vector(768)"}
     * and run the ALTER TABLE migration described in the class javadoc above.
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
