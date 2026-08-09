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
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.ANSWER)).thenReturn(false);
        when(configuration.modelFor(Role.ANSWER)).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage(response)))));
        return new Fixture(
                new SpringAiRuleAnswerModel(
                        configuration, new FakeRuleAnswerModel(), mock(VersionedAgentPrompts.class)),
                chatModel);
    }

    private record Fixture(SpringAiRuleAnswerModel model, ChatModel chatModel) {}
}
