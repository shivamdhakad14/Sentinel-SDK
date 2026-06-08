package com.sentinel.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive wrapper around Spring AI ChatClient.
 *
 * Handles three failure modes automatically — zero manual intervention:
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  429 Too Many Requests (rate limit hit)                         │
 * │                                                                 │
 * │  Step 1: Read Retry-After header, wait that exact duration      │
 * │  Step 2: Exponential backoff if no header (2s → 4s → 8s → 16s) │
 * │  Step 3: Trim prompt by N chars (payload-reduction-steps)       │
 * │          → removes spec details, truncates error bodies         │
 * │  Step 4: Switch to fallback model (e.g. gemma → llama)          │
 * │  Step 5: If all retries exhausted → throw with clear message    │
 * │                                                                 │
 * │  503 / 500 (provider outage)                                    │
 * │  → Switch to fallback model immediately                         │
 * │                                                                 │
 * │  Context window exceeded                                        │
 * │  → Progressively cut prompt to fit                              │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Usage — same call-site as regular ChatClient:
 *   adaptiveChatClient.call(chatClient, myPrompt)            → String
 *   adaptiveChatClient.callEntity(chatClient, myPrompt, T.class) → T
 */
@Component
@Slf4j
public class AdaptiveChatClient {

    @Value("${sentinel.ai.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${sentinel.ai.rate-limit.initial-retry-ms:2000}")
    private long initialRetryMs;

    @Value("${sentinel.ai.rate-limit.max-retries:4}")
    private int maxRetries;

    @Value("${sentinel.ai.rate-limit.backoff-multiplier:2.0}")
    private double backoffMultiplier;

    @Value("${sentinel.ai.rate-limit.payload-reduction-steps:3000,2000,1000,500}")
    private String payloadReductionStepsStr;

    @Value("${sentinel.ai.fallback-model:llama-3.1-8b-instant}")
    private String fallbackModel;

    // Metrics for dashboard
    private final AtomicInteger totalRateLimitHits  = new AtomicInteger(0);
    private final AtomicInteger totalFallbackSwitches = new AtomicInteger(0);
    private final AtomicLong    totalWaitMs          = new AtomicLong(0);

    /**
     * Call LLM and return raw string. Adapts automatically on 429.
     */
    public String call(ChatClient client, String prompt) {
        return call(client, prompt, String.class);
    }

    /**
     * Call LLM and return structured object. Adapts automatically on 429.
     */
    public <T> T callEntity(ChatClient client, String prompt, Class<T> type) {
        return call(client, prompt, type);
    }

