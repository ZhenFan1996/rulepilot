package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpringAiTeachingLessonModelTest {

    @Test
    void delegatesOnlyImageBearingSectionsToTheVisualProvider() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("deepseek");
        when(configuration.providerFor(Role.VISUAL)).thenReturn("gemini");
        when(configuration.usesFake(Role.VISUAL)).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL)).thenReturn(true);
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.providerId()).isEqualTo("deepseek+gemini");
        assertThat(model.roleFor(request(List.of()))).isEqualTo(Role.TEACHING);
        assertThat(model.roleFor(request(List.of(new PageImageInput(4, "image/png", new byte[] {1}, 100, 100)))))
                .isEqualTo(Role.VISUAL);
    }

    @Test
    void disablesQwenThinkingForBoundedVisualLessonWork() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.VISUAL)).thenReturn("qwen");
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.providerOptions(Role.VISUAL)).containsEntry("enable_thinking", false);
    }

    @Test
    void resolvesVisionFromTheLessonOwnerRatherThanWorkerThreadState() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.VISUAL, "player")).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL, "player")).thenReturn(true);
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.supportsVisualEvidence("player")).isTrue();
    }

    private SectionRequest request(List<PageImageInput> pageImages) {
        return new SectionRequest(
                "setup",
                "摆放游戏",
                "让玩家完成开局摆放",
                List.of("setup"),
                4,
                4,
                25,
                120,
                4,
                List.of(),
                List.of(new EvidenceInput(UUID.randomUUID(), "SETUP", "Setup", "Place the board.", 4, 4)),
                pageImages);
    }
}
