package com.sentinel.repository;

import com.sentinel.model.AgentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentAttemptRepository extends JpaRepository<AgentAttempt, Long> {
    List<AgentAttempt> findByScanSessionIdOrderByCreatedAtDesc(Long sessionId);

    List<AgentAttempt> findByScanSessionIdAndEndpointPath(Long sessionId, String path);

    @Query("SELECT a FROM AgentAttempt a WHERE a.scanSession.id = :sid AND a.outcome = 'SUCCESS'")
    List<AgentAttempt> findSuccessfulAttempts(@Param("sid") Long sessionId);

    long countByScanSessionIdAndOutcome(Long sessionId, AgentAttempt.AttemptOutcome outcome);

    @Query("SELECT a FROM AgentAttempt a WHERE a.schemaDriftDetected = true ORDER BY a.createdAt DESC")
    List<AgentAttempt> findAllDriftAttempts();
}
