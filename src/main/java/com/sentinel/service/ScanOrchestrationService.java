package com.sentinel.service;

import com.sentinel.agent.SchemaDriftDetector;
import com.sentinel.agent.SentinelAgent;
import com.sentinel.agent.SentinelAgent.EndpointTestResult;
import com.sentinel.model.*;
import com.sentinel.report.TestReportGenerator;
import com.sentinel.repository.*;
import com.sentinel.service.OpenApiDiscoveryService.DiscoveryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Orchestrates the full scan lifecycle.
 *
 * LLM call budget per scan (v2):
 *   Call 1 — Build execution plan (all endpoints analyzed at once)
 *   Call 2 — Batch self-correct failures (0 if all pass, 1 if any fail)
 *   Call 3 — Generate all JUnit 5 tests at once
 *   Call 4 — Schema drift AI analysis (0 if no drift, 1 if drift found)
 *   Call 5 — Self-healing PR test fixes (0 if no drift, 1 per drifted file)
 *
 *   WORST CASE: 5 calls. TYPICAL: 3 calls. vs v1's 60+ calls.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScanOrchestrationService {

    private final OpenApiDiscoveryService discoveryService;
    private final SentinelAgent agent;
    private final SchemaDriftDetector driftDetector;
    private final TestReportGenerator reportGenerator;
    private final GitHubPrService githubPrService;
    private final ScanSessionRepository sessionRepository;
    private final AgentAttemptRepository attemptRepository;
    private final GeneratedTestRepository testRepository;
    private final SchemaDriftEventRepository driftRepository;
    private final SimpMessagingTemplate websocket;
    @Qualifier("agentTaskExecutor")
    private final Executor agentTaskExecutor;

    @Transactional
    public ScanSession startScan(String targetBaseUrl, String targetName) {
        ScanSession session = sessionRepository.save(ScanSession.builder()
            .targetBaseUrl(targetBaseUrl).targetName(targetName)
            .status(ScanSession.ScanStatus.QUEUED).build());
        submitAfterCommit(() -> runScan(session.getId(), targetBaseUrl, targetName));
        return session;
    }

    private void runScan(Long sessionId, String targetBaseUrl, String targetName) {
        ScanSession session = sessionRepository.findById(sessionId).orElseThrow();

        try {
            // ── PHASE 1: Discovery ────────────────────────────────────────────
            session.setStatus(ScanSession.ScanStatus.RUNNING);
            sessionRepository.save(session);
            broadcast(sessionId, "DISCOVERING", "Fetching OpenAPI spec from " + targetBaseUrl + "…");

            DiscoveryResult discovery = discoveryService.discover(targetBaseUrl);
            if (!discovery.success()) { failSession(session, discovery.errorMessage()); return; }

            String previousSpec = sessionRepository
                .findTopByTargetBaseUrlAndStatusOrderByStartedAtDesc(targetBaseUrl, ScanSession.ScanStatus.COMPLETED)
                .map(ScanSession::getOpenApiSpecSnapshot).orElse(null);

            session.setOpenApiSpecSnapshot(discovery.rawSpecJson());
            session.setTotalEndpointsDiscovered(discovery.endpoints().size());
            sessionRepository.save(session);

            broadcast(sessionId, "PLANNING",
                "Discovered " + discovery.endpoints().size() + " endpoints — building AI execution plan…");

            // ── PHASE 2: Agent (1-2 LLM calls) ───────────────────────────────
            List<EndpointTestResult> results = agent.testAllEndpoints(
                session,
                discovery.endpoints(),
                discovery.rawSpecJson(),
                (phase, msg) -> broadcast(sessionId, phase, msg)
            );

            // Persist all attempts
            results.forEach(r -> attemptRepository.saveAll(r.attempts()));

            int passed = (int) results.stream().filter(EndpointTestResult::success).count();
            int failed = results.size() - passed;
            session.setEndpointsTested(results.size());
            session.setEndpointsPassed(passed);
            session.setEndpointsFailed(failed);
            sessionRepository.save(session);

            // ── PHASE 3: Schema Drift Detection ───────────────────────────────
            broadcast(sessionId, "DRIFT_CHECK", "Comparing API specs for schema drift…");
            List<SchemaDriftEvent> driftEvents = driftDetector.detectDrift(
                previousSpec, discovery.rawSpecJson(), session);

            if (!driftEvents.isEmpty()) {
                driftRepository.saveAll(driftEvents);
                reportGenerator.enrichDriftEvents(driftEvents); // ~250 tokens, compressed prompt
                session.setSchemaDriftsDetected(driftEvents.size());
                sessionRepository.save(session);
                broadcast(sessionId, "DRIFT_FOUND",
                    "⚠️ " + driftEvents.size() + " schema drift(s) detected");
            }

            // ── PHASE 4: Test Generation (1 LLM call for all) ────────────────
            broadcast(sessionId, "GENERATING", "Generating test artifacts (1 AI call for all endpoints)…");

            List<GeneratedTest> tests = new ArrayList<>();
            tests.addAll(reportGenerator.generateAllJUnit5Tests(results, session));     // 1 LLM call
            tests.add(reportGenerator.generatePostmanCollection(results, session));     // 0 LLM calls
            testRepository.saveAll(tests);

            // ── PHASE 5: Self-Healing PRs ─────────────────────────────────────
            if (!driftEvents.isEmpty()) {
                broadcast(sessionId, "SELF_HEALING", "Auto-healing " + driftEvents.size() + " drifted test(s)…");
                List<GeneratedTest> healed = healTests(driftEvents, tests);
                if (!healed.isEmpty()) {
                    testRepository.saveAll(healed);
                    String prUrl = githubPrService.createSelfHealingPr(driftEvents, healed);
                    if (prUrl != null) {
                        healed.forEach(t -> { t.setGithubPrUrl(prUrl); t.setHealingStatus(GeneratedTest.HealingStatus.PR_SUBMITTED); });
                        testRepository.saveAll(healed);
                        broadcast(sessionId, "PR_CREATED", "Self-healing PR: " + prUrl);
                    }
                }
            }

            // ── DONE ──────────────────────────────────────────────────────────
            session.setStatus(ScanSession.ScanStatus.COMPLETED);
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);

            broadcast(sessionId, "COMPLETED", String.format(
                "✅ Done — %d/%d passed · %d tests generated · %d drifts · ~3 AI calls used",
                passed, results.size(), tests.size(), driftEvents.size()));

        } catch (Exception e) {
            log.error("[Orchestrator] Scan {} failed: {}", sessionId, e.getMessage(), e);
            failSession(session, e.getMessage());
        }
    }

    private List<GeneratedTest> healTests(List<SchemaDriftEvent> drifts, List<GeneratedTest> tests) {
        List<GeneratedTest> healed = new ArrayList<>();
        drifts.stream()
            .filter(d -> d.getDriftType() == SchemaDriftEvent.DriftType.FIELD_RENAMED
                      || d.getDriftType() == SchemaDriftEvent.DriftType.FIELD_REMOVED)
            .forEach(drift -> tests.stream()
                .filter(t -> t.getTargetEndpoint() != null && t.getTargetEndpoint().contains(drift.getEndpointPath()))
                .forEach(test -> {
                    String fixed = reportGenerator.healTest(
                        test.getContent(), drift.getAiAnalysis(), drift.getPreviousValue(), drift.getNewValue());
                    test.setContent(fixed);
                    test.setSelfHealingApplied(true);
                    test.setHealingDiff(drift.getPreviousValue() + " → " + drift.getNewValue());
                    healed.add(test);
                }));
        return healed;
    }

    private void failSession(ScanSession session, String reason) {
        session.setStatus(ScanSession.ScanStatus.FAILED);
        session.setErrorMessage(reason);
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);
        broadcast(session.getId(), "FAILED", "❌ " + reason);
    }

    private void broadcast(Long sessionId, String phase, String message) {
        try {
            websocket.convertAndSend("/topic/scan/" + sessionId,
                Map.of("phase", phase, "message", message, "ts", System.currentTimeMillis()));
        } catch (Exception e) { /* non-critical */ }
    }

    private void submitAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    agentTaskExecutor.execute(task);
                }
            });
        } else {
            agentTaskExecutor.execute(task);
        }
    }
}
