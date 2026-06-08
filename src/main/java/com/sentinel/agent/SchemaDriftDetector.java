package com.sentinel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.model.ScanSession;
import com.sentinel.model.SchemaDriftEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Schema drift detection — pure Java structural diff. Zero LLM calls here.
 * AI enrichment (analysis + fix suggestions) is delegated to TestReportGenerator
 * which uses the compressed drift prompt (~250 tokens).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SchemaDriftDetector {

    private final ObjectMapper objectMapper;

    public List<SchemaDriftEvent> detectDrift(String prevSpec, String currSpec, ScanSession session) {
        if (prevSpec == null || prevSpec.isBlank()) {
            log.info("[Drift] Baseline scan — no previous spec to compare");
            return Collections.emptyList();
        }
        try {
            JsonNode prev = objectMapper.readTree(prevSpec);
            JsonNode curr = objectMapper.readTree(currSpec);
            List<SchemaDriftEvent> events = structuralDiff(prev, curr, session);
            log.info("[Drift] Found {} drift events (0 LLM calls)", events.size());
            return events;
        } catch (Exception e) {
            log.error("[Drift] Comparison error: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SchemaDriftEvent> structuralDiff(JsonNode prev, JsonNode curr, ScanSession session) {
        List<SchemaDriftEvent> events = new ArrayList<>();
        JsonNode prevPaths = prev.path("paths");
        JsonNode currPaths = curr.path("paths");

        prevPaths.fields().forEachRemaining(pe -> {
            String path = pe.getKey();
            JsonNode prevItem = pe.getValue();
            JsonNode currItem = currPaths.path(path);

            if (currItem.isMissingNode()) {
                prevItem.fieldNames().forEachRemaining(method -> {
                    if (isHttpMethod(method)) events.add(SchemaDriftEvent.builder()
                        .scanSession(session).endpointPath(path).httpMethod(method.toUpperCase())
                        .driftType(SchemaDriftEvent.DriftType.ENDPOINT_REMOVED)
                        .previousValue(path).newValue("REMOVED")
                        .severity(SchemaDriftEvent.DriftSeverity.CRITICAL)
                        .affectedTestsCount(estimate(path)).build());
                });
            } else {
                prevItem.fields().forEachRemaining(me -> {
                    if (!isHttpMethod(me.getKey())) return;
                    JsonNode currOp = currItem.path(me.getKey());
                    if (currOp.isMissingNode()) {
                        events.add(SchemaDriftEvent.builder()
                            .scanSession(session).endpointPath(path).httpMethod(me.getKey().toUpperCase())
                            .driftType(SchemaDriftEvent.DriftType.ENDPOINT_REMOVED)
                            .previousValue(me.getKey().toUpperCase()+" "+path).newValue("REMOVED")
                            .severity(SchemaDriftEvent.DriftSeverity.HIGH).build());
                    } else {
                        events.addAll(diffBody(path, me.getKey(), me.getValue(), currOp, prev, curr, session));
                    }
                });
            }
        });

        // Newly required fields
        currPaths.fields().forEachRemaining(ce -> {
            String path = ce.getKey();
            if (!prevPaths.has(path)) return;
            ce.getValue().fields().forEachRemaining(me -> {
                if (!isHttpMethod(me.getKey())) return;
                JsonNode prevOp = prevPaths.path(path).path(me.getKey());
                if (!prevOp.isMissingNode()) events.addAll(newRequired(path, me.getKey(), prevOp, me.getValue(), session));
            });
        });

        return events;
    }

    private List<SchemaDriftEvent> diffBody(String path, String method,
            JsonNode prevOp, JsonNode currOp, JsonNode prevSpec, JsonNode currSpec, ScanSession session) {
        List<SchemaDriftEvent> events = new ArrayList<>();
        JsonNode prevProps = props(prevOp, prevSpec);
        JsonNode currProps = props(currOp, currSpec);
        if (prevProps.isMissingNode() || currProps.isMissingNode()) return events;

        prevProps.fields().forEachRemaining(fe -> {
            String field = fe.getKey();
            if (currProps.has(field)) {
                String pt = fe.getValue().path("type").asText("");
                String ct = currProps.path(field).path("type").asText("");
                if (!pt.isBlank() && !ct.isBlank() && !pt.equals(ct)) events.add(SchemaDriftEvent.builder()
                    .scanSession(session).endpointPath(path).httpMethod(method.toUpperCase())
                    .driftType(SchemaDriftEvent.DriftType.FIELD_TYPE_CHANGED)
                    .fieldPath("body."+field).previousValue(pt).newValue(ct)
                    .severity(SchemaDriftEvent.DriftSeverity.HIGH).affectedTestsCount(estimate(path)).build());
            } else {
                String closest = closest(field, currProps);
                events.add(SchemaDriftEvent.builder()
                    .scanSession(session).endpointPath(path).httpMethod(method.toUpperCase())
                    .driftType(closest!=null?SchemaDriftEvent.DriftType.FIELD_RENAMED:SchemaDriftEvent.DriftType.FIELD_REMOVED)
                    .fieldPath("body."+field).previousValue(field).newValue(closest!=null?closest:"REMOVED")
                    .severity(closest!=null?SchemaDriftEvent.DriftSeverity.HIGH:SchemaDriftEvent.DriftSeverity.CRITICAL)
                    .affectedTestsCount(estimate(path)).build());
            }
        });
        return events;
    }

    private List<SchemaDriftEvent> newRequired(String path, String method,
            JsonNode prevOp, JsonNode currOp, ScanSession session) {
        List<SchemaDriftEvent> events = new ArrayList<>();
        JsonNode req = currOp.path("requestBody").path("content").path("application/json").path("schema").path("required");
        JsonNode prevReq = prevOp.path("requestBody").path("content").path("application/json").path("schema").path("required");
        if (!req.isArray()) return events;
        req.forEach(f -> {
            if (!contains(prevReq, f.asText())) events.add(SchemaDriftEvent.builder()
                .scanSession(session).endpointPath(path).httpMethod(method.toUpperCase())
                .driftType(SchemaDriftEvent.DriftType.FIELD_ADDED_REQUIRED)
                .fieldPath("body."+f.asText()).previousValue("optional").newValue("required")
                .severity(SchemaDriftEvent.DriftSeverity.MEDIUM).build());
        });
        return events;
    }

    private JsonNode props(JsonNode op, JsonNode spec) {
        JsonNode schema = op.path("requestBody").path("content").path("application/json").path("schema");
        if (schema.has("$ref")) {
            String[] parts = schema.get("$ref").asText().replace("#/","").split("/");
            JsonNode n = spec; for (String p : parts) n = n.path(p);
            schema = n;
        }
        return schema.path("properties");
    }

    private String closest(String target, JsonNode fields) {
        String best=null; int bestD=5;
        for (Iterator<String> it=fields.fieldNames(); it.hasNext();) {
            String n=it.next(); int d=lev(target,n);
            if (d<bestD) { bestD=d; best=n; }
        }
        return best;
    }

    private int lev(String a, String b) {
        int[][] dp=new int[a.length()+1][b.length()+1];
        for(int i=0;i<=a.length();i++) dp[i][0]=i;
        for(int j=0;j<=b.length();j++) dp[0][j]=j;
        for(int i=1;i<=a.length();i++) for(int j=1;j<=b.length();j++)
            dp[i][j]=a.charAt(i-1)==b.charAt(j-1)?dp[i-1][j-1]:1+Math.min(dp[i-1][j-1],Math.min(dp[i-1][j],dp[i][j-1]));
        return dp[a.length()][b.length()];
    }

    private boolean isHttpMethod(String s) { return Set.of("get","post","put","patch","delete").contains(s.toLowerCase()); }
    private boolean contains(JsonNode arr, String val) { if(!arr.isArray()) return false; for(JsonNode n:arr) if(n.asText().equals(val)) return true; return false; }
    private int estimate(String path) { return (int)(path.chars().filter(c->c=='/').count()*2)+2; }
}
