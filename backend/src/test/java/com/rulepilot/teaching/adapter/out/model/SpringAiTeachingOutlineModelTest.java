package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
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
    void closesOnlyASingleMissingRootObjectDelimiterWithoutRepairingTruncatedContent() {
        assertThat(SpringAiTeachingOutlineModel.closeTruncatedRootObject(
                        "{\"topics\":[{\"key\":\"flow\"}]}"))
                .isEqualTo("{\"topics\":[{\"key\":\"flow\"}]}");
        assertThat(SpringAiTeachingOutlineModel.closeTruncatedRootObject(
                        "{\"topics\":[{\"key\":\"flow"))
                .isEqualTo("{\"topics\":[{\"key\":\"flow");
        assertThat(SpringAiTeachingOutlineModel.closeTruncatedRootObject(
                        "{\"topics\":[{\"key\":\"flow\"}"))
                .isEqualTo("{\"topics\":[{\"key\":\"flow\"}");
        assertThat(SpringAiTeachingOutlineModel.closeTruncatedRootObject("{\"topics\":[]}"))
                .isEqualTo("{\"topics\":[]}");
    }

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
        assertThat(options.build().getMaxTokens()).isEqualTo(10_000);
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
    void translatesRepeatedStructuredOutputFailuresAtTheModelBoundary() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.modelFor(Role.TEACHING, "player")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(prompts.teachingOutlineSystem()).thenReturn("Return a bounded outline.");
        when(prompts.teachingOutlineUser()).thenReturn("{pages}\n{repair}");
        when(prompts.structuredOutputRepair()).thenReturn("Repair the invalid structure.");
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"gameTitle":"Game","premise":"Premise","topics":[{
                          "key":"invalid","title":"","objective":"Teach one source relation.",
                          "required":true,"visualEvidenceRecommended":true,
                          "retrievalQueries":["OPAQUE"],"coverageTags":["core_loop"],
                          "sourcePageNumbers":[1]
                        }]}
                        """)))));
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, prompts, new FakeTeachingOutlineModel());

        try {
            assertThatThrownBy(() -> model.organize(new OutlineRequest(
                            List.of(new PageInput(1, "OPAQUE source relation")),
                            List.of(),
                            "player")))
                    .isInstanceOf(OutlineGenerationException.class)
                    .hasMessageContaining("no valid outline")
                    .hasCauseInstanceOf(RuntimeException.class);
        } finally {
            model.close();
        }

        verify(chatModel, times(2)).call(any(Prompt.class));
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
    void repairsOnlyInvalidWholeGameContextWithoutRegeneratingTheValidOutline() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.modelFor(Role.TEACHING, "player")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatResponse incomplete = new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"gameTitle":"Game","premise":"Two source-owned relations form one lesson.","topics":[
                  {"key":"first-topic","title":"第一关系","objective":"先理解第一项来源关系。","required":true,
                   "visualEvidenceRecommended":false,"retrievalQueries":["R-one"],
                   "coverageTags":["source_coverage"],"sourcePageNumbers":[1]},
                  {"key":"second-topic","title":"第二关系","objective":"再理解第二项来源关系。","required":true,
                   "visualEvidenceRecommended":false,"retrievalQueries":["R-two"],
                   "coverageTags":["source_coverage"],"sourcePageNumbers":[1]}
                ],"sourceCoverageSlots":[
                  {"slotId":"first-slot","role":"SUPPORTING_RULE","sourceIdentifier":"R-one",
                   "sourcePageNumbers":[1],"ownerTopicKey":"first-topic","teachingUnitId":"first-unit","availability":"SOURCED"},
                  {"slotId":"second-slot","role":"SUPPORTING_RULE","sourceIdentifier":"R-two",
                   "sourcePageNumbers":[1],"ownerTopicKey":"second-topic","teachingUnitId":"second-unit","availability":"SOURCED"}
                ],"sourceCoverageInventoryComplete":true,"wholeGameUnderstanding":{
                  "summary":"先理解第一项关系，再用它理解第二项关系。",
                  "concepts":[
                    {"conceptId":"first-relation","label":"第一项关系","explanation":"第一项来源关系。",
                     "sourceIdentifiers":["R-one"],"sourcePageNumbers":[1],"relatedTopicKeys":["first-topic"],
                     "prerequisiteConceptIds":[]},
                    {"conceptId":"second-relation","label":"第二项关系","explanation":"第二项来源关系。",
                     "sourceIdentifiers":["R-two"],"sourcePageNumbers":[1],"relatedTopicKeys":["second-topic"],
                     "prerequisiteConceptIds":["first-topic"]}],
                  "topicDependencies":[{"prerequisiteTopicKey":"first-topic","dependentTopicKey":"second-topic",
                    "reason":"第二项教学依赖第一项。"}]}}
                """))));
        ChatResponse patch = new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"concepts":[
                  {"conceptId":"first-relation","label":"第一项关系","explanation":"第一项来源关系。",
                   "sourceSlotIds":["first-slot"],"relatedTopicKeys":["first-topic"],
                   "prerequisiteConceptIds":[]},
                  {"conceptId":"second-relation","label":"第二项关系",
                   "explanation":"第二项来源关系建立在第一项之后。","sourceSlotIds":["second-slot"],
                   "relatedTopicKeys":["second-topic"],"prerequisiteConceptIds":["first-relation"]}],
                 "topicDependencies":[{"prerequisiteTopicKey":"first-topic",
                   "dependentTopicKey":"second-topic","reason":"第二项教学依赖第一项。"}]}
                """))));
        when(chatModel.call(any(Prompt.class))).thenReturn(incomplete, patch);
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, prompts, new FakeTeachingOutlineModel());

        OutlineDraft outline;
        try {
            outline = model.organize(new OutlineRequest(
                    List.of(new PageInput(1, "R-one is established first. R-two follows it.")),
                    List.of(),
                    "player"));
        } finally {
            model.close();
        }

        assertThat(outline.topics()).extracting(TopicDraft::key)
                .containsExactly("first-topic", "second-topic");
        assertThat(outline.sourceCoverageSlots()).extracting(slot -> slot.sourceIdentifier())
                .containsExactly("R-one", "R-two");
        assertThat(outline.wholeGameUnderstanding().concepts())
                .extracting(concept -> concept.conceptId())
                .containsExactly("first-relation", "second-relation");
        assertThat(com.rulepilot.teaching.application.TeachingSourceCoverageContract
                        .unrelatedSourceOwnedTopicKeys(outline))
                .isEmpty();
        ArgumentCaptor<Prompt> promptsSent = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptsSent.capture());
        assertThat(promptsSent.getAllValues().get(1).getInstructions())
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text)
                        .contains("Topic contracts", "second-topic", "second-slot", "R-two")
                        .doesNotContain("Return a complete replacement outline"));
    }

    @Test
    void repairsOnlyInvalidSourceIdentifiersAndPreservesTheAgentOwnedOutline() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.modelFor(Role.TEACHING, "player")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatResponse outlineResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"gameTitle":"Game","premise":"Keep this premise exactly.","topics":[{
                  "key":"flow","title":"自然教学标题","objective":"完整解释这项来源关系。","required":true,
                  "visualEvidenceRecommended":false,"retrievalQueries":["中文提示"],
                  "coverageTags":["source_coverage"],"sourcePageNumbers":[1]
                }],"sourceCoverageSlots":[{
                  "slotId":"flow-slot","role":"SUPPORTING_RULE","sourceIdentifier":"R-paraphrase",
                  "sourcePageNumbers":[1],"ownerTopicKey":"flow","teachingUnitId":"flow-unit"
                }],"sourceCoverageInventoryComplete":true,"wholeGameUnderstanding":{
                  "summary":"保留这份整体认识。","concepts":[{
                    "conceptId":"flow-relation","label":"来源关系","explanation":"解释来源关系。",
                    "sourceIdentifiers":["R-paraphrase"],"sourcePageNumbers":[1],
                    "relatedTopicKeys":["flow"],"prerequisiteConceptIds":[]
                  }],"topicDependencies":[]}}
                """))));
        ChatResponse patchResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"replacements":[{"slotId":"flow-slot","sourceIdentifier":"R-canonical"}]}
                """))));
        when(chatModel.call(any(Prompt.class))).thenReturn(outlineResponse, patchResponse);
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, prompts, new FakeTeachingOutlineModel());

        OutlineDraft outline;
        try {
            outline = model.organize(new OutlineRequest(
                    List.of(new PageInput(1, "R-canonical establishes the relation.")),
                    List.of(),
                    "player"));
        } finally {
            model.close();
        }

        assertThat(outline.gameTitle()).isEqualTo("Game");
        assertThat(outline.premise()).isEqualTo("Keep this premise exactly.");
        assertThat(outline.topics()).singleElement().satisfies(topic -> {
            assertThat(topic.title()).isEqualTo("自然教学标题");
            assertThat(topic.objective()).isEqualTo("完整解释这项来源关系。");
            assertThat(topic.retrievalQueries()).containsExactly("中文提示");
        });
        assertThat(outline.sourceCoverageSlots()).singleElement().satisfies(slot ->
                assertThat(slot.sourceIdentifier()).isEqualTo("R-canonical"));
        assertThat(outline.wholeGameUnderstanding().summary()).isEqualTo("保留这份整体认识。");
        assertThat(outline.wholeGameUnderstanding().concepts()).singleElement().satisfies(concept ->
                assertThat(concept.sourceIdentifiers()).containsExactly("R-canonical"));
        ArgumentCaptor<Prompt> promptsSent = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptsSent.capture());
        assertThat(promptsSent.getAllValues().get(1).getInstructions())
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text)
                        .contains("flow-slot", "R-paraphrase", "R-canonical establishes the relation")
                        .doesNotContain("Return a complete replacement outline"));
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
                          "key":"playable","title":"可开局规则","objective":"说明全部有来源的必要规则。","required":true,
                          "visualEvidenceRecommended":false,
                          "retrievalQueries":["S-0","T-0","A-0","F-0","P-0","E-0"],
                          "coverageTags":["setup","core_loop","legal_action","end","scoring","necessary_exception"],
                          "sourcePageNumbers":[1]
                        }],"wholeGameUnderstanding":{
                          "summary":"先认识共同状态，再沿来源关系完成这一章。",
                          "concepts":[{
                            "conceptId":"playable-relation","label":"完整来源关系",
                            "explanation":"六个来源锚点共同界定当前规则关系。",
                            "sourceIdentifiers":["S-0","T-0","A-0","F-0","P-0","E-0"],
                            "sourcePageNumbers":[1],"relatedTopicKeys":["playable"],
                            "prerequisiteConceptIds":[]
                          }],"topicDependencies":[]
                        },"sourceCoverageSlots":[
                          {"slotId":"setup","role":"SETUP","sourceIdentifier":"S-0",
                           "sourcePageNumbers":[1],"ownerTopicKey":"playable","availability":"SOURCED"},
                          {"slotId":"flow","role":"CORE_LOOP","sourceIdentifier":"T-0",
                           "sourcePageNumbers":[1],"ownerTopicKey":"playable","availability":"SOURCED"},
                          {"slotId":"action","role":"LEGAL_ACTION","sourceIdentifier":"A-0",
                           "sourcePageNumbers":[1],"ownerTopicKey":"playable","availability":"SOURCED"},
                          {"slotId":"ending","role":"ENDING","sourceIdentifier":"F-0",
                           "sourcePageNumbers":[1],"ownerTopicKey":"playable","availability":"SOURCED"},
                          {"slotId":"scoring","role":"SCORING","sourceIdentifier":"P-0",
                           "sourcePageNumbers":[1],"ownerTopicKey":"playable","availability":"SOURCED"},
                          {"slotId":"exception","role":"NECESSARY_EXCEPTION","sourceIdentifier":"E-0",
                           "sourcePageNumbers":[1],"ownerTopicKey":"playable","availability":"SOURCED"}
                        ],"sourceCoverageInventoryComplete":true}
                        """)))));
        SpringAiTeachingOutlineModel model = new SpringAiTeachingOutlineModel(
                configuration, prompts, new FakeTeachingOutlineModel());

        try {
            model.organize(new OutlineRequest(

                    List.of(new PageInput(
                            1,
                            "S-0 starts play. T-0 advances play. A-0 is a legal choice. "
                                    + "F-0 ends play. P-0 resolves the result. E-0 changes A-0 conditionally.")),
                    List.of(),
                    "先让我能带大家开局，再重点讲行动怎么衔接。",
                    "player"));
        } finally {
            model.close();
        }

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getTemperature()).isEqualTo(0.1);
        String runtimeSystem = prompt.getValue().getInstructions().getFirst().getText();
        assertThat(runtimeSystem)
                .contains("Planning autonomy", "teachingUnitId")
                .doesNotContain(
                        "Create at least one slot for SETUP",
                        "Across all topics include setup, core_loop, end, and scoring");
        assertThat(runtimeSystem.length()).isLessThan(10_000);
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
