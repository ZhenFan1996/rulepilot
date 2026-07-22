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
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
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

    @Test
    void givesOwnershipRefinementTheEntireCurrentOutlineInsteadOfOnlyTheConflictSummary() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        new TopicDraft(
                                "turn-flow",
                                "玩家回合流程",
                                "说明五个步骤与拿牌方向。",
                                true,
                                false,
                                List.of("PLAYER TURN"),
                                List.of("core_loop"),
                                List.of(4)),
                        new TopicDraft(
                                "emotion-cards",
                                "拿取情感牌",
                                "说明拿牌、额外抽牌与方向对应。",
                                true,
                                false,
                                List.of("FEEL EMOTION"),
                                List.of("core_loop"),
                                List.of(5))));

        String instruction = SpringAiTeachingOutlineModel.ownershipRefinementInstruction(
                outline, "Move card details out of the flow chapter.");

        assertThat(instruction).contains(
                "Current complete outline",
                "turn-flow | 玩家回合流程",
                "emotion-cards | 拿取情感牌",
                "PLAYER TURN",
                "do not start over",
                "reorder whole topics");
    }
}
