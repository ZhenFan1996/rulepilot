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
import java.util.Map;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

class SpringAiTeachingOutlineModelTest {

    @Test
    void disablesThinkingForTheOwnersDeepSeekPlanningModel() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.TEACHING, "player"))
                .thenReturn(true);
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, mock(VersionedAgentPrompts.class), new FakeTeachingOutlineModel());

        var options = model.providerOptions(Role.TEACHING, "player");

        assertThat(options.build().getExtraBody()).isEqualTo(Map.of("thinking", Map.of("type", "disabled")));
    }

    @Test
    void recognizesNestedTimeoutsSoTheyAreNotRetriedAsSchemaFailures() {
        RuntimeException failure = new RuntimeException("model failed", new SocketTimeoutException("read timed out"));

        assertThat(SpringAiTeachingOutlineModel.isTimeout(failure)).isTrue();
    }

    @Test
    void usesTheOwnersTeachingModelToOrganizeAnAlreadyCataloguedVisualRulebook() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(true);
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
        verify(configuration).usesFake(Role.TEACHING, "player");
        verify(configuration, never()).usesFake(Role.VISUAL, "player");
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

    @Test
    void createsASourceDerivedFallbackWithoutMakingAnotherModelCall() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, mock(VersionedAgentPrompts.class), new FakeTeachingOutlineModel());

        var outline = model.fallback(new OutlineRequest(
                4,
                4,
                30,
                List.of(new PageInput(1, "SETTING UP A GAME: Give each player two power tokens.")),
                List.of(),
                "player"));

        assertThat(outline.topics()).anyMatch(topic -> topic.coverageTags().contains("setup"));
        assertThat(outline.topics()).anyMatch(topic -> topic.coverageTags().contains("scoring"));
        verify(configuration, never()).modelFor(Role.TEACHING, "player");
    }
}
