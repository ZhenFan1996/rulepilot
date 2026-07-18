package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.application.TeachingSectionKnowledge;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiTeachingLessonModel implements TeachingLessonModel {

    private final RuntimeModelConfiguration models;
    private final FakeTeachingLessonModel fakeModel;
    private final VersionedAgentPrompts prompts;

    public SpringAiTeachingLessonModel(
            RuntimeModelConfiguration models,
            FakeTeachingLessonModel fakeModel,
            VersionedAgentPrompts prompts) {
        this.models = models;
        this.fakeModel = fakeModel;
        this.prompts = prompts;
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
            return composeOnce(request, prompts.structuredOutputRepair());
        } catch (RuntimeException exception) {
            exception.addSuppressed(firstFailure);
            throw exception;
        }
    }

    private SectionDraft composeOnce(SectionRequest request, String repairInstruction) {
        var guidance = TeachingSectionKnowledge.forSection(request.sectionType());
        return ChatClient.create(models.modelFor(Role.TEACHING)).prompt()
                .system(prompts.teachingSystem())
                .user(user -> user.text(prompts.teachingUser())
                        .param("section", request.sectionType().name())
                        .param("objective", guidance.objective())
                        .param("coverage", guidance.coverageChecklist())
                        .param("players", request.playerCount())
                        .param("beginners", request.beginnerCount())
                        .param("totalDuration", request.totalDurationMinutes())
                        .param("sectionDuration", request.sectionDurationSeconds())
                        .param("maxSteps", request.maxSteps())
                        .param("continuity", request.priorSections())
                        .param("evidence", request.evidence())
                        .param("repair", repairInstruction))
                .call()
                .entity(SectionDraft.class);
    }
}
