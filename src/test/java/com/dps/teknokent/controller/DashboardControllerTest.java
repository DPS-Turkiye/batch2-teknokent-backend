package com.dps.teknokent.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.responses.DashboardSummaryResponse;
import com.dps.teknokent.service.DashboardService;

@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @Test
    void requiresAuthenticationForDashboardSummary() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized());
    }

    // A session without a startup sends a blank id; that must not read as "the whole platform".
    @Test
    void refusesADashboardSummaryWithoutAnOrganization() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary").with(user("test-user")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/dashboard/summary").param("organizationId", "").with(user("test-user")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(dashboardService);
    }

    @Test
    void returnsDashboardSummaryForSpecificOrganization() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(dashboardService.getSummary(organizationId)).thenReturn(new DashboardSummaryResponse(
                7,
                3,
                1,
                2,
                4
        ));

        mockMvc.perform(get("/api/dashboard/summary")
                        .param("organizationId", organizationId.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeMentorships").value(7))
                .andExpect(jsonPath("$.openBottlenecks").value(3))
                .andExpect(jsonPath("$.highSeverityBottlenecks").value(1))
                .andExpect(jsonPath("$.overdueActions").value(2))
                .andExpect(jsonPath("$.upcomingMeetings").value(4));
    }

    @Test
    void returnsCompleteSummaryPayload() throws Exception {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(dashboardService.getSummary(organizationId)).thenReturn(new DashboardSummaryResponse(
                9,
                5,
                3,
                6,
                2
        ));

        mockMvc.perform(get("/api/dashboard/summary")
                        .param("organizationId", organizationId.toString())
                        .with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeMentorships").value(9))
                .andExpect(jsonPath("$.openBottlenecks").value(5))
                .andExpect(jsonPath("$.highSeverityBottlenecks").value(3))
                .andExpect(jsonPath("$.overdueActions").value(6))
                .andExpect(jsonPath("$.upcomingMeetings").value(2));
    }
}
