package com.sentinel.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

/**
 * Spring AI 1.0.x Tool methods — annotated with @Tool so the LLM
 * can invoke them via function calling. Pass instance to ChatClient via .tools(this).
 *
 * KEY CHANGE from 0.8.x:
 *   OLD: @Bean Function<Req,Resp> myTool() { ... } + .functions("myTool")
 *   NEW: @Tool on method + .tools(agentToolsInstance)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AgentTools {

    private final RestClient restClient;

    @Tool(description = """
        Execute an HTTP request against the target API.
        Use to probe endpoints and gather prerequisite IDs/data.
        For GET use null body. For POST/PUT/PATCH provide JSON body.
        Returns status code, response body, and response time.
        """)
    public HttpCallResult callEndpoint(
        @ToolParam(description = "Full URL e.g. http://localhost:8080/api/users") String url,
        @ToolParam(description = "HTTP method: GET POST PUT PATCH DELETE") String method,
        @ToolParam(description = "JSON body for POST/PUT/PATCH. Null for GET/DELETE.") String body,
        @ToolParam(description = "Bearer token (without 'Bearer ' prefix). Null if no auth.") String bearerToken
    ) {
        log.info("[Tool] {} {}", method, url);
        Instant start = Instant.now();
        try {
            var spec = restClient.method(HttpMethod.valueOf(method.toUpperCase())).uri(url);
            if (bearerToken != null && !bearerToken.isBlank())
                spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);

            ResponseEntity<String> resp = (body != null && !body.isBlank() && !method.equalsIgnoreCase("GET"))
                ? spec.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(String.class)
                : spec.retrieve().toEntity(String.class);

            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info("[Tool] <- {} {}ms", resp.getStatusCode().value(), ms);
            return new HttpCallResult(resp.getStatusCode().value(), cap(resp.getBody(), 3000), ms, null);

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            return new HttpCallResult(e.getStatusCode().value(), cap(e.getResponseBodyAsString(), 2000), ms, null);
        } catch (Exception e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            return new HttpCallResult(0, null, ms, "Connection error: " + e.getMessage());
        }
    }

    @Tool(description = "Check if target API is alive via Spring Boot Actuator /actuator/health. Call before scanning.")
    public HealthResult checkHealth(
        @ToolParam(description = "Actuator health URL e.g. http://localhost:8080/actuator/health") String actuatorUrl
    ) {
        try {
            String body = restClient.get().uri(actuatorUrl).retrieve().body(String.class);
            return new HealthResult(body != null && body.contains("\"UP\""), body);
        } catch (Exception e) {
            return new HealthResult(false, e.getMessage());
        }
    }

    public record HttpCallResult(int statusCode, String responseBody, long responseTimeMs, String errorMessage) {
        public boolean isSuccess()    { return statusCode >= 200 && statusCode < 300; }
        public boolean isClientError(){ return statusCode >= 400 && statusCode < 500; }
        public boolean isAuthError()  { return statusCode == 401 || statusCode == 403; }
    }
    public record HealthResult(boolean healthy, String details) {}

    private String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…[truncated]";
    }
}
