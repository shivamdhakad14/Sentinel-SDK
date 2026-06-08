package com.sentinel.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Stores every single reasoning step the agent takes.
 * This is the "audit trail" — companies can review exactly how Sentinel
 * explored and tested their API.
 */
@Entity
@Table(name = "agent_attempts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_session_id", nullable = false)
    private ScanSession scanSession;

    // ── Endpoint under test ─────────────────────────────
    @Column(nullable = false)
    private String httpMethod;

    @Column(nullable = false)
    private String endpointPath;

    private int attemptNumber;

    // ── Think-Act-Observe ───────────────────────────────
    @Column(columnDefinition = "TEXT")
    private String agentThought;      // LLM's reasoning text

    @Column(columnDefinition = "TEXT")
    private String requestPayload;    // JSON body sent

    @Column(columnDefinition = "TEXT")
    private String requestHeaders;    // Headers used

    private String requestUrl;        // Full resolved URL

    // ── Response ────────────────────────────────────────
    private int responseStatusCode;

    @Column(columnDefinition = "LONGTEXT")
    private String responseBody;

    private long responseTimeMs;

    // ── Outcome ─────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    private AttemptOutcome outcome;

    @Column(columnDefinition = "TEXT")
    private String correctionReason;  // Why the agent adjusted payload

    @Column(columnDefinition = "TEXT")
    private String generatedTestCode; // JUnit snippet for this attempt

    private boolean schemaDriftDetected;

    @Column(columnDefinition = "TEXT")
    private String schemaDriftDetails;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum AttemptOutcome {
        SUCCESS,          // 2xx received
        RETRYING,         // Non-2xx, agent is self-correcting
        FAILED,           // Max retries exhausted
        SCHEMA_DRIFT,     // Detected field rename / structural change
        SKIPPED           // Agent determined endpoint needs auth it can't obtain
    }
}
