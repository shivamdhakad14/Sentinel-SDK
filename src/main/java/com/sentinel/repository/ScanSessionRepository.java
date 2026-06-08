package com.sentinel.repository;

import com.sentinel.model.ScanSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScanSessionRepository extends JpaRepository<ScanSession, Long> {
    List<ScanSession> findAllByOrderByStartedAtDesc();

    List<ScanSession> findByStatus(ScanSession.ScanStatus status);

    Optional<ScanSession> findTopByTargetBaseUrlAndStatusOrderByStartedAtDesc(
            String targetBaseUrl, ScanSession.ScanStatus status);

    @Query("SELECT s FROM ScanSession s WHERE s.startedAt >= :since ORDER BY s.startedAt DESC")
    List<ScanSession> findRecentSessions(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(s) FROM ScanSession s WHERE s.status = 'RUNNING'")
    long countActiveSessions();
}
