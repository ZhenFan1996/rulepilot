package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.TeachingUnitInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiTeachingLessonModelStructuredOutputTest {

    private static final String VALID = """
            {
              "title":"开始行动",
              "visualKind":"FLOW_DIAGRAM",
              "visualCaption":"从选择行动到结算结果的顺序。",
              "visualCitationIds":["E1"],
              "steps":[{
                "heading":"选择一项行动",
                "kind":"DO",
                "text":"选择当前可用的一项行动，然后支付对应费用。",
                "citationIds":["E1"],
                "teachingUnitIds":["turn-action"],
                "ruleFacts":[
                  {"role":"CHOICE","text":"选择一项当前可用的行动。","citationIds":["E1"]},
                  {"role":"COST_OR_GAIN","text":"支付该行动列出的费用。","citationIds":["E1"]},
                  {"role":"LIMIT","text":"本回合只能选择一项行动。","citationIds":["E1"]}
                ],
                "visualFocus":null
              }]
            }
            """;

    @Test
    void admitsEveryDeclaredDisplayFieldFromOneExactJsonObject() throws Exception {
        var draft = SpringAiTeachingLessonModel.parseStructuredDraft(VALID);

        assertThat(draft.title()).isEqualTo("开始行动");
        assertThat(draft.steps()).singleElement().satisfies(step -> {
            assertThat(step.heading()).isEqualTo("选择一项行动");
            assertThat(step.ruleFacts()).extracting(SpringAiTeachingLessonModel.ModelRuleFact::role)
                    .containsExactly(
                            com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole.CHOICE,
                            com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole.COST_OR_GAIN,
                            com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole.LIMIT);
        });
    }

    @Test
    void admitsAtTableReferenceAndLimitStepsWithoutRegeneratingSupportedContent() throws Exception {
        var reference = SpringAiTeachingLessonModel.parseStructuredDraft(
                VALID.replace("\"kind\":\"DO\"", "\"kind\":\"REFERENCE_CARD\""));
        var limit = SpringAiTeachingLessonModel.parseStructuredDraft(
                VALID.replace("\"kind\":\"DO\"", "\"kind\":\"LIMIT\""));

        assertThat(reference.steps().getFirst().kind())
                .isEqualTo(com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove.REFERENCE_CARD);
        assertThat(limit.steps().getFirst().kind())
                .isEqualTo(com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove.LIMIT);
    }

    @Test
    void rejectsMissingNestedFieldsInsteadOfDefaultingThemToEmptyLists() {
        String missingRuleFacts = """
                {
                  "title":"开始行动",
                  "visualKind":"FLOW_DIAGRAM",
                  "visualCaption":"从选择行动到结算结果的顺序。",
                  "visualCitationIds":["E1"],
                  "steps":[{
                    "heading":"选择一项行动",
                    "kind":"DO",
                    "text":"选择当前可用的一项行动。",
                    "citationIds":["E1"],
                    "teachingUnitIds":["turn-action"],
                    "visualFocus":null
                  }]
                }
                """;

        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(missingRuleFacts))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void rejectsUnexpectedFieldsAndMarkdownWrappersInsteadOfRepairingThem() {
        String unexpected = VALID.replace("\"title\":\"开始行动\"", "\"title\":\"开始行动\",\"statusLine\":\"完成\"");

        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(unexpected))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft("```json\n" + VALID + "\n```"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void rejectsNullOrDuplicateStructuredArraysInsteadOfNormalizingThem() {
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(
                        VALID.replace("\"visualCitationIds\":[\"E1\"]", "\"visualCitationIds\":null")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(
                        VALID.replace("\"citationIds\":[\"E1\"]", "\"citationIds\":[\"E1\",\"E1\"]")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(
                        VALID.replace("\"teachingUnitIds\":[\"turn-action\"]",
                                "\"teachingUnitIds\":[\"turn-action\",\"turn-action\"]")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void exposesTypedSourceOwnershipToTheSectionModel() {
        UUID evidenceId = UUID.randomUUID();
        SectionRequest base = request(evidenceId, "Choose and resolve one available action.");
        SectionRequest typed = new SectionRequest(
                base.topicKey(),
                base.title(),
                base.objective(),
                base.coverageTags(),
                base.priorSections(),
                base.evidence(),
                base.pageImages(),
                List.of("R-action"),
                List.of(new TeachingUnitInput(
                        "action-unit",
                        List.of("R-action"),
                        List.of(evidenceId),
                        List.of(SourceCoverageRole.LEGAL_ACTION),
                        SourceCoverageAvailability.SOURCED)),
                base.modelConfigurationOwner(),
                base.chapterScope(),
                base.wholeGameContext());

        var modelUnits = model(mock(ChatModel.class)).modelTeachingUnits(typed);

        assertThat(modelUnits).singleElement().satisfies(unit -> {
            assertThat(unit.directEvidenceIds()).containsExactly("E1");
            assertThat(unit.roles()).containsExactly(SourceCoverageRole.LEGAL_ACTION);
            assertThat(unit.availability()).isEqualTo(SourceCoverageAvailability.SOURCED);
        });
    }

    @Test
    void tracesTheExactOrdinaryRuleEvidenceVisibleToEachSuccessfulLessonAttempt() {
        UUID evidenceId = UUID.randomUUID();
        String exactExcerpt = "A player takes exactly one available action.";
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(VALID));
        SpringAiTeachingLessonModel model = model(chatModel);
        RecordingCapture capture = new RecordingCapture();
        UUID operationId = UUID.randomUUID();

        model.composeInvocation(
                request(evidenceId, exactExcerpt),
                capture,
                context(operationId),
                1);

        assertThat(capture.starts).singleElement().satisfies(start -> {
            assertThat(start.attempt()).isEqualTo(1);
            assertThat(start.context().operationId()).isEqualTo(operationId);
        });
        assertThat(capture.turns).singleElement().satisfies(turn -> {
            assertThat(turn.assistantText()).isEqualTo(VALID);
            assertThat(turn.context().operationId()).isEqualTo(operationId);
        });
        assertThat(capture.toolCalls).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("provide_teaching_section_evidence");
            assertThat(call.canonicalArgumentsJson()).contains(evidenceId.toString());
            assertThat(call.context().operationId()).isEqualTo(operationId);
        });
        assertThat(capture.observations).singleElement().satisfies(observation -> {
            assertThat(observation.toolName()).isEqualTo("provide_teaching_section_evidence");
            assertThat(observation.evidenceCount()).isEqualTo(1);
            assertThat(observation.modelVisibleObservationJson()).contains(
                    "\"evidenceRef\":\"E1\"",
                    "\"chunkId\":\"" + evidenceId + "\"",
                    "\"excerpt\":\"" + exactExcerpt + "\"",
                    "\"pageFrom\":7",
                    "\"pageTo\":8");
            assertThat(observation.context().operationId()).isEqualTo(operationId);
        });
        assertThat(capture.failures).isEmpty();
    }

    @Test
    void correlatesAnOrdinaryLessonProviderFailureWithoutInventingAModelTurn() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("private provider detail"));
        SpringAiTeachingLessonModel model = model(chatModel);
        RecordingCapture capture = new RecordingCapture();
        UUID operationId = UUID.randomUUID();

        assertThatThrownBy(() -> model.composeInvocation(
                        request(UUID.randomUUID(), "The ordinary rule remains exact."),
                        capture,
                        context(operationId),
                        1))
                .isInstanceOf(IllegalStateException.class);

        assertThat(capture.starts).singleElement()
                .extracting(start -> start.context().operationId())
                .isEqualTo(operationId);
        assertThat(capture.turns).isEmpty();
        assertThat(capture.observations).singleElement()
                .extracting(observation -> observation.context().operationId())
                .isEqualTo(operationId);
        assertThat(capture.failures).singleElement().satisfies(failure -> {
            assertThat(failure.signal()).isEqualTo(LifecycleSignal.FAILURE);
            assertThat(failure.code()).isEqualTo("TEACHING_MODEL_CALL_FAILED");
            assertThat(failure.context().operationId()).isEqualTo(operationId);
        });
    }

    private SpringAiTeachingLessonModel model(ChatModel chatModel) {
        RuntimeModelConfiguration models = mock(RuntimeModelConfiguration.class);
        when(models.modelFor(Role.TEACHING, "owner")).thenReturn(chatModel);
        when(models.providerFor(Role.TEACHING, "owner")).thenReturn("test-provider");
        when(models.modelNameFor(Role.TEACHING, "owner")).thenReturn("test-model");
        when(models.usesFake(Role.TEACHING, "owner")).thenReturn(false);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(prompts.teachingRuntimeSystem()).thenReturn("Return the typed lesson JSON.");
        when(prompts.teachingUser()).thenReturn("Section {section}; evidence {evidence}");
        return new SpringAiTeachingLessonModel(models, prompts);
    }

    private SectionRequest request(UUID evidenceId, String excerpt) {
        return new SectionRequest(
                "ordinary-rules",
                "Ordinary rules",
                "Teach one source-bound rule",
                List.of("core_loop"),
                List.of(),
                List.of(new EvidenceInput(
                        evidenceId,
                        "RULE",
                        "Available action",
                        excerpt,
                        7,
                        8)),
                List.of(),
                List.of(),
                List.of(),
                "owner",
                "One ordinary rules chapter");
    }

    private TraceEventContext context(UUID operationId) {
        return TraceEventContext.create(
                Instant.now(),
                JourneyStage.TEACHING,
                operationId,
                null,
                new ResourceRef(ResourceType.TEACHING_RUN, UUID.randomUUID()));
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class RecordingCapture implements CaptureHandle {
        private final UUID traceId = UUID.randomUUID();
        private final List<AgentTraceEvent.ModelCallStarted> starts = new ArrayList<>();
        private final List<AgentTraceEvent.ModelTurn> turns = new ArrayList<>();
        private final List<AgentTraceEvent.ToolCall> toolCalls = new ArrayList<>();
        private final List<AgentTraceEvent.ToolObservation> observations = new ArrayList<>();
        private final List<AgentTraceEvent.BindingOrFailure> failures = new ArrayList<>();

        @Override public boolean enabled() { return true; }
        @Override public Optional<UUID> traceId() { return Optional.of(traceId); }
        @Override public void userTurn(AgentTraceEvent.UserTurn event) {}
        @Override public void modelCallStarted(AgentTraceEvent.ModelCallStarted event) { starts.add(event); }
        @Override public void modelTurn(AgentTraceEvent.ModelTurn event) { turns.add(event); }
        @Override public void toolCall(AgentTraceEvent.ToolCall event) { toolCalls.add(event); }
        @Override public void toolObservation(AgentTraceEvent.ToolObservation event) { observations.add(event); }
        @Override public void publication(AgentTraceEvent.Publication event) {}
        @Override public void bindingOrFailure(AgentTraceEvent.BindingOrFailure event) { failures.add(event); }
        @Override public boolean bind(ResourceRef resource) { return true; }
    }
}
