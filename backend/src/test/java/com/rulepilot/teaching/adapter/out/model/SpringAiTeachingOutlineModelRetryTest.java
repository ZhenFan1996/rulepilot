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
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
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
            verify(configuration).resolvedModelFor(Role.TEACHING, "player");
            verify(configuration, never()).modelFor(Role.TEACHING, "player");
            verify(configuration, never()).providerFor(Role.TEACHING, "player");
            verify(configuration, never()).modelNameFor(Role.TEACHING, "player");
            verify(configuration, never()).usesDeepSeekNonThinkingGeneration(Role.TEACHING, "player");
        } finally {
            model.close();
        }
    }

    private RuntimeModelConfiguration configuration() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.providerFor(Role.TEACHING, "player")).thenReturn("compatible");
        return configuration;
    }

    private ChatModel chatModel(RuntimeModelConfiguration configuration) {
        ChatModel model = mock(ChatModel.class);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
        when(configuration.resolvedModelFor(Role.TEACHING, "player"))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        model, "compatible", "teaching-test-model", false));
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

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
