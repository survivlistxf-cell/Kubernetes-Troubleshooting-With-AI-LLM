package com.kdiag.server.repositories;

import com.kdiag.server.entities.QaFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface QaFeedbackRepository extends JpaRepository<QaFeedback, Long> {

    // -------------------------------------------------------------------------
    // Derived queries
    // -------------------------------------------------------------------------

    Optional<QaFeedback> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);

    long countByFeedback(int feedback);

    // -------------------------------------------------------------------------
    // Native write operations
    // (embedding column is vector(768); we pass it as the pgvector text literal)
    // -------------------------------------------------------------------------

    /**
     * Sets both the embedding vector and the feedback score in a single statement.
     * The {@code :vec} parameter must be a pgvector text literal, e.g. {@code "[0.1,-0.2,...]"}.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE qa_feedback SET embedding = CAST(:vec AS vector), feedback = :fb WHERE id = :id",
           nativeQuery = true)
    int updateEmbeddingAndFeedback(@Param("id") Long id,
                                   @Param("vec") String vec,
                                   @Param("fb") int fb);

    /**
     * Updates only the feedback score, leaving the embedding unchanged.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE qa_feedback SET feedback = :fb WHERE id = :id",
           nativeQuery = true)
    int updateFeedbackOnly(@Param("id") Long id, @Param("fb") int fb);

    // -------------------------------------------------------------------------
    // Native read/stats operations
    // -------------------------------------------------------------------------

    @Query(value = "SELECT COUNT(*) FROM qa_feedback WHERE embedding IS NOT NULL",
           nativeQuery = true)
    long countWithEmbedding();

    // -------------------------------------------------------------------------
    // ANN similarity search (consumed by the NEXT prompt — declared here now)
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code topK} rows with positive feedback that are nearest to
     * {@code queryVec} by cosine distance.
     *
     * <p>Each element of the returned {@code Object[]} contains the columns in
     * SELECT order: id, conversation_id, user_question, ai_response, emb_text,
     * feedback, source_urls, created_at, distance.
     */
    @Query(value = "SELECT id, conversation_id, user_question, ai_response, " +
                   "CAST(embedding AS text) AS emb_text, feedback, source_urls, created_at, " +
                   "embedding <=> CAST(:queryVec AS vector) AS distance " +
                   "FROM qa_feedback " +
                   "WHERE embedding IS NOT NULL AND feedback >= 1 " +
                   "ORDER BY embedding <=> CAST(:queryVec AS vector) ASC " +
                   "LIMIT :topK",
           nativeQuery = true)
    List<Object[]> findSimilarByEmbeddingWithDistance(@Param("queryVec") String queryVec,
                                                      @Param("topK") int topK);
}
