package com.rulepilot.teaching.adapter.in.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.teaching.application.PublicIconGlossaryBackfillService;
import com.rulepilot.teaching.application.PublicIconGlossaryBackfillService.BackfillLaunch;
import com.rulepilot.teaching.application.RulebookIconGlossaryService.GlossaryStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicIconGlossaryAdminControllerTest {

    private final PublicIconGlossaryBackfillService backfills = mock(PublicIconGlossaryBackfillService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicIconGlossaryAdminController(backfills)).build();
    }

    @Test
    void accepts_a_new_public_icon_inventory() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(backfills.launch(planId)).thenReturn(Optional.of(new BackfillLaunch(
                planId, GlossaryStatus.GENERATING, true, runId, AssistantRunState.RECEIVED, false)));

        mockMvc.perform(post("/api/admin/public-lessons/{planId}/icon-glossary", planId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.teachingPlanId").value(planId.toString()))
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.assistantRunId").value(runId.toString()));
    }

    @Test
    void reports_ready_without_starting_another_run() throws Exception {
        UUID planId = UUID.randomUUID();
        when(backfills.launch(planId)).thenReturn(Optional.of(
                new BackfillLaunch(planId, GlossaryStatus.READY, false, null, null, false)));

        mockMvc.perform(post("/api/admin/public-lessons/{planId}/icon-glossary", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.started").value(false));
    }

    @Test
    void rejects_a_non_public_plan() throws Exception {
        UUID planId = UUID.randomUUID();
        when(backfills.launch(planId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/admin/public-lessons/{planId}/icon-glossary", planId))
                .andExpect(status().isNotFound());
    }
}
