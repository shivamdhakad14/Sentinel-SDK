package com.sentinel.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Records every schema drift event detected by Sentinel.
 * Used for the Self-Healing Regression feature and enterprise reporting.
 */
@Entity
@Table(name = "schema_drift_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaDriftEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_session_id")
    private ScanSession scanSession;

    private String endpointPath;
    private String httpMethod;

    @Enumerated(EnumType.STRING)
    private DriftType driftType;

    // What changed
    private String fieldPath;         // e.g. "body.user_id"
    private String previousValue;     // e.g. "user_id"
    private String newValue;          // e.g. "customer_uuid"

    @Column(columnDefinition = "TEXT")
    private String aiAnalysis;        // LLM's explanation of the drift

    @Column(columnDefinition = "TEXT")
    private String suggestedFix;      // How to update affected tests

    // ── Impact Analysis ──────────────────────────────────
    private int affectedTestsCount;

    @Column(columnDefinition = "TEXT")
    private String affectedTestsList; // JSON array of test class names

    private boolean healingTriggered;
    private String healingPrUrl;

    @Enumerated(EnumType.STRING)
    private DriftSeverity severity;

    @CreationTimestamp
    private LocalDateTime detectedAt;

    public enum DriftType {
        FIELD_RENAMED,
        FIELD_REMOVED,
        FIELD_TYPE_CHANGED,
        FIELD_ADDED_REQUIRED,
        ENDPOINT_REMOVED,
        ENDPOINT_PATH_CHANGED,
        RESPONSE_SCHEMA_CHANGED,
        AUTH_REQUIREMENT_CHANGED
    }

    public enum DriftSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
