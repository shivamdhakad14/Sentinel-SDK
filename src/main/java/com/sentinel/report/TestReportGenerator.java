package com.sentinel.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.agent.SentinelAgent.EndpointTestResult;
import com.sentinel.ai.AdaptiveChatClient;
import com.sentinel.ai.ModelRouter;
import com.sentinel.ai.ModelRouter.Task;
import com.sentinel.ai.PromptCompressor;
import com.sentinel.ai.PromptCompressor.TestEndpointSummary;
import com.sentinel.model.AgentAttempt;
import com.sentinel.model.GeneratedTest;
import com.sentinel.model.ScanSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test report generator — token-optimized for Groq free tier.
 *
 * Strategy: generate tests PER ENDPOINT (not batch) so each call fits
 * within Groq's 6,000 TPM limit with room to spare.
 *
 * Per-endpoint test gen: ~150 in + ~400 out = ~550 tokens
 * 10 endpoints × 550 = 5,500 tokens → fits comfortably in 6,000 TPM
 *
 * For large-context models (llama-3.1-8b-instant, 131k context),
 * batching all endpoints in one call is also supported.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TestReportGenerator {

    private final ChatClient chatClient;
    private final AdaptiveChatClient adaptiveClient;
    private final ModelRouter modelRouter;
    private final PromptCompressor compressor;
    private final ObjectMapper objectMapper;

    @Value("${sentinel.report.output-dir:./generated-tests}")
    private String outputDir;

    @Value("${sentinel.report.junit-package:com.generated.tests}")
    private String pkg;

    @Value("${sentinel.ai.tokens.test-gen-max:3000}")
    private int testGenMaxTokens;

    // ── JUnit 5 generation — one LLM call per endpoint ────────────────────────

    public List<GeneratedTest> generateAllJUnit5Tests(
            List<EndpointTestResult> results, ScanSession session) {

        List<EndpointTestResult> successes = results.stream().filter(EndpointTestResult::success).toList();
        if (successes.isEmpty()) return List.of();

        log.info("[ReportGen] Generating {} JUnit tests (1 LLM call each, ~550 tokens each)",
            successes.size());

        List<GeneratedTest> tests = new ArrayList<>();
        for (EndpointTestResult r : successes) {
            GeneratedTest test = generateSingleTest(r, session);
            if (test != null) tests.add(test);
        }

        log.info("[ReportGen] Generated {} test classes", tests.size());
        return tests;
    }

    private GeneratedTest generateSingleTest(EndpointTestResult r, ScanSession session) {
        AgentAttempt ok = r.attempts().stream()
            .filter(a -> a.getOutcome() == AgentAttempt.AttemptOutcome.SUCCESS)
            .findFirst().orElse(r.attempts().getLast());

        String className = buildClassName(r.endpoint().path(), r.endpoint().method());

        // Compressed prompt: ~150 tokens input
        String prompt = compressor.testGenPromptSingle(
            r.endpoint().method(), r.endpoint().path(), className,
            pkg, session.getTargetBaseUrl(),
            ok.getRequestPayload(), ok.getResponseStatusCode(), ok.getResponseBody()
        );

        log.debug("[ReportGen] Test gen for {}: {} chars ≈ {} tokens",
            className, prompt.length(), prompt.length() / 4);

        try {
            // LLM call 3a, 3b, ... (one per successful endpoint)
            String code = adaptiveClient.call(clientFor(Task.TEST_GEN), prompt);
            code = code.replaceAll("(?s)```java\\s*|```\\s*", "").trim();
            if (code.isBlank()) return null;

            saveFile(className + ".java", code);

            return GeneratedTest.builder()
                .scanSession(session).testType(GeneratedTest.TestType.JUNIT5)
                .className(className).packageName(pkg).content(code)
                .targetEndpoint(r.endpoint().method() + " " + r.endpoint().path())
                .coverageScore(coverageScore(r))
                .selfHealingApplied(false)
                .healingStatus(GeneratedTest.HealingStatus.NOT_NEEDED)
                .build();

        } catch (Exception e) {
            log.error("[ReportGen] Test gen failed for {}: {}", className, e.getMessage());
            return null;
        }
    }

    // ── Postman collection — zero LLM calls ──────────────────────────────────

    public GeneratedTest generatePostmanCollection(List<EndpointTestResult> results, ScanSession session) {
        log.info("[ReportGen] Building Postman collection (0 LLM calls)");
        ObjectNode collection = objectMapper.createObjectNode();
        ObjectNode info = collection.putObject("info");
        info.put("name", session.getTargetName() + " — Sentinel");
        info.put("description", "Generated " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        info.put("_postman_id", UUID.randomUUID().toString());

        var baseUrlVar = collection.putArray("variable").addObject();
        baseUrlVar.put("key", "baseUrl"); baseUrlVar.put("value", session.getTargetBaseUrl());

        ArrayNode items = collection.putArray("item");
        results.stream().filter(EndpointTestResult::success).forEach(r -> {
            AgentAttempt ok = r.attempts().stream()
                .filter(a -> a.getOutcome() == AgentAttempt.AttemptOutcome.SUCCESS)
                .findFirst().orElse(null);
            if (ok != null) items.add(buildPostmanItem(r, ok));
        });

        String json;
        try { json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(collection); }
        catch (Exception e) { json = "{}"; }

        String fname = session.getTargetName().replaceAll("[^a-zA-Z0-9]","_") + "_collection.json";
        saveFile(fname, json);

        return GeneratedTest.builder()
            .scanSession(session).testType(GeneratedTest.TestType.POSTMAN_COLLECTION)
            .className(fname).content(json).targetEndpoint("ALL")
            .coverageScore(calcCoverage(results))
            .healingStatus(GeneratedTest.HealingStatus.NOT_NEEDED).build();
    }

    // ── Self-healing: compressed heal prompt ─────────────────────────────────

    public String healTest(String existingCode, String driftDesc, String oldField, String newField) {
        log.info("[ReportGen] Healing test: {} → {} (~400 tokens)", oldField, newField);
        String prompt = compressor.healTestPrompt(existingCode, oldField, newField);
        String healed = adaptiveClient.call(clientFor(Task.HEAL), prompt);
        return healed.replaceAll("(?s)```java\\s*|```\\s*", "").trim();
    }

    // ── Drift enrichment: compressed prompt ──────────────────────────────────

    public void enrichDriftEvents(List<com.sentinel.model.SchemaDriftEvent> events) {
        if (events.isEmpty()) return;
        List<PromptCompressor.DriftSummary> summaries = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            var e = events.get(i);
            summaries.add(new PromptCompressor.DriftSummary(
                i, e.getDriftType().name(), e.getFieldPath() != null ? e.getFieldPath() : "",
                e.getPreviousValue() != null ? e.getPreviousValue() : "",
                e.getNewValue() != null ? e.getNewValue() : ""));
        }

        String prompt = compressor.driftEnrichmentPrompt(summaries);
        log.debug("[ReportGen] Drift enrichment: {} chars ≈ {} tokens", prompt.length(), prompt.length()/4);

        try {
            String raw = adaptiveClient.call(clientFor(Task.DRIFT), prompt);
            var arr = objectMapper.readTree(raw.replaceAll("(?s)```json\\s*|```\\s*","").trim());
            arr.forEach(node -> {
                int idx = node.path("i").asInt(-1);
                if (idx >= 0 && idx < events.size()) {
                    events.get(idx).setAiAnalysis(node.path("a").asText());
                    events.get(idx).setSuggestedFix(node.path("f").asText());
                }
            });
        } catch (Exception e) {
            log.warn("[ReportGen] Drift enrichment failed gracefully: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode buildPostmanItem(EndpointTestResult r, AgentAttempt ok) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("name", r.endpoint().method() + " " + r.endpoint().path());
        ObjectNode request = item.putObject("request");
        request.put("method", r.endpoint().method());
        request.putObject("url").put("raw", "{{baseUrl}}" + r.endpoint().path());
        request.putArray("header").addObject().put("key","Content-Type").put("value","application/json");
        if (ok.getRequestPayload() != null && !ok.getRequestPayload().isBlank()) {
            request.putObject("body").put("mode","raw").put("raw", ok.getRequestPayload())
                .putObject("options").putObject("raw").put("language","json");
        }
        return item;
    }

    private String buildClassName(String path, String method) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/"))
            if (!part.isBlank() && !part.startsWith("{"))
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        String m = method.charAt(0) + method.substring(1).toLowerCase();
        return sb + m + "Test";
    }

    private int coverageScore(EndpointTestResult r) {
        return Math.min(70 + r.attempts().size() * 8, 100);
    }

    private int calcCoverage(List<EndpointTestResult> results) {
        if (results.isEmpty()) return 0;
        return (int)(results.stream().filter(EndpointTestResult::success).count() * 100 / results.size());
    }

    private void saveFile(String name, String content) {
        try {
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), content);
        } catch (IOException e) {
            log.warn("[ReportGen] Could not save {}: {}", name, e.getMessage());
        }
    }

    private ChatClient clientFor(Task task) {
        return chatClient.mutate()
            .defaultOptions(modelRouter.optionsFor(task))
            .build();
    }
}
