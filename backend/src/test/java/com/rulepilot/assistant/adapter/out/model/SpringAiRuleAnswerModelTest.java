package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.util.List;
import java.util.Set;
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
                 "learningIntent":"EXAMPLE",
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
                                "MUST contain between one and four"));
    }

    @Test
    void rejectsQuestionInterpretationWithFieldsOutsideTheVersionedContract() {
        Fixture fixture = fixture("""
                {"questionType":"RULE_QUERY","referenceBinding":"CURRENT_QUESTION","terms":[],
                 "missingContext":[],"subquestions":[{"questionSpan":"它也是这样吗？","evidenceNeeds":["DIRECT_RULE"]}],
                 "answer":"invented rule fact"}
                """);

        assertThat(fixture.model.interpretQuestion(request())).isEmpty();
    }

    @Test
    void requestsJsonModeAndDisablesThinkingForDeepSeekInterpretation() {
        Fixture fixture = fixture("""
                {"questionType":"RULE_QUERY","referenceBinding":"PREVIOUS_QUESTION","terms":[],
                 "missingContext":[],"learningIntent":"SOURCE",
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

    private Fixture fixture(String response, boolean deepSeek) {
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
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage(response)))));
        return new Fixture(
                new SpringAiRuleAnswerModel(
                        configuration, new FakeRuleAnswerModel(), mock(VersionedAgentPrompts.class)),
                chatModel);
    }

    private record Fixture(SpringAiRuleAnswerModel model, ChatModel chatModel) {}
}
