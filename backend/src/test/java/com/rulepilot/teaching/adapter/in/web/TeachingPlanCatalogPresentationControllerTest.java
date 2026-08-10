package com.rulepilot.teaching.adapter.in.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.teaching.application.TeachingPlanCatalogPresentationService;
import com.rulepilot.teaching.application.TeachingPlanService;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TeachingPlanCatalogPresentationControllerTest {

    @Test
    void returnsAttributedDisplayMetadataOnlyAfterOwnerScopedPlanLookup() throws Exception {
        TeachingPlanService plans = mock(TeachingPlanService.class);
        TeachingPlanCatalogPresentationService presentations = mock(TeachingPlanCatalogPresentationService.class);
        var controller = new TeachingPlanCatalogPresentationController(plans, presentations);
        UUID planId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        TeachingPlan plan = new TeachingPlan(
                planId, UUID.randomUUID(), "Rules title", "Premise", List.of(), "alice",
                Instant.parse("2026-08-06T00:00:00Z"));
        var game = new CatalogGamePresentationLookup.Presentation(
                editionId,
                "Wingspan",
                "Wingspan",
                "en",
                2019,
                266192,
                "https://example.test/wingspan.jpg",
                1,
                5,
                70,
                10,
                "https://boardgamegeek.com/boardgame/266192");
        when(plans.findOwned(planId, "alice")).thenReturn(Optional.of(plan));
        when(presentations.findForOwnedPlan(plan)).thenReturn(Optional.of(game));

        MockMvcBuilders.standaloneSetup(controller)
                .build()
                .perform(get("/api/v1/teaching-plans/{planId}/catalog-presentation", planId)
                        .principal(() -> "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameName").value("Wingspan"))
                .andExpect(jsonPath("$.bggId").value(266192))
                .andExpect(jsonPath("$.bggUrl").value("https://boardgamegeek.com/boardgame/266192"));
    }
}
