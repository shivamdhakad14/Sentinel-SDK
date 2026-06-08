package com.sentinel.controller;

import com.sentinel.model.*;
import com.sentinel.repository.*;
import com.sentinel.service.ScanOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

// ─── Shared response wrapper ──────────────────────────────────────────────────
record ApiResponse<T>(boolean success, T data, String message) {
    static <T> ApiResponse<T> success(T data)                   { return new ApiResponse<>(true, data, "OK"); }
    static <T> ApiResponse<T> success(T data, String message)   { return new ApiResponse<>(true, data, message); }
    static <T> ApiResponse<T> error(String message)             { return new ApiResponse<>(false, null, message); }
}

record ScanSessionSummary(
    Long id, String targetName, String targetBaseUrl,
    ScanSession.ScanStatus status, int totalEndpoints,
    int passed, int failed, int drifts, LocalDateTime startedAt
) {
    static ScanSessionSummary from(ScanSession s) {
        return new ScanSessionSummary(s.getId(), s.getTargetName(), s.getTargetBaseUrl(),
            s.getStatus(), s.getTotalEndpointsDiscovered(), s.getEndpointsPassed(),
            s.getEndpointsFailed(), s.getSchemaDriftsDetected(), s.getStartedAt());
    }
}

@Data
class StartScanRequest {
    @NotBlank(message = "targetBaseUrl is required")  private String targetBaseUrl;
    @NotBlank(message = "targetName is required")     private String targetName;
}

// ─── Scan Controller ──────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/v1/scans")
@RequiredArgsConstructor
@Tag(name = "Scans", description = "Manage Sentinel autonomous scan sessions")
class ScanController {

    private final ScanOrchestrationService orchestrationService;
    private final ScanSessionRepository sessionRepository;
    private final AgentAttemptRepository attemptRepository;

    @PostMapping
    @Operation(summary = "Start a new autonomous scan (async — returns immediately with session ID)")
    public ResponseEntity<ApiResponse<ScanSessionSummary>> startScan(
            @Valid @RequestBody StartScanRequest req) {
        ScanSession session = orchestrationService.startScan(req.getTargetBaseUrl(), req.getTargetName());
        return ResponseEntity.ok(ApiResponse.success(
            ScanSessionSummary.from(session),
            "Scan queued — poll GET /api/v1/scans/" + session.getId() + " for status"));
    }

    @GetMapping
    @Operation(summary = "List all scan sessions, newest first")
    public ResponseEntity<ApiResponse<List<ScanSessionSummary>>> listScans() {
        List<ScanSessionSummary> summaries = sessionRepository.findAllByOrderByStartedAtDesc()
            .stream().map(ScanSessionSummary::from).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(summaries));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get full scan session details")
    public ResponseEntity<ApiResponse<ScanSession>> getScan(@PathVariable Long id) {
        return sessionRepository.findById(id)
            .map(s -> ResponseEntity.ok(ApiResponse.success(s)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/attempts")
    @Operation(summary = "Full agent attempt log — every HTTP call made during scan")
    public ResponseEntity<ApiResponse<List<AgentAttempt>>> getAttempts(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
            attemptRepository.findByScanSessionIdOrderByCreatedAtDesc(id)));
    }

    @GetMapping("/{id}/attempts/successful")
    @Operation(summary = "Only successful attempts — the inputs that produced generated tests")
    public ResponseEntity<ApiResponse<List<AgentAttempt>>> getSuccessful(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(attemptRepository.findSuccessfulAttempts(id)));
    }

    @GetMapping("/stats")
    @Operation(summary = "Aggregate scan statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        long total    = sessionRepository.count();
        long active   = sessionRepository.countActiveSessions();
        long completed = sessionRepository.findByStatus(ScanSession.ScanStatus.COMPLETED).size();
        long failed   = sessionRepository.findByStatus(ScanSession.ScanStatus.FAILED).size();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "totalScans",   total,
            "activeScans",  active,
            "completed",    completed,
            "failed",       failed,
            "successRate",  total > 0 ? (completed * 100.0 / total) : 0.0
        )));
    }
}

