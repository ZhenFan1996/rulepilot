package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiTeachingOutlineModelRetryTest {

    @Test
    void letsOneAgentReadPublishAndFinishWhileKeepingUnresolvedEvidenceVisible() {
        Fixture fixture = fixture();
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(
                response("""
                        {"action":"read_pages","pageNumbers":[2],"reason":"Need repair rules","note":"additive field"}
                        """),
                response("""
                        {"action":"publish_chapter","chapter":{"key":"play-turn","title":"进行回合",
                        "objective":"完成一个回合","sourcePageNumbers":[1],"visualEvidenceRecommended":false,
                        "afterChapterIds":[],"providerMetadata":{"ignored":true}},"reason":"Page 1 supports it"}
                        """),
                response("""
                        {"action":"complete","gameTitle":"示例游戏","premise":"轮流行动并维护系统。",
                        "coveredChapterIds":["play-turn"],"unresolvedTopics":["维修细节仍需补充"],
                        "reason":"Publish the readable chapter and report the gap"}
                        """));

        var outline = fixture.model.organize(request());

        assertThat(outline.topics()).singleElement().satisfies(topic -> {
            assertThat(topic.key()).isEqualTo("play-turn");
            assertThat(topic.sourcePageNumbers()).containsExactly(1);
        });
        assertThat(outline.unresolvedTopics())
                .containsExactly("维修细节仍需补充", "Read rulebook page not used by any chapter 2");
        verify(fixture.chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void returnsTheWholeRejectedJsonAndTypedBoundaryToTheSameAgent() {
        Fixture fixture = fixture();
        String invalid = """
                {"action":"publish_chapter","chapter":{"key":"bad_key","title":"坏标识",
                "objective":"测试","sourcePageNumbers":[1],"visualEvidenceRecommended":false,
                "afterChapterIds":[]},"reason":"try"}
                """;
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(
                response(invalid),
                response("""
                        {"action":"publish_chapter","chapter":{"key":"play-turn","title":"进行回合",
                        "objective":"完成一个回合","sourcePageNumbers":[1],"visualEvidenceRecommended":false,
                        "afterChapterIds":[]},"reason":"correct identity"}
                        """),
                response("""
                        {"action":"complete","gameTitle":"示例游戏","premise":"轮流行动。",
                        "coveredChapterIds":["play-turn"],"unresolvedTopics":[],"reason":"done"}
                        """));

        assertThat(fixture.model.organize(onePageRequest()).topics()).singleElement();

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel, times(3)).call(prompts.capture());
        String repairState = promptText(prompts.getAllValues().get(1));
        assertThat(repairState)
                .contains("\"code\":\"INVALID_CHAPTER_ID\"")
                .contains("\"path\":\"$.chapter.key\"")
                .contains("\"reason\":\"chapter key must be unique kebab-case\"")
                .contains("\"schema\":")
                .contains("\"candidateJson\":")
                .contains("bad_key")
                .contains("\"allowedPageIds\":[\"page-1\"]")
                .contains("\"allowedChapterIds\":[]");
    }

    @Test
    void finishingBeforeReadingAvailablePagesKeepsTheChapterAndMarksItDegraded() {
        Fixture fixture = fixture();
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(
                response("""
                        {"action":"publish_chapter","chapter":{"key":"setup","title":"设置",
                        "objective":"完成设置","sourcePageNumbers":[1],"visualEvidenceRecommended":false,
                        "afterChapterIds":[]},"reason":"Page 1 is enough for this chapter"}
                        """),
                response("""
                        {"action":"complete","gameTitle":"示例游戏","premise":"先设置。",
                        "coveredChapterIds":["setup"],"unresolvedTopics":[],"reason":"done for now"}
                        """));

        var outline = fixture.model.organize(request());

        assertThat(outline.topics()).singleElement();
        assertThat(outline.unresolvedTopics())
                .containsExactly("Unread available rulebook page 2");
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel, times(2)).call(prompts.capture());
        assertThat(promptText(prompts.getAllValues().get(1)))
                .contains("\"outputLocale\":\"zh-CN\"")
                .contains("\"unreadAvailablePageIds\":[2]")
                .contains("\"publishedChapters\":[{");
    }

    @Test
    void transportFailureIsTerminalAndIsNotReplayedAsSchemaRepair() {
        Fixture fixture = fixture();
        when(fixture.chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("provider stalled", new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> fixture.model.organize(request()))
                .isInstanceOf(OutlineGenerationException.class)
                .hasRootCauseMessage("read timed out");
        verify(fixture.chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void deepSeekActionsPreserveTheConfiguredRequestBoundaryAndDisableThinking() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        Duration configuredTimeout = Duration.ofMinutes(5);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .model("deepseek-test-model")
                .timeout(configuredTimeout)
                .build();
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.resolvedModelFor(Role.TEACHING, "player"))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "deepseek", "deepseek-test-model", true));
        when(chatModel.getOptions()).thenReturn(defaults);
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("""
                        {"action":"publish_chapter","chapter":{"key":"play-turn","title":"进行回合",
                        "objective":"完成一个回合","sourcePageNumbers":[1],"visualEvidenceRecommended":false,
                        "afterChapterIds":[]},"reason":"Page 1 supports it"}
                        """),
                response("""
                        {"action":"complete","gameTitle":"示例游戏","premise":"轮流行动。",
                        "coveredChapterIds":["play-turn"],"unresolvedTopics":[],"reason":"done"}
                        """));
        SpringAiTeachingOutlineModel subject = new SpringAiTeachingOutlineModel(
                configuration,
                mock(VersionedAgentPrompts.class),
                0.1,
                "Choose one action as JSON.",
                "Goal={learningGoal}\nState={state}");

        assertThat(subject.organize(onePageRequest()).topics()).singleElement();

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(prompt -> {
            assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
            OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
            assertThat(options.getTimeout()).isEqualTo(configuredTimeout);
            assertThat(options.getExtraBody()).containsEntry("thinking", Map.of("type", "disabled"));
            assertThat(options.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        });
    }

    private static String promptText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(message -> message.getText())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static Fixture fixture() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.resolvedModelFor(Role.TEACHING, "player"))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "compatible", "teaching-test-model", false));
        when(chatModel.getDefaultOptions()).thenReturn(options);
        when(chatModel.getOptions()).thenReturn(options);
        return new Fixture(
                chatModel,
                new SpringAiTeachingOutlineModel(
                        configuration,
                        mock(VersionedAgentPrompts.class),
                        0.1,
                        "Choose one action as JSON.",
                        "Goal={learningGoal}\nState={state}"));
    }

    private static OutlineRequest request() {
        return new OutlineRequest(
                List.of(
                        new PageInput(1, "SETUP: Place one marker. TAKE TURN: Move one marker."),
                        new PageInput(2, "SYSTEMS AND REPAIR: Repair a damaged system.")),
                List.of(),
                "player");
    }

    private static OutlineRequest onePageRequest() {
        return new OutlineRequest(
                List.of(new PageInput(1, "TAKE TURN: Move one marker.")),
                List.of(),
                "player");
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private record Fixture(ChatModel chatModel, SpringAiTeachingOutlineModel model) {}
}
