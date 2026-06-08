package com.sentinel.sdk;

import com.sentinel.model.ScanSession;
import com.sentinel.service.OpenApiDiscoveryService;
import com.sentinel.service.OpenApiDiscoveryService.DiscoveryResult;
import com.sentinel.service.ScanOrchestrationService;
import com.sentinel.sdk.SentinelAutoConfiguration.SentinelSdkProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * Programmatic API for embedding Sentinel in existing Spring Boot projects.
 *
 * {@code @Autowired SentinelSdk sentinel;}
 * {@code ScanSession s = sentinel.scan();}
 * {@code sentinel.scanAsync().thenAccept(r -> log.info("Done: {}", r.getStatus()));}
 */
@Slf4j
@RequiredArgsConstructor
public class SentinelSdk {

    private final ScanOrchestrationService orchestrationService;
    private final OpenApiDiscoveryService discoveryService;
    private final SentinelSdkProperties properties;

    @PostConstruct
    public void onStartup() {
        if (properties.isAutoScanOnStartup()) {
            log.info("[SentinelSDK] Auto-scan triggered for: {}", properties.getTargetUrl());
            scanAsync();
        }
    }

    /** Synchronous scan — blocks until the session is queued (scan runs async in background). */
    public ScanSession scan() {
        return orchestrationService.startScan(properties.getTargetUrl(), properties.getTargetName());
    }

    /** Scan a specific target URL. */
    public ScanSession scan(String targetUrl, String targetName) {
        return orchestrationService.startScan(targetUrl, targetName);
    }

    /** Non-blocking — returns immediately. */
    public CompletableFuture<ScanSession> scanAsync() {
        return CompletableFuture.supplyAsync(this::scan);
    }

    /** Quick connectivity check — returns true if target API is reachable and spec parseable. */
    public boolean isTargetHealthy() {
        return discoveryService.discover(properties.getTargetUrl()).success();
    }

    /** Discover endpoints only — no agent testing. */
    public DiscoveryResult discover() {
        return discoveryService.discover(properties.getTargetUrl());
    }
}
