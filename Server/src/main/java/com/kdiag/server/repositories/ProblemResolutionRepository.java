package com.kdiag.server.repositories;

import com.kdiag.server.entities.ProblemResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProblemResolutionRepository extends JpaRepository<ProblemResolution, Long> {
    List<ProblemResolution> findByConversationId(String conversationId);
    java.util.Optional<ProblemResolution> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);

    /**
     * Returns the raw {@code useful_urls} TEXT values for all resolutions with positive feedback.
     * Each value is a newline-joined list of URLs (see KubernetesDynamicSearcher.searchAndSave).
     * Callers must split on {@code \\R} (any line break).
     */
    @Query(value = "SELECT useful_urls FROM problem_resolutions " +
                   "WHERE feedback >= 1 AND useful_urls IS NOT NULL",
           nativeQuery = true)
    List<String> findAllUsefulUrlsWithPositiveFeedback();
}
