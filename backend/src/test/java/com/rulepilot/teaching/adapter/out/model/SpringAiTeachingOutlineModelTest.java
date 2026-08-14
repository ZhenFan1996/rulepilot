package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class SpringAiTeachingOutlineModelTest {

    @Test
    void disablesThinkingForTheOwnersDeepSeekPlanningModel() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.TEACHING, "player"))
                .thenReturn(true);
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, mock(VersionedAgentPrompts.class), new FakeTeachingOutlineModel(), 0.37);

        var options = model.providerOptions(Role.TEACHING, "player");

        assertThat(options.build().getExtraBody()).isEqualTo(Map.of("thinking", Map.of("type", "disabled")));
        assertThat(options.build().getTemperature()).isEqualTo(0.37);
    }

    @Test
    void recognizesNestedTimeoutsSoTheyAreNotRetriedAsSchemaFailures() {
        RuntimeException failure = new RuntimeException("model failed", new SocketTimeoutException("read timed out"));

        assertThat(SpringAiTeachingOutlineModel.isTimeout(failure)).isTrue();
    }

    @Test
    void rejectsAPlanningTimeoutInsteadOfPublishingTheFourGenericFallbackChapters() {
        var failure = SpringAiTeachingOutlineModel.planningTimeout(new TimeoutException("deadline"));

        assertThat(failure)
                .hasMessageContaining("semantic lesson plan")
                .hasMessageContaining("retry preparation")
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void usesTheOwnersTeachingModelToOrganizeAnAlreadyCataloguedVisualRulebook() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(true);
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, mock(VersionedAgentPrompts.class), new FakeTeachingOutlineModel());

        var outline = model.organize(new OutlineRequest(

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

    @Test
    void passesTheNaturalLearningGoalToTheOutlineAgentAsUntrustedContext() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.modelFor(Role.TEACHING, "player")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(prompts.teachingOutlineSystem()).thenReturn("Use the goal only as a teaching preference.");
        when(prompts.teachingOutlineUser()).thenReturn(
                "<untrusted_player_learning_goal>{learningGoal}</untrusted_player_learning_goal>\n{pages}\n{repair}");
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"gameTitle":"Game","premise":"Premise","topics":[{
                          "key":"setup","title":"开局","objective":"说明开局。","required":true,
                          "visualEvidenceRecommended":false,"retrievalQueries":["SETUP"],
                          "coverageTags":["setup"],"sourcePageNumbers":[1]
                        }]}
                        """)))));
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, prompts, new FakeTeachingOutlineModel());

        try {
            model.organize(new OutlineRequest(

                    List.of(new PageInput(1, "SETUP: Give each player a board.")),
                    List.of(),
                    "先让我能带大家开局，再重点讲行动怎么衔接。",
                    "player"));
        } finally {
            model.close();
        }

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getTemperature()).isEqualTo(0.1);
        assertThat(prompt.getValue().getInstructions())
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text)
                        .contains(
                                "untrusted_player_learning_goal",
                                "先让我能带大家开局，再重点讲行动怎么衔接。"));
    }

    @Test
    void rejectsInvalidOutlineTemperature() {
        assertThatThrownBy(() -> new SpringAiTeachingOutlineModel(
                        mock(RuntimeModelConfiguration.class),
                        mock(VersionedAgentPrompts.class),
                        new FakeTeachingOutlineModel(),
                        Double.POSITIVE_INFINITY))
                .hasMessageContaining("teaching outline model temperature");
    }
}
