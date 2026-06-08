package com.sentinel.scheduler;

import com.sentinel.model.ScanSession;
import com.sentinel.repository.ScanSessionRepository;
import com.sentinel.service.ScanOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs periodic regression scans against registered target APIs.
 *
 * This is a core enterprise feature: Sentinel monitors your API 24/7
 * and detects schema drift the moment it happens — not days later
 * when a developer notices broken tests.
 *
 * Targets are registered via the /api/v1/scheduler/targets endpoint
 * and persisted in memory for this session (or extend to DB for persistence).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduledRegressionRunner {

    private final ScanOrchestrationService orchestrationService;
    private final ScanSessionRepository sessionRepository;

    // In-memory registry of targets to scan on schedule.
    // In production, persist these to the DB (add a ScheduledTarget entity).
    private final Map<String, ScheduledTarget> registeredTargets = new ConcurrentHashMap<>();

    @Value("${sentinel.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${sentinel.scheduler.max-concurrent-scheduled:2}")
    private int maxConcurrent;

    /**
     * Runs every hour. Checks which registered targets are due for a scan
     * and kicks off scans for them.
     */
    @Scheduled(fixedRateString = "${sentinel.scheduler.interval-ms:3600000}")
    public void runScheduledScans() {
        if (!schedulerEnabled) return;

        long activeSessions = sessionRepository.countActiveSessions();
        if (activeSessions >= maxConcurrent) {
            log.info("[Scheduler] Skipping — {} scans already running (max: {})",
                activeSessions, maxConcurrent);
            return;
        }

        log.info("[Scheduler] Running scheduled regression check for {} targets",
            registeredTargets.size());

        registeredTargets.values().stream()
            .filter(this::isDueForScan)
            .limit(maxConcurrent - activeSessions)
            .forEach(target -> {
                log.info("[Scheduler] Triggering scheduled scan: {}", target.name());
                try {
                    ScanSession session = orchestrationService.startScan(
                        target.baseUrl(), target.name()
                    );
                    target.setLastScannedAt(LocalDateTime.now());
                    log.info("[Scheduler] Scan started — session ID: {}", session.getId());
                } catch (Exception e) {
                    log.error("[Scheduler] Failed to start scan for {}: {}",
                        target.name(), e.getMessage());
                }
            });
    }

    /**
     * Startup scan — run once on application start to establish baseline.
     * Only triggers if no previous scan exists for the default target.
     */
    @Scheduled(initialDelayString = "${sentinel.scheduler.startup-delay-ms:30000}",
               fixedDelay = Long.MAX_VALUE)
    public void runBaselineScan() {
        if (!schedulerEnabled || registeredTargets.isEmpty()) return;

        registeredTargets.values().stream()
            .filter(t -> sessionRepository
                .findTopByTargetBaseUrlAndStatusOrderByStartedAtDesc(
                    t.baseUrl(), ScanSession.ScanStatus.COMPLETED)
                .isEmpty())
            .forEach(target -> {
                log.info("[Scheduler] Running baseline scan for new target: {}", target.name());
                orchestrationService.startScan(target.baseUrl(), target.name());
            });
    }

    // ── Registration API ─────────────────────────────────────────────────────

    public void registerTarget(String baseUrl, String name, int intervalHours) {
        registeredTargets.put(baseUrl, new ScheduledTarget(baseUrl, name, intervalHours));
        log.info("[Scheduler] Registered target: {} (every {}h)", name, intervalHours);
    }

    public void unregisterTarget(String baseUrl) {
        registeredTargets.remove(baseUrl);
        log.info("[Scheduler] Unregistered target: {}", baseUrl);
    }

    public List<ScheduledTarget> listTargets() {
        return List.copyOf(registeredTargets.values());
    }

    private boolean isDueForScan(ScheduledTarget target) {
        if (target.lastScannedAt() == null) return true;
        return target.lastScannedAt()
            .plusHours(target.intervalHours())
            .isBefore(LocalDateTime.now());
    }

    // ── DTO ──────────────────────────────────────────────────────────────────

    public static class ScheduledTarget {
        private final String baseUrl;
        private final String name;
        private final int intervalHours;
        private LocalDateTime lastScannedAt;

        public ScheduledTarget(String baseUrl, String name, int intervalHours) {
            this.baseUrl = baseUrl;
            this.name = name;
            this.intervalHours = intervalHours;
        }

        public String baseUrl()        { return baseUrl; }
        public String name()           { return name; }
        public int intervalHours()     { return intervalHours; }
        public LocalDateTime lastScannedAt() { return lastScannedAt; }
        public void setLastScannedAt(LocalDateTime t) { this.lastScannedAt = t; }
    }
}
