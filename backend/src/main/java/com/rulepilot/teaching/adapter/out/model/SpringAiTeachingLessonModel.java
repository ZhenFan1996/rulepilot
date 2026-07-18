package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingLessonModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "rulepilot.teaching.provider", havingValue = "spring-ai")
public class SpringAiTeachingLessonModel implements TeachingLessonModel {

    private final ChatClient chatClient;
    private final String providerId;
    private final String systemPrompt;

    public SpringAiTeachingLessonModel(
            Map<String, ChatModel> models,
            @Value("${rulepilot.teaching.model-provider}") String provider,
            @Value("classpath:prompts/teaching-agent-v1.txt") Resource promptResource) throws IOException {
        this.providerId = providerId(provider);
        this.chatClient = ChatClient.create(requireModel(models, providerId));
        this.systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public SectionDraft compose(SectionRequest request) {
        RuntimeException firstFailure;
        try {
            return composeOnce(request, "");
        } catch (RuntimeException exception) {
            firstFailure = exception;
        }
        try {
            return composeOnce(request, "The previous output was invalid. Repair it to match the schema exactly.");
        } catch (RuntimeException exception) {
            exception.addSuppressed(firstFailure);
            throw exception;
        }
    }

    private SectionDraft composeOnce(SectionRequest request, String repairInstruction) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(user -> user.text("""
                                Section: {section}
                                Audience: {players} players, {beginners} beginners
                                Total lesson duration: {duration} minutes
                                Evidence data: {evidence}
                                {repair}
                                """)
                        .param("section", request.sectionType().name())
                        .param("players", request.playerCount())
                        .param("beginners", request.beginnerCount())
                        .param("duration", request.durationMinutes())
                        .param("evidence", request.evidence())
                        .param("repair", repairInstruction))
                .call()
                .entity(SectionDraft.class);
    }

    private static ChatModel requireModel(Map<String, ChatModel> models, String provider) {
        ChatModel model = models.get(provider);
        if (model == null) {
            throw new IllegalStateException("chat model provider '" + provider + "' is not enabled");
        }
        return model;
    }

    private static String providerId(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("teaching model provider is required");
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }
}
