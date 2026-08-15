package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiRuleAnswerModelTest {

    @Test
    void selectsConfiguredProviderWithoutCallingExternalApi() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.ANSWER)).thenReturn("deepseek");

        SpringAiRuleAnswerModel model =
                new SpringAiRuleAnswerModel(configuration, new FakeRuleAnswerModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.providerId()).isEqualTo("deepseek");
    }

    @Test
    void interpretsQuestionContextAsBoundedStructuredAgentOutput() {
        Fixture fixture = fixture("""
                {"questionType":"LESSON_STEP_FOLLOW_UP","referenceBinding":"PRIOR_GROUNDED_TURN",
                 "terms":["红色标记","这样"],"missingContext":[],
                 "learningIntent":"EXAMPLE","answerAid":"EXAMPLE",
                 "subquestions":[
                   {"questionSpan":"红色标记什么时候触发？","evidenceNeeds":["PRIOR_TURN"]},
                   {"questionSpan":"它也是这样吗？","evidenceNeeds":["DIRECT_RULE"]}
                 ]}
                """);

        var result = fixture.model.interpretQuestion(request());

        assertThat(result).hasValueSatisfying(draft -> {
            assertThat(draft.referenceBinding()).isEqualTo(ReferenceBinding.PRIOR_GROUNDED_TURN);
            assertThat(draft.terms()).containsExactly("红色标记", "这样");
            assertThat(draft.missingContext()).isEmpty();
            assertThat(draft.learningIntent()).isEqualTo(LearningIntent.EXAMPLE);
            assertThat(draft.subquestions()).hasSize(2);
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text)
                        .contains(
                                "NEEDS_CLARIFICATION",
                                "prior grounded conversation turn",
                                "never current rule evidence",
                                "subquestions",
                                "evidenceNeeds",
                                "copied verbatim",
                                "teaching move",
                                "learningIntent",
                                "GENERAL_QUESTION",
                                "fallback hint",
                                "PREVIOUS_QUESTION even if deterministicMissingContext",
                                "MUST contain between one and four",
                                "Use CALCULATION whenever",
                                "which of two proposed totals"));
    }

    @Test
    void interpretsNaturalStrategyLanguageAsAnAdviceEvidenceNeed() {
        Fixture fixture = fixture("""
                {"questionType":"RULE_QUERY","referenceBinding":"CURRENT_QUESTION","terms":["策略"],
                 "missingContext":[],"learningIntent":null,"answerAid":"NONE",
                 "subquestions":[{"questionSpan":"有没有赢的策略？","evidenceNeeds":["ADVICE"]}]}
                """);

        var result = fixture.model.interpretQuestion(new QuestionInterpretationRequest(
                "有没有赢的策略？",
                "",
                "",
                "",
                QuestionType.RULE_QUERY,
                Set.of(),
                PlayerLocale.ZH_CN));

        assertThat(result).hasValueSatisfying(draft -> assertThat(draft.subquestions().getFirst().evidenceNeeds())
                .containsExactly(EvidenceNeed.ADVICE));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text)
                        .contains(
                                "ADVICE",
                                "actually express guidance",
                                "objective, scoring rule, or legal action",
                                "never a learningIntent",
                                "active game and rulebook",
                                "victory conditions separate from advice",
                                "winning alone never creates an ADVICE need"));
    }

    @Test
    void repairsAValidJsonResponseThatConfusesAnEvidenceNeedWithLearningIntent() {
        Fixture fixture = fixture(
                """
                {"questionType":"RULE_QUERY","referenceBinding":"CURRENT_QUESTION","terms":["策略"],
                 "missingContext":[],"learningIntent":"ADVICE","answerAid":"NONE",
                 "subquestions":[{"questionSpan":"有没有赢的策略？","evidenceNeeds":["ADVICE"]}]}
                """,
                """
                {"questionType":"RULE_QUERY","referenceBinding":"CURRENT_QUESTION","terms":["策略"],
                 "missingContext":[],"learningIntent":null,"answerAid":"NONE",
                 "subquestions":[{"questionSpan":"有没有赢的策略？","evidenceNeeds":["ADVICE"]}]}
                """);

        var result = fixture.model.interpretQuestion(new QuestionInterpretationRequest(
                "有没有赢的策略？",
                "",
                "",
                "",
                QuestionType.RULE_QUERY,
                Set.of(),
                PlayerLocale.ZH_CN));

        assertThat(result).hasValueSatisfying(draft -> {
            assertThat(draft.learningIntent()).isNull();
            assertThat(draft.subquestions().getFirst().evidenceNeeds()).containsExactly(EvidenceNeed.ADVICE);
        });
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().getLast().getInstructions())
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text)
                        .contains("previous response could not be decoded", "ADVICE belongs only inside"));
    }

    @Test
    void repairPromptKeepsTimingAidOutOfTheEvidenceNeedEnum() {
        Fixture fixture = fixture(
                """
                {"questionType":"RULE_QUERY","referenceBinding":"CURRENT_QUESTION","terms":["when"],
                 "missingContext":[],"learningIntent":null,"answerAid":"TIMING",
                 "subquestions":[{"questionSpan":"when does Daylight end","evidenceNeeds":["TIMING"]}]}
                """,
                """
                {"questionType":"RULE_QUERY","referenceBinding":"CURRENT_QUESTION","terms":["when"],
                 "missingContext":[],"learningIntent":null,"answerAid":"TIMING",
                 "subquestions":[{"questionSpan":"when does Daylight end","evidenceNeeds":["SEQUENCE"]}]}
                """);

        var result = fixture.model.interpretQuestion(new QuestionInterpretationRequest(
                "when does Daylight end",
                "",
                "",
                "",
                QuestionType.RULE_QUERY,
                Set.of(),
                PlayerLocale.EN));

        assertThat(result).hasValueSatisfying(draft -> {
            assertThat(draft.answerAid()).isEqualTo(AnswerAid.TIMING);
            assertThat(draft.subquestions().getFirst().evidenceNeeds())
                    .containsExactly(EvidenceNeed.SEQUENCE);
        });
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().getLast().getInstructions())
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text)
                        .contains(
                                "TIMING is not an evidenceNeed",
                                "timing/order",
                                "SEQUENCE",
                                "answerAid value",
                                "inside evidenceNeeds"));
    }

    @Test
    void rejectsQuestionInterpretationWithFieldsOutsideTheVersionedContract() {
        Fixture fixture = fixture("""
                {"questionType":"RULE_QUERY","referenceBinding":"CURRENT_QUESTION","terms":[],
                 "missingContext":[],"answerAid":"NONE","subquestions":[{"questionSpan":"它也是这样吗？","evidenceNeeds":["DIRECT_RULE"]}],
                 "answer":"invented rule fact"}
                """);

        assertThat(fixture.model.interpretQuestion(request())).isEmpty();
    }

    @Test
    void requestsJsonModeAndDisablesThinkingForDeepSeekInterpretation() {
        Fixture fixture = fixture("""
                {"questionType":"RULE_QUERY","referenceBinding":"PREVIOUS_QUESTION","terms":[],
                 "missingContext":[],"learningIntent":"SOURCE","answerAid":"SOURCE",
                 "subquestions":[{"questionSpan":"这条规则在规则书哪里？","evidenceNeeds":["DIRECT_RULE"]}]}
                """, true);

        assertThat(fixture.model.interpretQuestion(request())).isPresent();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(options.getMaxTokens()).isEqualTo(384);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(options.getExtraBody())
                .containsEntry("thinking", java.util.Map.of("type", "disabled"));
        assertThat(options.getTemperature()).isZero();
    }

    @Test
    void appliesSeparateConfiguredTemperaturesToGenerationAndInterpretation() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        UUID citation = UUID.randomUUID();
        when(configuration.usesFake(Role.ANSWER)).thenReturn(false);
        when(configuration.providerFor(Role.ANSWER)).thenReturn("qwen");
        when(configuration.modelNameFor(Role.ANSWER)).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.ANSWER)).thenReturn(chatModel);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.getOptions()).thenReturn(defaults);
        when(prompts.answerSystem("NONE")).thenReturn("Answer only from evidence.");
        when(prompts.answerUser()).thenReturn("{question}\n{evidence}\n{repair}");
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"answerable":true,"shortVerdict":"Yes.","explanation":"Direct rule.",
                         "citationIds":["%s"],"exceptions":[],"confidence":"HIGH",
                         "answerBasis":"DIRECT_RULE"}
                        """.formatted(citation))))));
        SpringAiRuleAnswerModel model = new SpringAiRuleAnswerModel(
                configuration, new FakeRuleAnswerModel(), prompts, 0.42, 0.07);

        model.compose(new ModelRequest(
                "Arbitrary question",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(citation, "RULE", "Rule", "Direct rule.", 2, 2))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(((OpenAiChatOptions) prompt.getValue().getOptions()).getTemperature())
                .isEqualTo(0.42);
    }

    @Test
    void rejectsInvalidConfiguredTemperatures() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);

        assertThatThrownBy(() -> new SpringAiRuleAnswerModel(
                        configuration, new FakeRuleAnswerModel(), prompts, Double.NaN, 0.0))
                .hasMessageContaining("answer model temperature");
        assertThatThrownBy(() -> new SpringAiRuleAnswerModel(
                        configuration, new FakeRuleAnswerModel(), prompts, 0.1, 2.1))
                .hasMessageContaining("answer interpretation");
    }

    private QuestionInterpretationRequest request() {
        return new QuestionInterpretationRequest(
                "它也是这样吗？",
                "",
                "红色标记什么时候触发？",
                "行动结束后触发。",
                QuestionType.SITUATION_QUERY,
                Set.of(MissingQuestionContext.REFERENCED_OBJECT),
                PlayerLocale.ZH_CN);
    }

    private Fixture fixture(String response) {
        return fixture(response, false);
    }

    private Fixture fixture(String firstResponse, String secondResponse) {
        return fixture(false, firstResponse, secondResponse);
    }

    private Fixture fixture(String response, boolean deepSeek) {
        return fixture(deepSeek, response);
    }

    private Fixture fixture(boolean deepSeek, String... responses) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.ANSWER)).thenReturn(false);
        when(configuration.modelFor(Role.ANSWER)).thenReturn(chatModel);
        if (deepSeek) {
            when(configuration.providerFor(Role.ANSWER)).thenReturn("deepseek");
            when(configuration.modelNameFor(Role.ANSWER)).thenReturn("deepseek-v4-flash");
            when(configuration.usesDeepSeekNonThinkingGeneration(Role.ANSWER)).thenReturn(true);
            OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                    .apiKey("test-key")
                    .baseUrl("https://provider.example/v1")
                    .model("deepseek-v4-flash")
                    .build();
            when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
            when(chatModel.getOptions()).thenReturn(providerOptions);
        } else {
            when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
            when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        }
        ChatResponse[] chatResponses = java.util.Arrays.stream(responses)
                .map(response -> new ChatResponse(List.of(new Generation(new AssistantMessage(response)))))
                .toArray(ChatResponse[]::new);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                chatResponses[0],
                java.util.Arrays.copyOfRange(chatResponses, 1, chatResponses.length));
        return new Fixture(
                new SpringAiRuleAnswerModel(
                        configuration, new FakeRuleAnswerModel(), mock(VersionedAgentPrompts.class)),
                chatModel);
    }

    private record Fixture(SpringAiRuleAnswerModel model, ChatModel chatModel) {}
}
