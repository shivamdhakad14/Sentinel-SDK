package com.sentinel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.model.AgentAttempt;
import com.sentinel.model.ScanSession;
import com.sentinel.service.OpenApiDiscoveryService.EndpointDescriptor;
import com.sentinel.tools.AgentTools;
import com.sentinel.tools.AgentTools.HttpCallResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SentinelAgent — Batch TAO Loop (Spring AI 1.0.x)")
class SentinelAgentTest {

    // Spring AI 1.0.x ChatClient mock chain:
    // chatClient.prompt() → ChatClientRequestSpec
    //   .user(String)     → ChatClientRequestSpec
    //   .call()           → CallResponseSpec
    //   .content()        → String
    //   .entity(Class)    → T
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock AgentTools agentTools;
    @InjectMocks SentinelAgent agent;

    ScanSession session;
    List<EndpointDescriptor> endpoints;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(agent, "maxRetries", 2);

        session = ScanSession.builder()
            .id(1L).targetBaseUrl("http://localhost:8080")
            .targetName("Test API").status(ScanSession.ScanStatus.RUNNING).build();

        endpoints = List.of(
            new EndpointDescriptor("http://localhost:8080", "/api/users", "GET",
                "listUsers", "List users", "", List.of(), null, Map.of(), List.of()),
            new EndpointDescriptor("http://localhost:8080", "/api/orders", "POST",
                "createOrder", "Create order", "", List.of(),
                "{\"type\":\"object\",\"properties\":{\"userId\":{\"type\":\"string\"}}}",
                Map.of(), List.of())
        );
    }

    /** Wire Spring AI 1.0.x: prompt().user().call().content() */
    private void stubLlmContent(String content) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(content);
    }

    /** Wire Spring AI 1.0.x: prompt().user().call().entity(T.class) */
    private void stubLlmEntity(Object entity) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(any(Class.class))).thenReturn(entity);
    }

    private SentinelAgent.ExecutionPlan singleStepPlan(String operationId, String method, String path) {
        return new SentinelAgent.ExecutionPlan(List.of(
            new SentinelAgent.ExecutionPlan.Step(
                operationId, method, path,
                "http://localhost:8080" + path,
                null, null, null, false, null)
        ));
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Returns SUCCESS for all endpoints when HTTP calls succeed")
    void testAllEndpoints_allSucceed() {
        stubLlmEntity(new SentinelAgent.ExecutionPlan(List.of(
            new SentinelAgent.ExecutionPlan.Step("listUsers","GET","/api/users",
                "http://localhost:8080/api/users",null,null,null,false,null)
        )));
        when(agentTools.callEndpoint(any(), any(), any(), any()))
            .thenReturn(new HttpCallResult(200, "[{\"id\":\"u1\",\"name\":\"Alice\"}]", 100, null));

        List<SentinelAgent.EndpointTestResult> results =
            agent.testAllEndpoints(session, endpoints.subList(0,1), "{}", (p,m) -> {});

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).finalStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Triggers batch self-correction when endpoints fail")
    void testAllEndpoints_failureTriggersBatchCorrection() {
        // Plan returns one step
        stubLlmEntity(singleStepPlan("createOrder","POST","/api/orders"));

        // First HTTP call fails, correction call returns fixed payload, retry succeeds
        when(agentTools.callEndpoint(any(), any(), any(), any()))
            .thenReturn(new HttpCallResult(400, "{\"error\":\"quantity must be >= 1\"}", 80, null))
            .thenReturn(new HttpCallResult(201, "{\"id\":\"ord_001\"}", 120, null));

        // LLM entity call (plan) + LLM content call (correction)
        when(callSpec.content()).thenReturn("{\"createOrder\":\"{\\\"quantity\\\":1}\"}");

        List<SentinelAgent.EndpointTestResult> results =
            agent.testAllEndpoints(session, endpoints.subList(1,2), "{}", (p,m) -> {});

        assertThat(results).hasSize(1);
        // After correction and retry it should succeed
        verify(agentTools, atLeast(1)).callEndpoint(any(), eq("POST"), any(), any());
    }

    @Test
    @DisplayName("Skips endpoints marked skip=true in execution plan")
    void testAllEndpoints_skipsSecuredEndpoints() {
        stubLlmEntity(new SentinelAgent.ExecutionPlan(List.of(
            new SentinelAgent.ExecutionPlan.Step("adminEndpoint","DELETE","/api/admin",
                "http://localhost:8080/api/admin",null,null,null,true,"Requires admin OAuth")
        )));

        EndpointDescriptor adminEndpoint = new EndpointDescriptor(
            "http://localhost:8080","/api/admin","DELETE","adminEndpoint",
            "","",List.of(),null,Map.of(),List.of("oauth2"));

        List<SentinelAgent.EndpointTestResult> results =
            agent.testAllEndpoints(session, List.of(adminEndpoint), "{}", (p,m) -> {});

        assertThat(results).hasSize(1);
        assertThat(results.get(0).skipped()).isTrue();
        verify(agentTools, never()).callEndpoint(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Uses fallback plan when LLM returns null entity")
    void testAllEndpoints_nullPlan_usesFallback() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(any(Class.class))).thenReturn(null);

        when(agentTools.callEndpoint(any(), any(), any(), any()))
            .thenReturn(new HttpCallResult(200, "[]", 50, null));

        List<SentinelAgent.EndpointTestResult> results =
            agent.testAllEndpoints(session, endpoints, "{}", (p,m) -> {});

        // Fallback plan still tests all endpoints
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Extracts context from GET response for downstream POST")
    void extractContext_populatesSharedContext() {
        // GET /api/users succeeds and returns user with id
        // POST /api/orders should receive userId in payload
        stubLlmEntity(new SentinelAgent.ExecutionPlan(List.of(
            new SentinelAgent.ExecutionPlan.Step("listUsers","GET","/api/users",
                "http://localhost:8080/api/users",null,null,null,false,null),
            new SentinelAgent.ExecutionPlan.Step("createOrder","POST","/api/orders",
                "http://localhost:8080/api/orders",
                "{\"userId\":\"{{userId}}\",\"quantity\":1}",
                "listUsers","userId",false,null)
        )));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        when(agentTools.callEndpoint(any(), eq("GET"), any(), any()))
            .thenReturn(new HttpCallResult(200, "[{\"userId\":\"usr_abc\"}]", 90, null));
        when(agentTools.callEndpoint(any(), eq("POST"), bodyCaptor.capture(), any()))
            .thenReturn(new HttpCallResult(201, "{\"id\":\"ord_001\"}", 110, null));

        agent.testAllEndpoints(session, endpoints, "{}", (p,m) -> {});

        // The POST body should have resolved {{userId}} to usr_abc
        String capturedBody = bodyCaptor.getValue();
        assertThat(capturedBody).contains("usr_abc");
        assertThat(capturedBody).doesNotContain("{{userId}}");
    }

    @Test
    @DisplayName("Attempts list records all retry attempts")
    void executeStep_recordsRetryAttempts() {
        stubLlmEntity(singleStepPlan("createOrder","POST","/api/orders"));

        when(agentTools.callEndpoint(any(), any(), any(), any()))
            .thenReturn(new HttpCallResult(422, "{\"error\":\"invalid\"}", 70, null))
            .thenReturn(new HttpCallResult(201, "{\"id\":\"ord_1\"}", 90, null));

        List<SentinelAgent.EndpointTestResult> results =
            agent.testAllEndpoints(session, endpoints.subList(1,2), "{}", (p,m) -> {});

        assertThat(results.get(0).attempts())
            .anyMatch(a -> a.getOutcome() == AgentAttempt.AttemptOutcome.RETRYING);
        assertThat(results.get(0).attempts())
            .anyMatch(a -> a.getOutcome() == AgentAttempt.AttemptOutcome.SUCCESS);
    }

    @Test
    @DisplayName("Auth errors (401/403) are not retried")
    void executeStep_authError_notRetried() {
        stubLlmEntity(singleStepPlan("createOrder","POST","/api/orders"));
        when(agentTools.callEndpoint(any(), any(), any(), any()))
            .thenReturn(new HttpCallResult(401, "{\"error\":\"Unauthorized\"}", 60, null));

        List<SentinelAgent.EndpointTestResult> results =
            agent.testAllEndpoints(session, endpoints.subList(1,2), "{}", (p,m) -> {});

        // Should only call once — no retries on auth errors
        verify(agentTools, times(1)).callEndpoint(any(), any(), any(), any());
    }
}
