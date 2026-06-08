package com.sentinel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.model.ScanSession;
import com.sentinel.model.SchemaDriftEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SchemaDriftDetector — OpenAPI Spec Diff (Spring AI 1.0.x)")
class SchemaDriftDetectorTest {

    // Spring AI 1.0.x mock chain: prompt().user(String).call().content()
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callSpec;

    SchemaDriftDetector detector;
    final ObjectMapper mapper = new ObjectMapper();
    ScanSession session;

    static final String SPEC_V1 = """
        {"paths":{"/api/orders":{"post":{"requestBody":{"content":{"application/json":{"schema":{
          "type":"object","properties":{
            "user_id":{"type":"string"},
            "product_id":{"type":"string"},
            "quantity":{"type":"integer"}
          }}}}}}}}}""";

    static final String SPEC_V2_RENAMED = """
        {"paths":{"/api/orders":{"post":{"requestBody":{"content":{"application/json":{"schema":{
          "type":"object","properties":{
            "customer_uuid":{"type":"string"},
            "product_id":{"type":"string"},
            "quantity":{"type":"integer"}
          }}}}}}}}}""";

    static final String SPEC_V3_TYPE_CHANGE = """
        {"paths":{"/api/orders":{"post":{"requestBody":{"content":{"application/json":{"schema":{
          "type":"object","properties":{
            "user_id":{"type":"string"},
            "product_id":{"type":"string"},
            "quantity":{"type":"string"}
          }}}}}}}}}""";

    static final String SPEC_V4_ENDPOINT_REMOVED = """
        {"paths":{"/api/products":{"get":{"responses":{"200":{}}}}}}""";

    @BeforeEach
    void setUp() {
        detector = new SchemaDriftDetector(chatClient, mapper);
        session = ScanSession.builder().id(1L).targetName("Test")
            .targetBaseUrl("http://localhost:8080")
            .status(ScanSession.ScanStatus.RUNNING).build();

        // Stub AI enrichment with Spring AI 1.0.x chain
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("[]");   // empty enrichment — structural diff still works
    }

    @Test
    @DisplayName("Returns empty list when no previous spec (baseline scan)")
    void detectDrift_noBaseline_returnsEmpty() {
        assertThat(detector.detectDrift(null, SPEC_V2_RENAMED, session)).isEmpty();
        assertThat(detector.detectDrift("", SPEC_V2_RENAMED, session)).isEmpty();
    }

    @Test
    @DisplayName("Detects FIELD_RENAMED: user_id → customer_uuid")
    void detectDrift_fieldRenamed() {
        List<SchemaDriftEvent> events = detector.detectDrift(SPEC_V1, SPEC_V2_RENAMED, session);
        assertThat(events).anyMatch(e ->
            e.getDriftType() == SchemaDriftEvent.DriftType.FIELD_RENAMED &&
            e.getPreviousValue().equals("user_id") &&
            e.getNewValue().equals("customer_uuid"));
    }

    @Test
    @DisplayName("Detects FIELD_TYPE_CHANGED: quantity integer → string")
    void detectDrift_typeChanged() {
        List<SchemaDriftEvent> events = detector.detectDrift(SPEC_V1, SPEC_V3_TYPE_CHANGE, session);
        assertThat(events).anyMatch(e ->
            e.getDriftType() == SchemaDriftEvent.DriftType.FIELD_TYPE_CHANGED &&
            e.getFieldPath().contains("quantity") &&
            e.getPreviousValue().equals("integer") &&
            e.getNewValue().equals("string"));
    }

    @Test
    @DisplayName("Detects ENDPOINT_REMOVED: POST /api/orders gone entirely")
    void detectDrift_endpointRemoved() {
        List<SchemaDriftEvent> events = detector.detectDrift(SPEC_V1, SPEC_V4_ENDPOINT_REMOVED, session);
        assertThat(events).anyMatch(e ->
            e.getEndpointPath().equals("/api/orders") &&
            (e.getDriftType() == SchemaDriftEvent.DriftType.ENDPOINT_REMOVED ||
             e.getDriftType() == SchemaDriftEvent.DriftType.FIELD_REMOVED));
    }

    @Test
    @DisplayName("Assigns CRITICAL or HIGH severity to removed endpoint")
    void detectDrift_removedEndpoint_highSeverity() {
        List<SchemaDriftEvent> events = detector.detectDrift(SPEC_V1, SPEC_V4_ENDPOINT_REMOVED, session);
        events.stream()
            .filter(e -> e.getEndpointPath().equals("/api/orders"))
            .forEach(e -> assertThat(e.getSeverity())
                .isIn(SchemaDriftEvent.DriftSeverity.CRITICAL, SchemaDriftEvent.DriftSeverity.HIGH));
    }

    @Test
    @DisplayName("Returns empty list when specs are identical — no false positives")
    void detectDrift_identicalSpecs_returnsEmpty() {
        // No AI call needed for identical specs
        assertThat(detector.detectDrift(SPEC_V1, SPEC_V1, session)).isEmpty();
        verifyNoInteractions(chatClient);
    }

    @Test
    @DisplayName("All drift events carry correct scanSession reference")
    void detectDrift_eventsHaveSessionReference() {
        List<SchemaDriftEvent> events = detector.detectDrift(SPEC_V1, SPEC_V2_RENAMED, session);
        assertThat(events).isNotEmpty();
        events.forEach(e -> assertThat(e.getScanSession()).isEqualTo(session));
    }

    @Test
    @DisplayName("AI enrichment failure does not crash — structural diff still returned")
    void detectDrift_aiEnrichmentFails_structuralDriftStillReturned() {
        when(callSpec.content()).thenThrow(new RuntimeException("OpenAI timeout"));
        // Should not throw — graceful degradation
        List<SchemaDriftEvent> events = detector.detectDrift(SPEC_V1, SPEC_V2_RENAMED, session);
        assertThat(events).isNotEmpty();  // structural diff still works
    }
}
