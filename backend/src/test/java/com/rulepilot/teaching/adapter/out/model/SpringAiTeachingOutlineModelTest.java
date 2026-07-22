package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringAiTeachingOutlineModelTest {

    @Test
    void resolvesTheVisualModelFromThePlanOwnerInsteadOfTheWorkerThread() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.supportsVision(Role.VISUAL, "player")).thenReturn(true);
        when(configuration.usesFake(Role.VISUAL, "player")).thenReturn(true);
        when(configuration.usesFake(Role.VISUAL)).thenReturn(false);
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, mock(VersionedAgentPrompts.class), new FakeTeachingOutlineModel());

        var outline = model.organize(new OutlineRequest(
                4,
                4,
                30,
                List.of(new PageInput(1, "[Visual page catalog; verify against page image]")),
                List.of(new PageImageInput(1, "image/jpeg", new byte[] {1})),
                "player"));

        assertThat(outline.gameTitle()).isEqualTo("Imported rulebook");
    }

    @Test
    void keepsTextBearingRulebookPlanningOnTheTeachingModelEvenWhenPagePreviewsExist() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(true);
        when(configuration.supportsVision(Role.VISUAL, "player")).thenReturn(true);
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, mock(VersionedAgentPrompts.class), new FakeTeachingOutlineModel());

        var outline = model.organize(new OutlineRequest(
                4,
                4,
                30,
                List.of(new PageInput(1, "SETTING UP A GAME: Give each player two power tokens.")),
                List.of(new PageImageInput(1, "image/jpeg", new byte[] {1})),
                "player"));

        assertThat(outline.gameTitle()).isEqualTo("Imported rulebook");
        verify(configuration).usesFake(Role.TEACHING, "player");
        verify(configuration, never()).usesFake(Role.VISUAL, "player");
    }
}
