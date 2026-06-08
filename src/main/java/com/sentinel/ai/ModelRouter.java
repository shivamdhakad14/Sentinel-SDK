package com.sentinel.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Routes each LLM call to the most cost-effective model for that task.
 *
 * On Groq FREE tier, different models have different strengths and limits:
 *
 * ┌─────────────────────────────────────────────┬──────────┬──────────┐
 * │ Task                                        │ Model    │ Why      │
 * ├─────────────────────────────────────────────┼──────────┼──────────┤
 * │ Plan (structured JSON, logic)               │ 70b      │ accuracy │
 * │ Correction (fix payloads, understand errors)│ 70b      │ accuracy │
 * │ Test gen (write code)                       │ 70b      │ quality  │
 * │ Drift enrichment (1-line analysis)          │ 8b       │ fast     │
 * │ Heal test (string substitution)             │ 8b       │ fast     │
 * └─────────────────────────────────────────────┴──────────┴──────────┘
 *
 * Groq free TPM limits: 8b=14,400/min, 70b=6,000/min
 * Adaptive fallback: if 70b is rate-limited → automatically falls back to 8b
 *
 * Token budget per task (after PromptCompressor):
 *   Plan:       ~800 in + ~400 out  = 1,200 tokens
 *   Correct:    ~200 in + ~200 out  = 400 tokens
 *   Test gen:   ~300 in + ~500 out  = 800 tokens (per endpoint)
 *   Drift:      ~150 in + ~100 out  = 250 tokens
 *   Heal:       ~400 in + ~400 out  = 800 tokens
 *
 * Typical 10-endpoint scan total: ~5,200 tokens → well within free tier
 */
@Component
@Slf4j
public class ModelRouter {

    @Value("${sentinel.ai.plan-model:llama-3.3-70b-versatile}")
    private String planModel;

    @Value("${sentinel.ai.generate-model:llama-3.3-70b-versatile}")
    private String generateModel;

    @Value("${sentinel.ai.fallback-model:llama-3.1-8b-instant}")
    private String fallbackModel;

    public enum Task {
        PLAN,         // Build execution plan — needs good structured output
        CORRECT,      // Fix payloads — needs to understand error messages
        TEST_GEN,     // Write JUnit code — needs code quality
        DRIFT,        // 1-line drift analysis — simple summarization
        HEAL          // String substitution in test code — simple
    }

    /**
     * Returns ChatClientRequestSpec options to override the default model
     * for a specific task type.
     *
     * Usage:
     *   chatClient.prompt()
     *     .user(prompt)
     *     .options(modelRouter.optionsFor(Task.PLAN))
     *     .call().entity(ExecutionPlan.class)
     */
    public OpenAiChatOptions optionsFor(Task task) {
        String model = switch (task) {
            case PLAN, CORRECT -> planModel;
            case TEST_GEN      -> generateModel;
            case DRIFT, HEAL             -> fallbackModel;   // simpler tasks → smaller faster model
        };

        int maxTokens = switch (task) {
            case PLAN    -> 600;   // JSON plan: compact
            case CORRECT -> 400;   // Corrections: small JSON
            case TEST_GEN -> 1500; // Code output: longer
            case DRIFT   -> 300;   // 1-liners
            case HEAL    -> 1200;  // Full class rewrite
        };

        log.debug("[ModelRouter] Task={} → model={} maxTokens={}", task, model, maxTokens);

        return OpenAiChatOptions.builder()
            .model(model)
            .temperature(task == Task.TEST_GEN ? 0.2 : 0.1)  // slight creativity for code
            .maxTokens(maxTokens)
            .build();
    }

    /**
     * Returns a per-task model name string (for logging/metrics).
     */
    public String modelFor(Task task) {
        return switch (task) {
            case PLAN, CORRECT -> planModel;
            case TEST_GEN      -> generateModel;
            case DRIFT, HEAL             -> fallbackModel;
        };
    }
}
