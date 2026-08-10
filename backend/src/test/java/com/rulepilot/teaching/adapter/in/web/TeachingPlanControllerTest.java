package com.rulepilot.teaching.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.teaching.application.TeachingPlanLauncher;
import com.rulepilot.teaching.application.TeachingPlanService;
import java.security.Principal;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPlanControllerTest {

    @Test
    void acceptsANaturalLearningGoalWithoutTurningItIntoAClientSideMode() {
        TeachingPlanService plans = mock(TeachingPlanService.class);
        TeachingPlanLauncher launcher = mock(TeachingPlanLauncher.class);
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var expected = new TeachingPlanLauncher.PlanLaunch(runId, AssistantRunState.RECEIVED, false);
        when(launcher.launch(
                        versionId,
                        "先学会怎么带大家开局，再多讲容易混淆的行动衔接。",
                        "alice"))
                .thenReturn(expected);
        var controller = new TeachingPlanController(plans, launcher);

        var result = controller.create(
                versionId,
                new TeachingPlanController.CreatePlanRequest(
                        "先学会怎么带大家开局，再多讲容易混淆的行动衔接。"),
                (Principal) () -> "alice");

        assertThat(result).isEqualTo(expected);
        verify(launcher).launch(
                versionId,
                "先学会怎么带大家开局，再多讲容易混淆的行动衔接。",
                "alice");
        assertThat(Arrays.stream(TeachingPlanController.CreatePlanRequest.class.getRecordComponents())
                        .map(component -> component.getName()))
                .containsExactly("learningGoal");
    }
}
