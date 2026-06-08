package com.sentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.model.GeneratedTest;
import com.sentinel.model.SchemaDriftEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Submits auto-generated self-healing PRs to GitHub.
 *
 * The enterprise "Self-Healing Regression" feature:
 * When schema drift is detected, Sentinel:
 * 1. Rewrites the broken test(s)
 * 2. Creates a feature branch
 * 3. Commits the fixed tests
 * 4. Opens a PR with a detailed description of the drift + fixes
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GitHubPrService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${sentinel.github.token:}")
    private String githubToken;

    @Value("${sentinel.github.owner:}")
    private String owner;

    @Value("${sentinel.github.repo:}")
    private String repo;

    @Value("${sentinel.github.base-branch:main}")
    private String baseBranch;

    private static final String GITHUB_API = "https://api.github.com";

    /**
     * Create a self-healing PR for a set of drift events and their fixed tests.
     */
    public String createSelfHealingPr(
            List<SchemaDriftEvent> driftEvents,
            List<GeneratedTest> healedTests) {

        if (githubToken.isBlank() || owner.isBlank() || repo.isBlank()) {
            log.warn("[GitHub] PR creation skipped — GitHub credentials not configured.");
            return null;
        }

        log.info("[GitHub] Creating self-healing PR for {} drift events, {} healed tests",
            driftEvents.size(), healedTests.size());

        try {
            // 1. Get base branch SHA
            String baseSha = getBaseSha();

            // 2. Create feature branch
            String branchName = "sentinel/self-heal-" + System.currentTimeMillis();
            createBranch(branchName, baseSha);

            // 3. Commit each healed test file
            for (GeneratedTest test : healedTests) {
                String filePath = "src/test/java/" +
                    test.getPackageName().replace(".", "/") + "/" +
                    test.getClassName() + ".java";
                commitFile(branchName, filePath, test.getContent(),
                    "🛡️ sentinel: heal " + test.getClassName());
            }

            // 4. Open PR
            String prUrl = openPullRequest(branchName, driftEvents, healedTests);
            log.info("[GitHub] Self-healing PR created: {}", prUrl);
            return prUrl;

        } catch (Exception e) {
            log.error("[GitHub] Failed to create PR: {}", e.getMessage(), e);
            return null;
        }
    }

    private String getBaseSha() {
        String response = restClient.get()
            .uri(GITHUB_API + "/repos/{owner}/{repo}/git/ref/heads/{branch}",
                owner, repo, baseBranch)
            .header("Authorization", "Bearer " + githubToken)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .body(String.class);

        try {
            return objectMapper.readTree(response)
                .path("object").path("sha").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get base SHA", e);
        }
    }

    private void createBranch(String branchName, String sha) {
        restClient.post()
            .uri(GITHUB_API + "/repos/{owner}/{repo}/git/refs", owner, repo)
            .header("Authorization", "Bearer " + githubToken)
            .header("Accept", "application/vnd.github+json")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("ref", "refs/heads/" + branchName, "sha", sha))
            .retrieve()
            .toBodilessEntity();
        log.debug("[GitHub] Created branch: {}", branchName);
    }

    private void commitFile(String branch, String filePath, String content, String message) {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes());

        // Check if file already exists (to get its SHA for update)
        String existingSha = getExistingFileSha(branch, filePath);

        Map<String, Object> body = existingSha != null
            ? Map.of("message", message, "content", encoded, "branch", branch, "sha", existingSha)
            : Map.of("message", message, "content", encoded, "branch", branch);

        restClient.put()
            .uri(GITHUB_API + "/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
            .header("Authorization", "Bearer " + githubToken)
            .header("Accept", "application/vnd.github+json")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();

        log.debug("[GitHub] Committed: {}", filePath);
    }

    private String getExistingFileSha(String branch, String filePath) {
        try {
            String response = restClient.get()
                .uri(GITHUB_API + "/repos/{owner}/{repo}/contents/{path}?ref={branch}",
                    owner, repo, filePath, branch)
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(String.class);
            return objectMapper.readTree(response).path("sha").asText();
        } catch (Exception e) {
            return null; // File doesn't exist yet
        }
    }

    private String openPullRequest(
            String branchName,
            List<SchemaDriftEvent> driftEvents,
            List<GeneratedTest> healedTests) {

        String prBody = buildPrDescription(driftEvents, healedTests);

        String response = restClient.post()
            .uri(GITHUB_API + "/repos/{owner}/{repo}/pulls", owner, repo)
            .header("Authorization", "Bearer " + githubToken)
            .header("Accept", "application/vnd.github+json")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of(
                "title", "🛡️ [Sentinel] Self-Healing: API Schema Drift Detected & Fixed",
                "body", prBody,
                "head", branchName,
                "base", baseBranch,
                "draft", false
            ))
            .retrieve()
            .body(String.class);

        try {
            return objectMapper.readTree(response).path("html_url").asText();
        } catch (Exception e) {
            return "PR created but URL not available";
        }
    }

    private String buildPrDescription(
            List<SchemaDriftEvent> drifts, List<GeneratedTest> healedTests) {

        StringBuilder sb = new StringBuilder();
        sb.append("## 🛡️ Sentinel SDK — Autonomous Self-Healing Report\n\n");
        sb.append("This PR was **automatically generated** by [Sentinel-SDK]");
        sb.append("(https://github.com/yourorg/sentinel-sdk) after detecting API schema drift.\n\n");

        sb.append("---\n\n");
        sb.append("## 🔍 Detected Schema Changes\n\n");
        sb.append("| Endpoint | Change Type | Field | Before → After | Severity |\n");
        sb.append("|----------|------------|-------|---------------|----------|\n");

        drifts.forEach(d -> sb.append("| `")
            .append(d.getHttpMethod()).append(" ").append(d.getEndpointPath())
            .append("` | ").append(d.getDriftType())
            .append(" | `").append(d.getFieldPath()).append("`")
            .append(" | `").append(d.getPreviousValue())
            .append("` → `").append(d.getNewValue()).append("`")
            .append(" | ").append(d.getSeverity()).append(" |\n"));

        sb.append("\n---\n\n");
        sb.append("## 🔧 Auto-Healed Tests (").append(healedTests.size()).append(")\n\n");

        healedTests.forEach(t -> {
            sb.append("- `").append(t.getClassName()).append("` — ");
            sb.append(t.getTargetEndpoint()).append("\n");
        });

        sb.append("\n---\n\n");
        sb.append("## ⚠️ Review Checklist\n\n");
        sb.append("- [ ] Verify the healed tests still cover all edge cases\n");
        sb.append("- [ ] Run the full test suite before merging\n");
        sb.append("- [ ] Check if any integration tests outside this PR also reference the changed fields\n");
        sb.append("- [ ] Update API documentation if not done automatically\n\n");
        sb.append("---\n");
        sb.append("*Generated by Sentinel-SDK v1.0.0 — Autonomous QA Agent*\n");

        return sb.toString();
    }
}
