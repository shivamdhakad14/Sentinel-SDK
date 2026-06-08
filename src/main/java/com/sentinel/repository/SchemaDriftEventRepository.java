package com.sentinel.repository;

import com.sentinel.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchemaDriftEventRepository extends JpaRepository<SchemaDriftEvent, Long> {
    List<SchemaDriftEvent> findAllByOrderByDetectedAtDesc();
    List<SchemaDriftEvent> findByScanSessionId(Long sessionId);
    List<SchemaDriftEvent> findBySeverityOrderByDetectedAtDesc(SchemaDriftEvent.DriftSeverity severity);
    @Query("SELECT e FROM SchemaDriftEvent e WHERE e.healingTriggered = false AND e.severity IN ('HIGH','CRITICAL')")
    List<SchemaDriftEvent> findUnhealed();
    long countByHealingTriggeredFalse();
}
