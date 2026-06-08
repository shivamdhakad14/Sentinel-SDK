package com.sentinel.integration;

import com.sentinel.SentinelSdkApplication;
import com.sentinel.agent.SchemaDriftDetector;
import com.sentinel.agent.SentinelAgent;
import com.sentinel.report.TestReportGenerator;
import com.sentinel.scheduler.ScheduledRegressionRunner;
import com.sentinel.service.GitHubPrService;
import com.sentinel.service.OpenApiDiscoveryService;
import com.sentinel.service.ScanOrchestrationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.*;

/**
 * Full context integration test.
 *
 * Uses H2 in-memory DB so no MySQL needed.
 * Mocks ChatModel so no real OpenAI key needed.
 * Verifies all Spring beans wire correctly.
 */
@SpringBootTest(
    classes = SentinelSdkApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    // H2 in-memory instead of MySQL
    "spring.datasource.url=jdbc:h2:mem:sentinel_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    // Stub OpenAI key — ChatModel is mocked below
    "spring.ai.openai.api-key=test-key-not-real",
    // Disable scheduler to avoid background scans during tests
    "sentinel.scheduler.enabled=false",
    "sentinel.agent.max-retries=1",
    "sentinel.report.output-dir=/tmp/sentinel-test-output"
})
@DisplayName("Sentinel SDK — Spring Context Integration")
class SentinelSdkIntegrationTest {

    // Mock ChatModel so no real OpenAI API calls are made
    @MockBean org.springframework.ai.chat.model.ChatModel chatModel;

    @Autowired OpenApiDiscoveryService discoveryService;
    @Autowired SentinelAgent sentinelAgent;
    @Autowired SchemaDriftDetector driftDetector;
    @Autowired ScanOrchestrationService orchestrationService;
    @Autowired TestReportGenerator reportGenerator;
    @Autowired ScheduledRegressionRunner scheduledRunner;
    @Autowired GitHubPrService githubPrService;

    @Test
    @DisplayName("All beans wired — Spring context loads without errors")
    void contextLoads() {
        assertThat(discoveryService).isNotNull();
        assertThat(sentinelAgent).isNotNull();
        assertThat(driftDetector).isNotNull();
        assertThat(orchestrationService).isNotNull();
        assertThat(reportGenerator).isNotNull();
        assertThat(scheduledRunner).isNotNull();
        assertThat(githubPrService).isNotNull();
    }

    @Test
    @DisplayName("Scheduler starts with empty target registry")
    void scheduler_startsEmpty() {
        assertThat(scheduledRunner.listTargets()).isEmpty();
    }

    @Test
    @DisplayName("Scheduler register → list → unregister lifecycle works")
    void scheduler_registerUnregisterLifecycle() {
        scheduledRunner.registerTarget("http://test-api:8080", "Test API", 6);
        assertThat(scheduledRunner.listTargets()).hasSize(1);
        assertThat(scheduledRunner.listTargets().get(0).name()).isEqualTo("Test API");
        assertThat(scheduledRunner.listTargets().get(0).intervalHours()).isEqualTo(6);

        scheduledRunner.unregisterTarget("http://test-api:8080");
        assertThat(scheduledRunner.listTargets()).isEmpty();
    }

    @Test
    @DisplayName("Discovery returns graceful failure for unreachable target")
    void discovery_unreachableTarget_gracefulFailure() {
        var result = discoveryService.discover("http://does-not-exist.invalid:9999");
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(result.endpoints()).isEmpty();
    }

    @Test
    @DisplayName("Drift detector returns empty for null previous spec (baseline scan)")
    void driftDetector_nullPreviousSpec_returnsEmpty() {
        var session = com.sentinel.model.ScanSession.builder()
            .id(999L).targetName("Test").targetBaseUrl("http://localhost:8080")
            .status(com.sentinel.model.ScanSession.ScanStatus.RUNNING).build();
        var events = driftDetector.detectDrift(null, "{\"paths\":{}}", session);
        assertThat(events).isEmpty();
    }
}
