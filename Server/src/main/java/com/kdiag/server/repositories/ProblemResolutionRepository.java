package com.kdiag.server.repositories;

import com.kdiag.server.entities.ProblemResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProblemResolutionRepository extends JpaRepository<ProblemResolution, Long> {
    List<ProblemResolution> findByConversationId(String conversationId);
    java.util.Optional<ProblemResolution> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);
}
