package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Primary
public class SpringAiTeachingOutlineModel implements TeachingOutlineModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTeachingOutlineModel.class);

    private final RuntimeModelConfiguration models;
    private final VersionedAgentPrompts prompts;
    private final FakeTeachingOutlineModel fake;

    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models, VersionedAgentPrompts prompts, FakeTeachingOutlineModel fake) {
        this.models = models;
        this.prompts = prompts;
        this.fake = fake;
    }

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        if (models.usesFake(Role.TEACHING)) return fake.organize(request);
        RuntimeException firstFailure;
        try {
            return organizeOnce(request, "");
        } catch (RuntimeException failure) {
            firstFailure = failure;
            log.warn("First teaching-outline model response failed: {}", failure.getMessage());
        }
        try {
            return organizeOnce(request, prompts.structuredOutputRepair());
        } catch (RuntimeException failure) {
            log.warn("Repaired teaching-outline model response failed: {}", failure.getMessage());
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    private OutlineDraft organizeOnce(OutlineRequest request, String repair) {
        return ChatClient.create(models.modelFor(Role.TEACHING)).prompt()
                .system(prompts.teachingOutlineSystem())
                .user(user -> user.text(prompts.teachingOutlineUser())
                        .param("players", request.playerCount())
                        .param("beginners", request.beginnerCount())
                        .param("duration", request.durationMinutes())
                        .param("pages", request.pages())
                        .param("repair", repair))
                .call()
                .entity(OutlineDraft.class);
    }
}
