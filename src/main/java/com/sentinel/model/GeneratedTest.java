package com.sentinel.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Represents a fully generated test artifact.
 * Can be a JUnit class, Postman collection, or OpenAPI diff report.
 */
@Entity
@Table(name = "generated_tests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_session_id", nullable = false)
    private ScanSession scanSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestType testType;

    @Column(nullable = false)
    private String className;         // e.g. "OrdersApiTest"

    private String packageName;       // e.g. "com.generated.tests"

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String content;           // The actual source code / JSON

    private String targetEndpoint;    // Which endpoint this covers

    private int coverageScore;        // 0-100 confidence score

    // ── Self-Healing ─────────────────────────────────────
    private boolean selfHealingApplied;

    @Column(columnDefinition = "TEXT")
    private String healingDiff;       // What changed in the test

    private String githubPrUrl;       // PR link if auto-submitted

    @Enumerated(EnumType.STRING)
    private HealingStatus healingStatus;

    @CreationTimestamp
    private LocalDateTime generatedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum TestType {
        JUNIT5,
        POSTMAN_COLLECTION,
        OPENAPI_DIFF_REPORT,
        REST_ASSURED
    }

    public enum HealingStatus {
        NOT_NEEDED,
        HEALING_IN_PROGRESS,
        PR_SUBMITTED,
        PR_MERGED,
        HEALING_FAILED
    }
}
