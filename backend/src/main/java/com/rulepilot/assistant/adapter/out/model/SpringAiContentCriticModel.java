package com.rulepilot.assistant.adapter.out.model;

import com.rulepilot.assistant.ContentCriticModel;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiContentCriticModel implements ContentCriticModel {

    private final RuntimeModelConfiguration models;
    private final FakeContentCriticModel fakeModel;
    private final VersionedAgentPrompts prompts;

    public SpringAiContentCriticModel(
            RuntimeModelConfiguration models, FakeContentCriticModel fakeModel, VersionedAgentPrompts prompts) {
        this.models = models;
        this.fakeModel = fakeModel;
        this.prompts = prompts;
    }

    @Override
    public String providerId() {
        return models.providerFor(Role.CRITIC);
    }

    @Override
    public CritiqueDraft critique(ReviewRequest request) {
        if (models.usesFake(Role.CRITIC)) {
            return fakeModel.critique(request);
        }
        RuntimeException firstFailure;
        try {
            return critiqueOnce(request, "");
        } catch (RuntimeException exception) {
            firstFailure = exception;
        }
        try {
            return critiqueOnce(request, prompts.structuredOutputRepair());
        } catch (RuntimeException exception) {
            exception.addSuppressed(firstFailure);
            throw exception;
        }
    }

    private CritiqueDraft critiqueOnce(ReviewRequest request, String repair) {
        return ChatClient.create(models.modelFor(Role.CRITIC)).prompt()
                .system(prompts.criticSystem())
                .user(user -> user.text(prompts.criticUser())
                        .param("type", request.contentType())
                        .param("claims", request.claims())
                        .param("evidence", request.evidence())
                        .param("repair", repair))
                .call()
                .entity(CritiqueDraft.class);
    }
}