// ─── Tests Controller ─────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/v1/tests")
@RequiredArgsConstructor
@Tag(name = "Generated Tests", description = "Access and download generated test artifacts")
class TestsController {

    private final GeneratedTestRepository testRepository;

    @GetMapping
    @Operation(summary = "List all generated test artifacts")
    public ResponseEntity<ApiResponse<List<GeneratedTest>>> listTests(
            @RequestParam(required = false) GeneratedTest.TestType type) {
        List<GeneratedTest> tests = type != null
            ? testRepository.findByTestType(type)
            : testRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success(tests));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a generated test artifact by ID")
    public ResponseEntity<ApiResponse<GeneratedTest>> getTest(@PathVariable Long id) {
        return testRepository.findById(id)
            .map(t -> ResponseEntity.ok(ApiResponse.success(t)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Download the raw source/JSON content of a test artifact")
    public ResponseEntity<String> downloadTest(@PathVariable Long id) {
        return testRepository.findById(id)
            .map(t -> ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + t.getClassName() + "\"")
                .body(t.getContent()))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/self-healed")
    @Operation(summary = "Tests that were automatically healed after schema drift")
    public ResponseEntity<ApiResponse<List<GeneratedTest>>> getSelfHealed() {
        return ResponseEntity.ok(ApiResponse.success(testRepository.findBySelfHealingAppliedTrue()));
    }
}

// ─── Drift Controller ─────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/v1/drift")
@RequiredArgsConstructor
@Tag(name = "Schema Drift", description = "Monitor API schema drift and self-healing status")
class DriftController {

    private final SchemaDriftEventRepository driftRepository;

    @GetMapping
    @Operation(summary = "All detected schema drift events")
    public ResponseEntity<ApiResponse<List<SchemaDriftEvent>>> listDrift(
            @RequestParam(required = false) SchemaDriftEvent.DriftSeverity severity) {
        List<SchemaDriftEvent> events = severity != null
            ? driftRepository.findBySeverityOrderByDetectedAtDesc(severity)
            : driftRepository.findAllByOrderByDetectedAtDesc();
        return ResponseEntity.ok(ApiResponse.success(events));
    }

    @GetMapping("/unhealed")
    @Operation(summary = "HIGH/CRITICAL drifts that have not yet been auto-healed")
    public ResponseEntity<ApiResponse<List<SchemaDriftEvent>>> getUnhealed() {
        return ResponseEntity.ok(ApiResponse.success(driftRepository.findUnhealed()));
    }

    @GetMapping("/stats")
    @Operation(summary = "Drift summary statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDriftStats() {
        long total    = driftRepository.count();
        long unhealed = driftRepository.countByHealingTriggeredFalse();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "totalDriftsDetected", total,
            "unhealedDrifts",      unhealed,
            "healingRate",         total > 0 ? ((total - unhealed) * 100.0 / total) : 100.0
        )));
    }
}

// ─── Dashboard Controller ─────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Aggregated metrics for the Sentinel UI")
class DashboardController {

    private final ScanSessionRepository sessionRepository;
    private final GeneratedTestRepository testRepository;
    private final SchemaDriftEventRepository driftRepository;

    @GetMapping
    @Operation(summary = "Full dashboard payload — metrics, recent sessions, drift summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        List<ScanSession> recent = sessionRepository.findRecentSessions(LocalDateTime.now().minusDays(7));
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("totalScans",         sessionRepository.count());
        dash.put("activeScans",        sessionRepository.countActiveSessions());
        dash.put("recentScans",        recent.size());
        dash.put("testsGenerated",     testRepository.count());
        dash.put("selfHealedTests",    testRepository.findBySelfHealingAppliedTrue().size());
        dash.put("totalDrifts",        driftRepository.count());
        dash.put("unhealedDrifts",     driftRepository.countByHealingTriggeredFalse());
        dash.put("recentSessions",     recent.stream().limit(10).map(ScanSessionSummary::from).toList());
        return ResponseEntity.ok(ApiResponse.success(dash));
    }
}
