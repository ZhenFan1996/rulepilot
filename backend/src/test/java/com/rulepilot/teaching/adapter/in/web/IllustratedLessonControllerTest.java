package com.rulepilot.teaching.adapter.in.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.teaching.application.IllustratedLessonLauncher;
import com.rulepilot.teaching.application.IllustratedLessonLauncher.LessonLaunch;
import com.rulepilot.teaching.application.IllustratedLessonService;
import com.rulepilot.teaching.application.LessonLocalizationService;
import com.rulepilot.teaching.application.RulebookIconGlossaryService;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IllustratedLessonControllerTest {

    private final IllustratedLessonService lessons = mock(IllustratedLessonService.class);
    private final IllustratedLessonLauncher launcher = mock(IllustratedLessonLauncher.class);
    private final TeachingPlanOwnerGuard owners = mock(TeachingPlanOwnerGuard.class);
    private final LessonLocalizationService localizations = mock(LessonLocalizationService.class);
    private final RulebookIconGlossaryService iconGlossary = mock(RulebookIconGlossaryService.class);
    private final Principal alice = () -> "alice";
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new IllustratedLessonController(lessons, launcher, owners, localizations, iconGlossary))
                .build();
    }

    @Test
    void acceptsGenerationWithoutWaitingForTheLesson() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(launcher.launch(planId, "alice"))
                .thenReturn(new LessonLaunch(runId, AssistantRunState.RECEIVED, false));

        mockMvc.perform(post("/api/v1/teaching-plans/{planId}/illustrated-lessons", planId).principal(alice))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assistantRunId").value(runId.toString()))
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andExpect(jsonPath("$.reused").value(false));

        verify(owners).requireOwned(planId, "alice");
        verify(launcher).launch(planId, "alice");
    }
}
