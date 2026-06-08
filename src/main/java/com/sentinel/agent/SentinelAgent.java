package com.sentinel.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.ai.AdaptiveChatClient;
import com.sentinel.ai.ModelRouter;
import com.sentinel.ai.ModelRouter.Task;
import com.sentinel.ai.PromptCompressor;
import com.sentinel.ai.PromptCompressor.FailedEndpoint;
import com.sentinel.model.AgentAttempt;
import com.sentinel.model.ScanSession;
import com.sentinel.service.OpenApiDiscoveryService.EndpointDescriptor;
import com.sentinel.tools.AgentTools;
import com.sentinel.tools.AgentTools.HttpCallResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Sentinel Agent v2.1 — Groq-powered, token-optimized, adaptive.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  LLM CALL BUDGET (after optimizations)                         │
 * │                                                                 │
 * │  Call 1: PLAN   ~800  tokens  (compressed spec + endpoints)    │
 * │  Call 2: CORRECT ~400 tokens  (only if failures exist)         │
 * │  HTTP execution: ZERO tokens  (pure RestClient)                │
 * │                                                                 │
 * │  Total per scan: ~1,200 tokens avg (was 15,000+ in v1)        │
 * │  Groq free tier: 500k TPD → ~416 full scans/day FREE           │
 * └─────────────────────────────────────────────────────────────────┘
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SentinelAgent {

    private final ChatClient chatClient;
    private final AdaptiveChatClient adaptiveClient;
    private final ModelRouter modelRouter;
    private final PromptCompressor compressor;
    private final AgentTools agentTools;
    private final ObjectMapper objectMapper;

    @Value("${sentinel.agent.max-retries:3}")
    private int maxRetries;

    @FunctionalInterface
    public interface ProgressCallback {
        void update(String phase, String message);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════

    public List<EndpointTestResult> testAllEndpoints(
            ScanSession session,
            List<EndpointDescriptor> endpoints,
            String rawSpecJson,
            ProgressCallback onProgress) {

        log.info("[Agent] Batch scan: {} endpoints | provider: Groq/OpenAI adaptive", endpoints.size());

        // ── CALL 1: Compressed plan prompt (~800 tokens) ──────────────────────
        onProgress.update("PLANNING", "Building execution plan (" + endpoints.size() + " endpoints)…");
        String planPrompt = compressor.planPrompt(endpoints, rawSpecJson);
        logTokenEstimate("PLAN", planPrompt);

        ExecutionPlan plan = adaptiveClient.callEntity(
            clientWithOptions(Task.PLAN), planPrompt, ExecutionPlan.class);

        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            log.warn("[Agent] Empty plan from LLM — using dependency-free fallback");
            plan = buildFallbackPlan(endpoints);
        }
        log.info("[Agent] Plan: {} steps, {} with deps",
            plan.steps().size(), plan.steps().stream().filter(s -> s.dep() != null).count());

        // ── ZERO LLM: Pure Java HTTP execution ───────────────────────────────
        Map<String, Object> context = new LinkedHashMap<>();
        List<EndpointTestResult> results = new ArrayList<>();
        List<FailedEndpoint> failures = new ArrayList<>();

        for (ExecutionPlan.Step step : plan.steps()) {
            onProgress.update("TESTING", step.m() + " " + step.p());
            EndpointTestResult result = executeStep(step, session, context, endpoints);
            results.add(result);

            if (result.success()) {
                extractContext(result.lastSuccessBody(), step.p(), context);
            } else if (!result.skipped()) {
                AgentAttempt last = result.attempts().getLast();
                failures.add(new FailedEndpoint(
                    step.id(), last.getResponseStatusCode(),
                    last.getRequestPayload(), last.getResponseBody()));
            }
        }

        // ── CALL 2 (conditional): Compressed batch correction (~400 tokens) ──
        if (!failures.isEmpty()) {
            onProgress.update("CORRECTING", "Self-correcting " + failures.size() + " failure(s)…");
            String corrPrompt = compressor.correctionPrompt(failures, context);
            logTokenEstimate("CORRECT", corrPrompt);

            String corrRaw = adaptiveClient.call(clientWithOptions(Task.CORRECT), corrPrompt);
            Map<String, String> corrections = null;
            try {
                String corrCleaned = corrRaw.replaceAll("(?s)```json\\s*|```\\s*", "").trim();
                corrections = objectMapper.readValue(corrCleaned, new TypeReference<Map<String, String>>() {});
            } catch (Exception parseEx) {
                log.warn("[Agent] Correction response parse failed: {}", parseEx.getMessage());
            }

            if (corrections != null) {
                for (FailedEndpoint fa : failures) {
                    String fix = corrections.get(fa.operationId());
                    if (fix != null && !fix.isBlank() && !fix.equals("null")) {
                        EndpointTestResult retried = retryWithPayload(
                            findStep(plan, fa.operationId()), session, fix, context, endpoints);
                        int idx = indexOfResult(results, fa.operationId());
                        if (idx >= 0) {
                            results.set(idx, retried);
                            if (retried.success()) extractContext(retried.lastSuccessBody(), retried.endpoint().path(), context);
                        }
                    }
                }
            }
        }

        long passed = results.stream().filter(EndpointTestResult::success).count();
        log.info("[Agent] Done: {}/{} passed | ~{} tokens used | rate limit hits: {}",
            passed, results.size(), estimateTotalTokens(planPrompt, failures),
            adaptiveClient.getRateLimitHits());

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CALL 1 — PLAN: Compressed spec → execution plan
    // ═══════════════════════════════════════════════════════════════════════════

    /** No-LLM fallback when plan call fails */
    private ExecutionPlan buildFallbackPlan(List<EndpointDescriptor> endpoints) {
        List<ExecutionPlan.Step> steps = endpoints.stream()
            .sorted(Comparator.comparingInt(e -> methodPriority(e.method())))
            .map(e -> new ExecutionPlan.Step(
                e.operationId(), e.method(), e.path(), e.fullUrl(),
                null, null, null, false, null))
            .toList();
        return new ExecutionPlan(steps);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ZERO LLM: Pure Java HTTP execution
    // ═══════════════════════════════════════════════════════════════════════════

    private EndpointTestResult executeStep(ExecutionPlan.Step step, ScanSession session,
            Map<String, Object> context, List<EndpointDescriptor> all) {

        EndpointDescriptor descriptor = findDescriptor(step, all);

        if (step.skip()) {
            AgentAttempt skipped = buildAttempt(session, step, 1)
                .agentThought("Skipped: " + step.why())
                .outcome(AgentAttempt.AttemptOutcome.SKIPPED).build();
            return EndpointTestResult.skipped(descriptor, skipped);
        }

        String url     = resolveUrl(step.url(), context);
        String payload = resolvePayload(step.body(), context);
        List<AgentAttempt> attempts = new ArrayList<>();

        for (int n = 1; n <= maxRetries; n++) {
            HttpCallResult http = agentTools.callEndpoint(url, step.m(), payload, null);

            AgentAttempt attempt = buildAttempt(session, step, n)
                .requestUrl(url).requestPayload(payload)
                .responseStatusCode(http.statusCode()).responseBody(http.responseBody())
                .responseTimeMs(http.responseTimeMs())
                .agentThought(n == 1 ? "Executing from AI plan" : "Retry #" + n)
                .outcome(outcome(http, n)).build();
            attempts.add(attempt);

            if (http.isSuccess()) return EndpointTestResult.success(descriptor, attempts, payload, http.statusCode());
            if (http.isAuthError()) break;
        }

        return EndpointTestResult.failed(descriptor, attempts, payload);
    }

    private EndpointTestResult retryWithPayload(ExecutionPlan.Step step, ScanSession session,
            String corrected, Map<String, Object> context, List<EndpointDescriptor> all) {

        EndpointDescriptor descriptor = findDescriptor(step, all);
        String url  = resolveUrl(step.url(), context);
        String body = resolvePayload(corrected, context);

        HttpCallResult http = agentTools.callEndpoint(url, step.m(), body, null);
        AgentAttempt attempt = buildAttempt(session, step, 99)
            .requestUrl(url).requestPayload(body)
            .responseStatusCode(http.statusCode()).responseBody(http.responseBody())
            .responseTimeMs(http.responseTimeMs())
            .agentThought("AI batch-corrected retry")
            .correctionReason("Adaptive correction applied")
            .outcome(http.isSuccess() ? AgentAttempt.AttemptOutcome.SUCCESS : AgentAttempt.AttemptOutcome.FAILED)
            .build();

        return http.isSuccess()
            ? EndpointTestResult.success(descriptor, List.of(attempt), body, http.statusCode())
            : EndpointTestResult.failed(descriptor, List.of(attempt), body);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONTEXT EXTRACTION — zero LLM
    // ═══════════════════════════════════════════════════════════════════════════

    private void extractContext(String responseBody, String path, Map<String, Object> context) {
        if (responseBody == null || responseBody.isBlank()) return;
        try {
            var root = objectMapper.readTree(responseBody);
            var target = root;
            if (root.isObject()) {
                for (String w : List.of("data","content","items","results","list","records")) {
                    var n = root.path(w);
                    if (n.isArray() && !n.isEmpty()) { target = n.get(0); break; }
                    if (n.isObject()) { target = n; break; }
                }
            } else if (root.isArray() && !root.isEmpty()) {
                target = root.get(0);
            }
            for (String field : List.of("id","uuid","userId","user_id","customerId","customer_uuid",
                    "productId","product_id","orderId","order_id","groupId","group_id",
                    "sessionId","token","accessToken","memberId","member_id")) {
                var val = target.path(field);
                if (!val.isMissingNode() && !val.isNull() && !val.asText().isBlank()) {
                    context.put(field, val.asText());
                    String camel = toCamel(field);
                    if (!camel.equals(field)) context.put(camel, val.asText());
                }
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private String resolvePayload(String payload, Map<String, Object> ctx) {
        if (payload == null) return null;
        String out = payload;
        for (var e : ctx.entrySet()) out = out.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
        return out;
    }

    private String resolveUrl(String url, Map<String, Object> ctx) {
        if (url == null) return url;
        String out = url;
        for (var e : ctx.entrySet()) out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        if (out.contains("{id}") && ctx.containsKey("id")) out = out.replace("{id}", String.valueOf(ctx.get("id")));
        return out;
    }

    private AgentAttempt.AgentAttemptBuilder buildAttempt(ScanSession s, ExecutionPlan.Step step, int n) {
        return AgentAttempt.builder().scanSession(s).httpMethod(step.m()).endpointPath(step.p()).attemptNumber(n);
    }

    private AgentAttempt.AttemptOutcome outcome(HttpCallResult http, int n) {
        if (http.isSuccess())   return AgentAttempt.AttemptOutcome.SUCCESS;
        if (http.isAuthError()) return AgentAttempt.AttemptOutcome.SKIPPED;
        if (n >= maxRetries)    return AgentAttempt.AttemptOutcome.FAILED;
        return AgentAttempt.AttemptOutcome.RETRYING;
    }

    private EndpointDescriptor findDescriptor(ExecutionPlan.Step step, List<EndpointDescriptor> all) {
        return all.stream()
            .filter(e -> e.operationId().equals(step.id()) ||
                        (e.method().equals(step.m()) && e.path().equals(step.p())))
            .findFirst()
            .orElse(new EndpointDescriptor(
                (step.url().endsWith(step.p())
                    ? step.url().substring(0, step.url().length() - step.p().length())
                    : step.url()), step.p(), step.m(), step.id(),
                "","",List.of(), step.body(), Map.of(), List.of()));
    }

    private ExecutionPlan.Step findStep(ExecutionPlan plan, String operationId) {
        return plan.steps().stream().filter(s -> s.id().equals(operationId)).findFirst()
            .orElse(new ExecutionPlan.Step(operationId,"GET","/unknown","http://unknown",null,null,null,false,null));
    }

    private int indexOfResult(List<EndpointTestResult> results, String opId) {
        for (int i = 0; i < results.size(); i++)
            if (results.get(i).endpoint().operationId().equals(opId)) return i;
        return -1;
    }

    private int methodPriority(String m) {
        return switch (m.toUpperCase()) {
            case "GET" -> 0; case "POST" -> 1; case "PUT" -> 2;
            case "PATCH" -> 3; case "DELETE" -> 4; default -> 5;
        };
    }

    private String toCamel(String snake) {
        if (!snake.contains("_")) return snake;
        StringBuilder sb = new StringBuilder(); boolean up = false;
        for (char c : snake.toCharArray()) { if (c=='_') up=true; else { sb.append(up?Character.toUpperCase(c):c); up=false; } }
        return sb.toString();
    }

    /**
     * Returns ChatClient pre-configured with model + token options for this task.
     * ModelRouter selects the right Groq model (8b vs 70b) based on task complexity.
     */
    private ChatClient clientWithOptions(Task task) {
        return chatClient.mutate()
            .defaultOptions(modelRouter.optionsFor(task))
            .build();
    }

    private void logTokenEstimate(String label, String prompt) {
        int est = prompt.length() / 4;
        log.debug("[Agent] {} prompt: {} chars ≈ {} tokens", label, prompt.length(), est);
    }

    private int estimateTotalTokens(String planPrompt, List<FailedEndpoint> failures) {
        int planTokens = planPrompt.length() / 4;
        int corrTokens = failures.isEmpty() ? 0 : 400;
        return planTokens + corrTokens;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC DTOs — compact field names to match compressed JSON output
    // ═══════════════════════════════════════════════════════════════════════════

    /** Short-field names match the compressed output schema the LLM returns */
    public record ExecutionPlan(List<Step> steps) {
        public record Step(
            String id,   // operationId
            String m,    // method
            String p,    // path
            String url,  // fullUrl
            String body, // payload (may contain {{vars}})
            String dep,  // dependsOn operationId
            String key,  // contextKey to extract from dep response
            boolean skip,
            String why   // skipReason
        ) {}
    }

    public static final class EndpointTestResult {
        private final EndpointDescriptor endpoint;
        private final List<AgentAttempt> attempts;
        private final boolean success;
        private final boolean skipped;
        private final String successfulPayload;
        private final int finalStatusCode;

        private EndpointTestResult(EndpointDescriptor e, List<AgentAttempt> a,
                boolean success, boolean skipped, String payload, int code) {
            this.endpoint=e; this.attempts=List.copyOf(a);
            this.success=success; this.skipped=skipped;
            this.successfulPayload=payload; this.finalStatusCode=code;
        }
        public static EndpointTestResult success(EndpointDescriptor e, List<AgentAttempt> a, String p, int c) { return new EndpointTestResult(e,a,true,false,p,c); }
        public static EndpointTestResult failed(EndpointDescriptor e, List<AgentAttempt> a, String p) {
            int c = a.isEmpty() ? 0 : a.getLast().getResponseStatusCode();
            return new EndpointTestResult(e,a,false,false,p,c);
        }
        public static EndpointTestResult skipped(EndpointDescriptor e, AgentAttempt a) { return new EndpointTestResult(e,List.of(a),false,true,null,0); }
        public EndpointDescriptor endpoint()  { return endpoint; }
        public List<AgentAttempt> attempts()  { return attempts; }
        public boolean success()              { return success; }
        public boolean skipped()              { return skipped; }
        public String successfulPayload()     { return successfulPayload; }
        public int finalStatusCode()          { return finalStatusCode; }
        public String lastSuccessBody() {
            return attempts.stream()
                .filter(a -> a.getOutcome()==AgentAttempt.AttemptOutcome.SUCCESS)
                .map(AgentAttempt::getResponseBody)
                .filter(Objects::nonNull).findFirst().orElse(null);
        }
    }
}