    @SuppressWarnings("unchecked")
    private <T> T call(ChatClient client, String prompt, Class<T> type) {
        if (!rateLimitEnabled) {
            return execute(client, prompt, type, false);
        }

        int[] reductions = parseReductions(payloadReductionStepsStr);
        long waitMs     = initialRetryMs;
        String currentPrompt = prompt;
        boolean usingFallback = false;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                T result = execute(client, currentPrompt, type, usingFallback);
                if (attempt > 0) {
                    log.info("[AdaptiveAI] Recovered after {} attempt(s), fallback={}", attempt, usingFallback);
                }
                return result;

            } catch (RateLimitException e) {
                totalRateLimitHits.incrementAndGet();
                long sleepMs = e.retryAfterMs > 0 ? e.retryAfterMs : waitMs;
                log.warn("[AdaptiveAI] 429 hit (attempt {}/{}). Waiting {}ms, then reducing prompt by {} chars",
                    attempt + 1, maxRetries, sleepMs,
                    attempt < reductions.length ? reductions[attempt] : "no further reduction");

                totalWaitMs.addAndGet(sleepMs);
                sleep(sleepMs);
                waitMs = (long)(waitMs * backoffMultiplier);

                // Trim the prompt progressively
                if (attempt < reductions.length) {
                    int cut = reductions[attempt];
                    currentPrompt = trimPrompt(currentPrompt, cut);
                    log.debug("[AdaptiveAI] Prompt trimmed to {} chars", currentPrompt.length());
                }

                // Switch to fallback model on 3rd attempt
                if (attempt == 2 && !usingFallback) {
                    usingFallback = true;
                    totalFallbackSwitches.incrementAndGet();
                    log.warn("[AdaptiveAI] Switching to fallback model: {}", fallbackModel);
                }

            } catch (ContextWindowException e) {
                // Hard limit — cut prompt more aggressively
                currentPrompt = trimPrompt(currentPrompt, currentPrompt.length() / 3);
                log.warn("[AdaptiveAI] Context window exceeded. Prompt cut to {} chars", currentPrompt.length());

            } catch (ProviderUnavailableException e) {
                if (!usingFallback) {
                    usingFallback = true;
                    totalFallbackSwitches.incrementAndGet();
                    log.warn("[AdaptiveAI] Provider 5xx — switching to fallback model: {}", fallbackModel);
                    sleep(initialRetryMs);
                } else {
                    throw new RuntimeException("Both primary and fallback model unavailable: " + e.getMessage());
                }
            }
        }

        throw new RuntimeException(
            "LLM call failed after " + maxRetries + " retries. Rate limit exhausted. " +
            "Waited " + totalWaitMs.get() + "ms total. Consider reducing scan frequency.");
    }

    @SuppressWarnings("unchecked")
    private <T> T execute(ChatClient client, String prompt, Class<T> type, boolean useFallback) {
        try {
            ChatClientRequestSpec spec = client.prompt().user(prompt);

            // Route to fallback model by modifying the options
            if (useFallback) {
                spec = spec.options(
                    org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(fallbackModel)
                        .temperature(0.1)
                        .build()
                );
            }

            if (type == String.class) {
                return (T) spec.call().content();
            } else {
                return spec.call().entity(type);
            }

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("429") || msg.contains("rate_limit") || msg.contains("rate limit")) {
                long retryAfter = parseRetryAfter(msg);
                throw new RateLimitException("Rate limit hit", retryAfter);
            }
            if (msg.contains("context_length") || msg.contains("context window") || msg.contains("too many tokens")) {
                throw new ContextWindowException("Context window exceeded: " + msg);
            }
            if (msg.contains("503") || msg.contains("502") || msg.contains("unavailable")) {
                throw new ProviderUnavailableException("Provider unavailable: " + msg);
            }
            throw e;
        }
    }

    /**
     * Smart prompt trimming — cuts from the MIDDLE (spec/context section),
     * preserving the instruction header and output schema at top and bottom.
     *
     * This preserves task understanding while reducing payload size.
     */
    private String trimPrompt(String prompt, int charsToRemove) {
        if (prompt.length() <= charsToRemove) {
            // Desperate: keep first 500 and last 300 chars (task + output format)
            if (prompt.length() > 800) {
                return prompt.substring(0, 500) + "\n…[content trimmed for rate limit]…\n" +
                    prompt.substring(prompt.length() - 300);
            }
            return prompt;
        }

        // Find the midpoint of the prompt (likely spec/context block)
        int mid = prompt.length() / 2;
        int halfCut = charsToRemove / 2;
        int start = Math.max(0, mid - halfCut);
        int end   = Math.min(prompt.length(), mid + halfCut);

        return prompt.substring(0, start) +
            "\n…[" + charsToRemove + " chars trimmed]…\n" +
            prompt.substring(end);
    }

    private long parseRetryAfter(String errorMessage) {
        // Try to extract "retry after X seconds" from error message
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*(?:second|sec|s)")
                .matcher(errorMessage);
            if (m.find()) return (long)(Double.parseDouble(m.group(1)) * 1000);
        } catch (Exception ignored) {}
        return 0; // will use exponential backoff
    }

    private int[] parseReductions(String csv) {
        try {
            String[] parts = csv.split(",");
            int[] result = new int[parts.length];
            for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i].trim());
            return result;
        } catch (Exception e) {
            return new int[]{3000, 2000, 1000, 500};
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    // ── Metrics (exposed via Actuator) ────────────────────────────────────────

    public int getRateLimitHits()    { return totalRateLimitHits.get(); }
    public int getFallbackSwitches() { return totalFallbackSwitches.get(); }
    public long getTotalWaitMs()     { return totalWaitMs.get(); }

    public void resetMetrics() {
        totalRateLimitHits.set(0);
        totalFallbackSwitches.set(0);
        totalWaitMs.set(0);
    }

    // ── Typed exceptions for clean routing ────────────────────────────────────

    private static class RateLimitException extends RuntimeException {
        final long retryAfterMs;
        RateLimitException(String msg, long retryAfterMs) {
            super(msg);
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static class ContextWindowException extends RuntimeException {
        ContextWindowException(String msg) { super(msg); }
    }

    private static class ProviderUnavailableException extends RuntimeException {
        ProviderUnavailableException(String msg) { super(msg); }
    }
}
