package com.sentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.service.OpenApiDiscoveryService.DiscoveryResult;
import com.sentinel.service.OpenApiDiscoveryService.EndpointDescriptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpenApiDiscoveryService — Spec Parsing (zero LLM calls)")
class OpenApiDiscoveryServiceTest {

    @Mock RestClient restClient;
    @Mock RestClient.RequestHeadersUriSpec uriSpec;
    @Mock RestClient.RequestHeadersSpec headersSpec;
    @Mock RestClient.ResponseSpec responseSpec;

    OpenApiDiscoveryService service;
    final ObjectMapper mapper = new ObjectMapper();

    static final String SPEC_4_ENDPOINTS = """
        {
          "openapi": "3.0.0",
          "paths": {
            "/api/users": {
              "get":  {"operationId":"listUsers","summary":"List users","responses":{"200":{}}},
              "post": {"operationId":"createUser","summary":"Create user",
                "requestBody":{"content":{"application/json":{"schema":{
                  "type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}},
                  "required":["name","email"]}}}},
                "responses":{"201":{}}}
            },
            "/api/users/{id}": {
              "get":    {"operationId":"getUser","parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],"responses":{"200":{}}},
              "delete": {"operationId":"deleteUser","responses":{"204":{}}}
            }
          }
        }""";

    @BeforeEach
    void setUp() {
        service = new OpenApiDiscoveryService(restClient, mapper);
        ReflectionTestUtils.setField(service, "openApiPath", "/v3/api-docs");
    }

    private void stubGet(String response) {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(response);
    }

    @Test @DisplayName("Discovers all 4 endpoints from spec")
    void discover_parsesAllEndpoints() {
        stubGet(SPEC_4_ENDPOINTS);
        DiscoveryResult result = service.discover("http://localhost:8080");
        assertThat(result.success()).isTrue();
        assertThat(result.endpoints()).hasSize(4);
    }

    @Test @DisplayName("GET endpoints sorted before POST in output")
    void discover_getEndpointsFirst() {
        stubGet(SPEC_4_ENDPOINTS);
        DiscoveryResult result = service.discover("http://localhost:8080");
        assertThat(result.endpoints().get(0).method()).isEqualTo("GET");
        assertThat(result.endpoints().get(1).method()).isEqualTo("GET");
    }

    @Test @DisplayName("Extracts path parameters correctly")
    void discover_pathParamsExtracted() {
        stubGet(SPEC_4_ENDPOINTS);
        DiscoveryResult result = service.discover("http://localhost:8080");
        EndpointDescriptor withParam = result.endpoints().stream()
            .filter(e -> e.path().contains("{id}")).findFirst().orElseThrow();
        assertThat(withParam.parameters()).anyMatch(p ->
            p.name().equals("id") && p.in().equals("path") && p.required());
    }

    @Test @DisplayName("Extracts requestBody schema for POST")
    void discover_requestBodySchemaExtracted() {
        stubGet(SPEC_4_ENDPOINTS);
        DiscoveryResult result = service.discover("http://localhost:8080");
        EndpointDescriptor post = result.endpoints().stream()
            .filter(e -> e.method().equals("POST")).findFirst().orElseThrow();
        assertThat(post.requestBodySchema()).contains("name").contains("email");
    }

    @Test @DisplayName("Returns failure for unreachable target")
    void discover_unreachable_returnsFailure() {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(new RestClientException("Connection refused"));
        DiscoveryResult result = service.discover("http://unreachable:9999");
        assertThat(result.success()).isFalse();
        assertThat(result.endpoints()).isEmpty();
        assertThat(result.errorMessage()).contains("Cannot reach");
    }

    @Test @DisplayName("Preserves raw spec JSON for drift comparison")
    void discover_preservesRawSpec() {
        stubGet(SPEC_4_ENDPOINTS);
        DiscoveryResult result = service.discover("http://localhost:8080");
        assertThat(result.rawSpecJson()).isEqualTo(SPEC_4_ENDPOINTS);
    }

    @Test @DisplayName("fullUrl() = baseUrl + path")
    void endpointDescriptor_fullUrl() {
        stubGet(SPEC_4_ENDPOINTS);
        DiscoveryResult result = service.discover("http://localhost:8080");
        result.endpoints().forEach(e ->
            assertThat(e.fullUrl()).startsWith("http://localhost:8080"));
    }

    @Test @DisplayName("operationId falls back to method+path when not in spec")
    void discover_operationIdFallback() {
        String specNoOpId = "{\"paths\":{\"/api/test\":{\"get\":{\"responses\":{\"200\":{}}}}}}";
        stubGet(specNoOpId);
        DiscoveryResult result = service.discover("http://localhost:8080");
        assertThat(result.endpoints().get(0).operationId()).isNotBlank();
    }
}
