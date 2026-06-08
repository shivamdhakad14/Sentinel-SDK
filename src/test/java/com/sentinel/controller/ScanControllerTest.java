package com.sentinel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.model.ScanSession;
import com.sentinel.repository.AgentAttemptRepository;
import com.sentinel.repository.ScanSessionRepository;
import com.sentinel.service.ScanOrchestrationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ScanController.class})
@DisplayName("ScanController — REST API slice tests")
class ScanControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ScanOrchestrationService orchestrationService;
    @MockBean ScanSessionRepository sessionRepository;
    @MockBean AgentAttemptRepository attemptRepository;

    ScanSession mockSession;

    @BeforeEach
    void setUp() {
        mockSession = ScanSession.builder()
            .id(1L).targetBaseUrl("http://localhost:8080")
            .targetName("Test API").status(ScanSession.ScanStatus.QUEUED)
            .totalEndpointsDiscovered(0).endpointsPassed(0).endpointsFailed(0)
            .startedAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("POST /api/v1/scans — valid request returns 200 with QUEUED session")
    void startScan_validRequest() throws Exception {
        when(orchestrationService.startScan(anyString(), anyString())).thenReturn(mockSession);

        mockMvc.perform(post("/api/v1/scans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetBaseUrl\":\"http://localhost:8080\",\"targetName\":\"Test API\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    @DisplayName("POST /api/v1/scans — missing targetBaseUrl returns 400")
    void startScan_missingUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/scans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetName\":\"Only Name\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/scans — missing targetName returns 400")
    void startScan_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/scans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetBaseUrl\":\"http://localhost:8080\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/scans — returns list of sessions")
    void listScans_returnsList() throws Exception {
        when(sessionRepository.findAllByOrderByStartedAtDesc()).thenReturn(List.of(mockSession));

        mockMvc.perform(get("/api/v1/scans"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].targetName").value("Test API"));
    }

    @Test
    @DisplayName("GET /api/v1/scans/{id} — found returns session")
    void getScan_found() throws Exception {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(mockSession));
        mockMvc.perform(get("/api/v1/scans/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.targetName").value("Test API"));
    }

    @Test
    @DisplayName("GET /api/v1/scans/{id} — not found returns 404")
    void getScan_notFound() throws Exception {
        when(sessionRepository.findById(999L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/scans/999"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/scans/stats — returns aggregate numbers")
    void getStats_returnsMetrics() throws Exception {
        when(sessionRepository.count()).thenReturn(15L);
        when(sessionRepository.countActiveSessions()).thenReturn(3L);
        when(sessionRepository.findByStatus(ScanSession.ScanStatus.COMPLETED))
            .thenReturn(List.of(mockSession, mockSession, mockSession, mockSession, mockSession));
        when(sessionRepository.findByStatus(ScanSession.ScanStatus.FAILED)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/scans/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalScans").value(15))
            .andExpect(jsonPath("$.data.activeScans").value(3))
            .andExpect(jsonPath("$.data.successRate").isNumber());
    }

    @Test
    @DisplayName("GET /api/v1/scans/{id}/attempts — returns attempt list")
    void getAttempts_returnsEmptyList() throws Exception {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(mockSession));
        when(attemptRepository.findByScanSessionIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/scans/1/attempts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }
}
