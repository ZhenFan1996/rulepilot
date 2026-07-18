package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.TeachingLessonModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiTeachingLessonModel implements TeachingLessonModel {

    private final RuntimeModelConfiguration models;
    private final FakeTeachingLessonModel fakeModel;
    private final String systemPrompt;

    public SpringAiTeachingLessonModel(
            RuntimeModelConfiguration models,
            FakeTeachingLessonModel fakeModel,
            @Value("classpath:prompts/teaching-agent-v1.txt") Resource promptResource) throws IOException {
        this.models = models;
        this.fakeModel = fakeModel;
        this.systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public String providerId() {
        return models.providerFor(Role.TEACHING);
    }

    @Override
    public SectionDraft compose(SectionRequest request) {
        if (models.usesFake(Role.TEACHING)) {
            return fakeModel.compose(request);
        }
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
        return ChatClient.create(models.modelFor(Role.TEACHING)).prompt()
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
}
