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
     * 
     * Acest repository se populeaza doar cand se trigger-uieste o cautare dinamica si se aduc in
     * baza de date niste informatii si este folosita la Hybrid Search-ul (BM25 + kNN) pentru a returna 
     * boosted_urls la trimiterea prompt-ului catre AI 
     */
    @Query(value = "SELECT useful_urls FROM problem_resolutions " +
                   "WHERE feedback >= 1 AND useful_urls IS NOT NULL",
           nativeQuery = true)
    List<String> findAllUsefulUrlsWithPositiveFeedback();
}
