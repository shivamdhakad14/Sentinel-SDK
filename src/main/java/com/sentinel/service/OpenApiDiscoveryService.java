package com.sentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.*;

/**
 * Discovers all API endpoints from a target app's OpenAPI / Swagger spec.
 * Pure Java — zero LLM calls. Fast, deterministic.
 *
 * EndpointDescriptor is the canonical shared type used by SentinelAgent,
 * TestReportGenerator, ScanOrchestrationService, and SentinelSdk.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenApiDiscoveryService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${sentinel.target.openapi-path:/v3/api-docs}")
    private String openApiPath;

    public DiscoveryResult discover(String targetBaseUrl) {
        String specUrl = targetBaseUrl + openApiPath;
        log.info("[Discovery] Fetching OpenAPI spec: {}", specUrl);
        try {
            String specJson = restClient.get().uri(specUrl).retrieve().body(String.class);
            JsonNode spec = objectMapper.readTree(specJson);
            List<EndpointDescriptor> endpoints = parseEndpoints(spec, targetBaseUrl);
            log.info("[Discovery] Found {} endpoints across {} paths",
                endpoints.size(), spec.path("paths").size());
            return new DiscoveryResult(true, endpoints, specJson, null);
        } catch (RestClientException e) {
            log.error("[Discovery] Cannot reach target: {}", e.getMessage());
            return new DiscoveryResult(false, List.of(), null, "Cannot reach target API: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Discovery] Spec parse error: {}", e.getMessage());
            return new DiscoveryResult(false, List.of(), null, "OpenAPI parse error: " + e.getMessage());
        }
    }

    private List<EndpointDescriptor> parseEndpoints(JsonNode spec, String baseUrl) {
        List<EndpointDescriptor> endpoints = new ArrayList<>();
        JsonNode paths = spec.path("paths");

        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            for (String method : List.of("get", "post", "put", "patch", "delete", "head", "options")) {
                JsonNode op = pathItem.path(method);
                if (!op.isMissingNode()) {
                    endpoints.add(buildDescriptor(baseUrl, path, method.toUpperCase(), op, spec));
                    log.debug("[Discovery]  {} {}", method.toUpperCase(), path);
                }
            }
        });

        // GETs first so context can be populated before POSTs need it
        endpoints.sort(Comparator.comparingInt(e -> methodPriority(e.method())));
        return endpoints;
    }

    private EndpointDescriptor buildDescriptor(
            String baseUrl, String path, String method, JsonNode op, JsonNode spec) {

        String operationId = op.path("operationId").asText(method + "_" + path.replace("/", "_").replace("{", "").replace("}", ""));
        String summary     = op.path("summary").asText("");
        String description = op.path("description").asText("");

        List<ParameterDescriptor> params = new ArrayList<>();
        op.path("parameters").forEach(p -> params.add(new ParameterDescriptor(
            p.path("name").asText(), p.path("in").asText(),
            p.path("required").asBoolean(false),
            p.path("schema").path("type").asText("string"),
            p.path("description").asText(""))));

        String requestBodySchema = null;
        JsonNode rbContent = op.path("requestBody").path("content").path("application/json");
        if (!rbContent.isMissingNode()) {
            requestBodySchema = resolveRef(rbContent.path("schema"), spec).toString();
        }

        Map<String, String> responseSchemas = new LinkedHashMap<>();
        op.path("responses").fields().forEachRemaining(re -> {
            JsonNode s = re.getValue().path("content").path("application/json").path("schema");
            if (!s.isMissingNode()) responseSchemas.put(re.getKey(), resolveRef(s, spec).toString());
        });

        List<String> security = new ArrayList<>();
        op.path("security").forEach(sec -> sec.fieldNames().forEachRemaining(security::add));

        return new EndpointDescriptor(baseUrl, path, method, operationId, summary, description,
            params, requestBodySchema, responseSchemas, security);
    }

    private JsonNode resolveRef(JsonNode schema, JsonNode spec) {
        if (!schema.has("$ref")) return schema;
        String[] parts = schema.get("$ref").asText().replace("#/", "").split("/");
        JsonNode node = spec;
        for (String p : parts) node = node.path(p);
        return node;
    }

    private int methodPriority(String m) {
        return switch (m.toUpperCase()) {
            case "GET" -> 0; case "POST" -> 1; case "PUT" -> 2;
            case "PATCH" -> 3; case "DELETE" -> 4; default -> 5;
        };
    }

    // ── Public DTOs ───────────────────────────────────────────────────────────

    public record DiscoveryResult(
        boolean success,
        List<EndpointDescriptor> endpoints,
        String rawSpecJson,
        String errorMessage
    ) {}

    /** Canonical type shared across the entire codebase */
    public record EndpointDescriptor(
        String baseUrl,
        String path,
        String method,
        String operationId,
        String summary,
        String description,
        List<ParameterDescriptor> parameters,
        String requestBodySchema,
        Map<String, String> responseSchemas,
        List<String> securityRequirements
    ) {
        public String fullUrl() { return baseUrl + path; }
    }

    public record ParameterDescriptor(
        String name, String in, boolean required, String type, String description
    ) {}
}
