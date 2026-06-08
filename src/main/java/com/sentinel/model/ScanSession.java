package com.sentinel.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "scan_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String targetBaseUrl;

    @Column(nullable = false)
    private String targetName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanStatus status;

    @Column(columnDefinition = "TEXT")
    private String openApiSpecSnapshot;

    private int totalEndpointsDiscovered;
    private int endpointsTested;
    private int endpointsPassed;
    private int endpointsFailed;
    private int schemaDriftsDetected;

    @CreationTimestamp
    private LocalDateTime startedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @OneToMany(mappedBy = "scanSession", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<AgentAttempt> attempts = new ArrayList<>();

    public enum ScanStatus {
        QUEUED, RUNNING, COMPLETED, FAILED, PARTIAL
    }
}
