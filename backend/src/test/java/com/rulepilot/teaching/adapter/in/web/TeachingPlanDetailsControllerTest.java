package com.rulepilot.teaching.adapter.in.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.teaching.application.TeachingPlanService;
import com.rulepilot.teaching.application.TeachingPlanRemovalService;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TeachingPlanDetailsControllerTest {

    private final TeachingPlanService plans = mock(TeachingPlanService.class);
    private final TeachingPlanRemovalService removals = mock(TeachingPlanRemovalService.class);
    private final Principal alice = () -> "alice";
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TeachingPlanDetailsController(plans, removals)).build();
    }

    @Test
    void listsOnlyPlansSelectedForTheAuthenticatedOwner() throws Exception {
        var plan = plan("alice");
        when(plans.listOwned("alice")).thenReturn(List.of(plan));

        mockMvc.perform(get("/api/v1/teaching-plans").principal(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(plan.id().toString()))
                .andExpect(jsonPath("$[0].createdBy").value("alice"));

        verify(plans).listOwned("alice");
    }

    @Test
    void hidesAPlanThatIsNotOwnedByTheAuthenticatedUser() throws Exception {
        var planId = UUID.randomUUID();
        when(plans.findOwned(planId, "alice")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/teaching-plans/{planId}", planId).principal(alice))
                .andExpect(status().isNotFound());

        verify(plans).findOwned(planId, "alice");
    }

    @Test
    void deletesOnlyTheAuthenticatedUsersTeachingPlan() throws Exception {
        UUID planId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/teaching-plans/{planId}", planId).principal(alice))
                .andExpect(status().isNoContent());

        verify(removals).removeOwned(planId, "alice");
    }

    private TeachingPlan plan(String owner) {
        return new TeachingPlan(
                UUID.randomUUID(), UUID.randomUUID(), 4, 2, 30, "Game", "Premise", List.of(), owner, Instant.parse("2026-07-18T12:00:00Z"));
    }
}
