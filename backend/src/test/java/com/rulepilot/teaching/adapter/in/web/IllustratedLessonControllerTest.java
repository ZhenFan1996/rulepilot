package com.rulepilot.teaching.adapter.in.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.teaching.application.IllustratedLessonLauncher;
import com.rulepilot.teaching.application.IllustratedLessonLauncher.LessonLaunch;
import com.rulepilot.teaching.application.IllustratedLessonService;
import com.rulepilot.teaching.application.TeachingPlanSummary;
import com.rulepilot.teaching.application.LessonLocalizationService;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.security.Principal;
import java.util.Optional;
import java.util.List;
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
    private final Principal alice = () -> "alice";
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new IllustratedLessonController(lessons, launcher, owners, localizations))
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

    @Test
    void returnsOnlyTheProgressFieldsNeededByTheWorkStatusScreen() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        when(lessons.latestProgress(planId)).thenReturn(Optional.of(
                new TeachingPlanSummary.LessonProgress(
                        lessonId,
                        planId,
                        IllustratedLesson.LessonStatus.DRAFT_READY,
                        List.of(new TeachingPlanSummary.SectionProgress(
                                IllustratedLesson.EvidenceStatus.CITED_DRAFT)))));

        mockMvc.perform(get(
                                "/api/v1/teaching-plans/{planId}/illustrated-lessons/latest/summary",
                                planId)
                        .principal(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lessonId.toString()))
                .andExpect(jsonPath("$.teachingPlanId").value(planId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT_READY"))
                .andExpect(jsonPath("$.sections[0].evidenceStatus").value("CITED_DRAFT"))
                .andExpect(jsonPath("$.generatorVersion").doesNotExist());

        verify(owners).requireOwned(planId, "alice");
    }

}
