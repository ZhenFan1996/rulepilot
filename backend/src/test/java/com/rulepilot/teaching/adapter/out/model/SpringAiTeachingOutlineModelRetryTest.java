package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.rulepilot.teaching.VisualRuleGroupTestFacts.facts;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.modelconfig.AccountQuotaExceededException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageLedgerState;
import com.rulepilot.teaching.application.TeachingSourceCoverageContract;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class SpringAiTeachingOutlineModelRetryTest {

    private static final String VALID_OUTLINE = """
            {
              "gameTitle":"示例游戏",
              "premise":"先完成准备，再轮流行动。",
              "topics":[{
                "key":"start-playing",
                "title":"准备并开始行动",
                "objective":"能够完成准备并执行行动。",
                "required":true,
                "visualEvidenceRecommended":false,
                "retrievalQueries":["SETUP","TAKE TURN"],
                "coverageTags":["setup","core_loop","source_coverage"],
                "sourcePageNumbers":[1]
              }],
              "sourceCoverageSlots":[
                {"slotId":"setup","role":"SETUP","sourceIdentifier":"SETUP",
                 "sourcePageNumbers":[1],"ownerTopicKey":"start-playing",
                 "teachingUnitId":"start-playing","availability":"SOURCED"},
                {"slotId":"take-turn","role":"CORE_LOOP","sourceIdentifier":"TAKE TURN",
                 "sourcePageNumbers":[1],"ownerTopicKey":"start-playing",
                 "teachingUnitId":"start-playing","availability":"SOURCED"}
              ],
              "sourceCoverageInventoryComplete":true,
              "wholeGameUnderstanding":{
                "summary":"准备建立初始状态，随后玩家轮流行动。",
                "concepts":[{
                  "conceptId":"turn-state",
                  "label":"回合状态",
                  "explanation":"完成准备后才能进入轮流行动。",
                  "sourceIdentifiers":["SETUP","TAKE TURN"],
                  "sourcePageNumbers":[1],
                  "relatedTopicKeys":["start-playing"],
                  "prerequisiteConceptIds":[]
                }],
                "topicDependencies":[]
              }
            }
            """;
    private static final String COMPACT_CANONICAL_OUTLINE = """
            {
              "gameTitle":"不透明游戏",
              "premise":"先理解可验证的行动关系。",
              "topics":[{
                "key":"choose-action",
                "objective":"能够依据已验证规则选择行动。",
                "required":true,
                "visualEvidenceRecommended":false,
                "teachingUnits":[{
                  "teachingUnitId":"choose-action",
                  "role":"LEGAL_ACTION",
                  "sourceSlotIds":["page-1-rule-1"]
                }]
              }],
              "wholeGameUnderstanding":{
                "summary":"已验证页面说明了行动选择。",
                "concepts":[{
                  "conceptId":"action-choice",
                  "label":"行动选择",
                  "explanation":"玩家依照已验证规则选择行动。",
                  "sourceSlotIds":["page-1-rule-1"],
                  "relatedTopicKeys":["choose-action"],
                  "prerequisiteConceptIds":[]
                }],
                "topicDependencies":[]
              }
            }
            """;

    @Test
    void retriesOneTransientProviderTimeoutAndReturnsTheValidatedOutline() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("provider stalled", new SocketTimeoutException("read timed out")))
                .thenReturn(response(VALID_OUTLINE));
        SpringAiTeachingOutlineModel model = model(configuration);

        try {
            var outline = model.organize(request());

            assertThat(outline.gameTitle()).isEqualTo("示例游戏");
            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("SETUP", "TAKE TURN");
            verify(chatModel, times(2)).call(any(Prompt.class));
        } finally {
            model.close();
        }
    }

    @Test
    void stopsAfterTheSingleBoundedTransportRetry() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("first timeout", new SocketTimeoutException("read timed out")))
                .thenThrow(new RuntimeException("second timeout", new SocketTimeoutException("read timed out")));
        SpringAiTeachingOutlineModel model = model(configuration);

        try {
            assertThatThrownBy(() -> model.organize(request()))
                    .isInstanceOf(OutlineGenerationException.class)
                    .hasMessageContaining("no valid outline");
            verify(chatModel, times(2)).call(any(Prompt.class));
        } finally {
            model.close();
        }
    }

    @Test
    void doesNotTreatAccountQuotaExhaustionAsOutputRepairOrTransportRetry() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("provider wrapper", new AccountQuotaExceededException()));
        SpringAiTeachingOutlineModel model = model(configuration);

        try {
            assertThatThrownBy(() -> model.organize(request()))
                    .isInstanceOf(AccountQuotaExceededException.class);
            verify(chatModel).call(any(Prompt.class));
        } finally {
            model.close();
        }
    }

    @Test
    void closesEveryFailedProviderCallWithTheOperationThatActuallyStartedIt() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("first timeout", new SocketTimeoutException("read timed out")))
                .thenThrow(new RuntimeException("second timeout", new SocketTimeoutException("read timed out")));
        SpringAiTeachingOutlineModel model = model(configuration);
        RecordingCapture capture = new RecordingCapture();
        ResourceRef resource = new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID());

        try {
            assertThatThrownBy(() -> model.organize(request(), capture, resource, UUID.randomUUID()))
                    .isInstanceOf(OutlineGenerationException.class);

            var startedOperations = capture.starts.stream()
                    .map(event -> event.context().operationId())
                    .toList();
            var failedOperations = capture.failures.stream()
                    .filter(event -> event.code().equals("TEACHING_OUTLINE_MODEL_CALL_FAILED"))
                    .map(event -> event.context().operationId())
                    .toList();
            assertThat(startedOperations).hasSize(2);
            assertThat(failedOperations).containsExactlyElementsOf(startedOperations);
        } finally {
            model.close();
        }
    }

    @Test
    void plansFromExactVisualPagesWhenAnotherPageIsExplicitlyUnavailable() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(COMPACT_CANONICAL_OUTLINE));
        SpringAiTeachingOutlineModel model = model(configuration);

        try {
            var outline = model.organize(partialVisualRequest());

            assertThat(outline.sourceCoverageSlots())
                    .extracting(slot -> slot.sourceIdentifier())
                    .containsExactly("R-1");
            assertThat(outline.sourceCoverageInventoryComplete()).isTrue();
            assertThat(outline.topics()).allSatisfy(topic -> assertThat(topic.coverageTags())
                    .contains(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG));
            assertThat(outline.premise()).contains(
                    "第2页本轮没有获得可验证的视觉证据",
                    "不会推断这些页面的规则");

            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(prompt.capture());
            assertThat(prompt.getValue().getInstructions().stream().map(message -> message.getText()).toList())
                    .anySatisfy(text -> assertThat(text).contains(
                            "PAGE_LEDGER_STATE: VISUAL_EXACT_COMPLETE",
                            "PAGE_LEDGER_STATE: VISUAL_EXPLICITLY_UNAVAILABLE",
                            "source obligations for this page are unknown"))
                    .noneSatisfy(text -> assertThat(text).contains("UNSAFE_UNAVAILABLE_DISPLAY_TEXT"));
        } finally {
            model.close();
        }
    }

    @Test
    void refusesAllUnavailableOrPartialVisualLedgersBeforeCallingAProvider() {
        RuntimeModelConfiguration configuration = configuration();
        ChatModel chatModel = chatModel(configuration);
        SpringAiTeachingOutlineModel model = model(configuration);
        var unavailableOnly = new OutlineRequest(
                List.of(new PageInput(
                        1,
                        "No visual observation.",
                        List.of(),
                        List.of(),
                        false,
                        List.of(),
                        PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE)),
                List.of(),
                "player");
        var exactAndPartial = new OutlineRequest(
                List.of(
                        exactVisualPage(1, "R-1"),
                        new PageInput(
                                2,
                                "Only a partial observation.",
                                List.of(),
                                List.of("R-2"),
                                false,
                                facts("R-2"),
                                PageLedgerState.VISUAL_PARTIAL)),
                List.of(),
                "player");

        try {
            assertThatThrownBy(() -> model.organize(unavailableOnly))
                    .isInstanceOf(OutlineGenerationException.class)
                    .hasMessageContaining("no safe canonical source ledger");
            assertThatThrownBy(() -> model.organize(exactAndPartial))
                    .isInstanceOf(OutlineGenerationException.class)
                    .hasMessageContaining("no safe canonical source ledger");
            verify(chatModel, times(0)).call(any(Prompt.class));
        } finally {
            model.close();
        }
    }

    private RuntimeModelConfiguration configuration() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.providerFor(Role.TEACHING, "player")).thenReturn("compatible");
        when(configuration.modelNameFor(Role.TEACHING, "player")).thenReturn("teaching-test-model");
        return configuration;
    }

    private ChatModel chatModel(RuntimeModelConfiguration configuration) {
        ChatModel model = mock(ChatModel.class);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
        when(configuration.modelFor(Role.TEACHING, "player")).thenReturn(model);
        when(model.getDefaultOptions()).thenReturn(options);
        when(model.getOptions()).thenReturn(options);
        return model;
    }

    private SpringAiTeachingOutlineModel model(RuntimeModelConfiguration configuration) {
        return new SpringAiTeachingOutlineModel(
                configuration,
                mock(VersionedAgentPrompts.class),
                0.1,
                "Return one exact JSON outline.",
                "Goal={learningGoal}\nPages={pages}\nImages={visualPages}\nRepair={repair}");
    }

    private OutlineRequest request() {
        return new OutlineRequest(
                List.of(new PageInput(1, "SETUP: Place one marker. TAKE TURN: Move one marker.")),
                List.of(),
                "player");
    }

    private OutlineRequest partialVisualRequest() {
        return new OutlineRequest(
                List.of(
                        exactVisualPage(1, "R-1"),
                        new PageInput(
                                2,
                                "UNSAFE_UNAVAILABLE_DISPLAY_TEXT",
                                List.of(),
                                List.of(),
                                false,
                                List.of(),
                                PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE)),
                List.of(),
                "player");
    }

    private PageInput exactVisualPage(int pageNumber, String identifier) {
        return new PageInput(
                pageNumber,
                identifier + ": A directly visible rule.",
                List.of(),
                List.of(identifier),
                true,
                facts(identifier),
                PageLedgerState.VISUAL_EXACT_COMPLETE);
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class RecordingCapture implements CaptureHandle {
        private final UUID traceId = UUID.randomUUID();
        private final List<AgentTraceEvent.ModelCallStarted> starts = new ArrayList<>();
        private final List<AgentTraceEvent.BindingOrFailure> failures = new ArrayList<>();

        @Override public boolean enabled() { return true; }
        @Override public Optional<UUID> traceId() { return Optional.of(traceId); }
        @Override public void userTurn(AgentTraceEvent.UserTurn event) {}
        @Override public void modelCallStarted(AgentTraceEvent.ModelCallStarted event) { starts.add(event); }
        @Override public void modelTurn(AgentTraceEvent.ModelTurn event) {}
        @Override public void toolCall(AgentTraceEvent.ToolCall event) {}
        @Override public void toolObservation(AgentTraceEvent.ToolObservation event) {}
        @Override public void publication(AgentTraceEvent.Publication event) {}
        @Override public void bindingOrFailure(AgentTraceEvent.BindingOrFailure event) { failures.add(event); }
        @Override public boolean bind(ResourceRef resource) { return true; }
    }
}
