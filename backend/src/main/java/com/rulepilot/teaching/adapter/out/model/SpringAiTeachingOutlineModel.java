package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.application.SourceLanguageRetrievalPolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
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
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();

    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models, VersionedAgentPrompts prompts, FakeTeachingOutlineModel fake) {
        this.models = models;
        this.prompts = prompts;
        this.fake = fake;
    }

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        Role role = roleFor(request);
        if (models.usesFake(role)) return fake.organize(request);
        RuntimeException firstFailure;
        try {
            return organizeOnce(request, role, "");
        } catch (RuntimeException failure) {
            firstFailure = failure;
            log.warn("First teaching-outline model response failed: {}", failure.getMessage());
        }
        try {
            String correction = "The previous outline failed schema or retrieval-language validation. "
                    + "Rebuild the complete outline. Retrieval queries must copy exact terms from the rulebook's "
                    + "source language; player-facing fields remain Simplified Chinese.\n"
                    + prompts.structuredOutputRepair();
            return organizeOnce(request, role, correction);
        } catch (RuntimeException failure) {
            log.warn("Repaired teaching-outline model response failed: {}", failure.getMessage());
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    private OutlineDraft organizeOnce(OutlineRequest request, Role role, String repair) {
        OutlineDraft outline = ChatClient.create(models.modelFor(role)).prompt()
                .system(prompts.teachingOutlineSystem())
                .user(user -> {
                    user.text(prompts.teachingOutlineUser())
                            .param("players", request.playerCount())
                            .param("beginners", request.beginnerCount())
                            .param("duration", request.durationMinutes())
                            .param("pages", request.pages())
                            .param("visualPages", request.pageImages().stream()
                                    .map(TeachingOutlineModel.PageImageInput::pageNumber)
                                    .toList())
                            .param("repair", repair);
                    if (role == Role.VISUAL) {
                        request.pageImages().stream().map(images::prepare).forEach(image -> user.media(
                                MimeTypeUtils.parseMimeType(image.mediaType()), new ByteArrayResource(image.content())));
                    }
                })
                .call()
                .entity(OutlineDraft.class);
        if (outline == null) throw new IllegalArgumentException("teaching outline model returned no draft");
        SourceLanguageRetrievalPolicy.validate(request, outline);
        return outline;
    }

    private Role roleFor(OutlineRequest request) {
        return !request.pageImages().isEmpty() && models.supportsVision(Role.VISUAL) ? Role.VISUAL : Role.TEACHING;
    }

}
