package com.sentinel.controller;

import com.sentinel.scheduler.ScheduledRegressionRunner;
import com.sentinel.scheduler.ScheduledRegressionRunner.ScheduledTarget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler")
@RequiredArgsConstructor
@Tag(name = "Scheduler", description = "Register targets for periodic autonomous regression scans")
public class SchedulerController {

    private final ScheduledRegressionRunner scheduler;

    @PostMapping("/targets")
    @Operation(summary = "Register a target API for scheduled scanning",
        description = "Sentinel will autonomously scan this API on the specified interval and " +
            "auto-detect schema drift. Ideal for CI environments.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerTarget(
            @Valid @RequestBody RegisterTargetRequest request) {

        scheduler.registerTarget(
            request.getBaseUrl(),
            request.getName(),
            request.getIntervalHours()
        );

        return ResponseEntity.ok(ApiResponse.success(
            Map.of(
                "baseUrl", request.getBaseUrl(),
                "name", request.getName(),
                "intervalHours", request.getIntervalHours(),
                "message", "Target registered. First scan will begin shortly."
            ),
            "Target registered for automated regression monitoring."
        ));
    }

    @DeleteMapping("/targets")
    @Operation(summary = "Unregister a target from scheduled scanning")
    public ResponseEntity<ApiResponse<Void>> unregisterTarget(
            @RequestParam String baseUrl) {
        scheduler.unregisterTarget(baseUrl);
        return ResponseEntity.ok(ApiResponse.success(null, "Target unregistered."));
    }

    @GetMapping("/targets")
    @Operation(summary = "List all registered scheduled targets")
    public ResponseEntity<ApiResponse<List<ScheduledTargetView>>> listTargets() {
        List<ScheduledTargetView> views = scheduler.listTargets()
            .stream()
            .map(ScheduledTargetView::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.success(views));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    static class RegisterTargetRequest {
        @NotBlank private String baseUrl;
        @NotBlank private String name;
        @Min(1) @Max(168) private int intervalHours = 6;
    }

    record ScheduledTargetView(String baseUrl, String name, int intervalHours, String lastScannedAt) {
        static ScheduledTargetView from(ScheduledTarget t) {
            return new ScheduledTargetView(
                t.baseUrl(), t.name(), t.intervalHours(),
                t.lastScannedAt() != null ? t.lastScannedAt().toString() : "Never"
            );
        }
    }
}
