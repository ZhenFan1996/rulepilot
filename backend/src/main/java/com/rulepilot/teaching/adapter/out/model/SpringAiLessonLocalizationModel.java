package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.LessonLocalizationModel;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.LessonLocalization.SectionTranslation;
import com.rulepilot.teaching.domain.LessonLocalization.StepTranslation;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiLessonLocalizationModel implements LessonLocalizationModel {

    private final RuntimeModelConfiguration models;
    private final VersionedAgentPrompts prompts;

    public SpringAiLessonLocalizationModel(RuntimeModelConfiguration models, VersionedAgentPrompts prompts) {
        this.models = models;
        this.prompts = prompts;
    }

    @Override
    public boolean available(String modelConfigurationOwner) {
        return !models.usesFake(Role.TEACHING, modelConfigurationOwner);
    }

    @Override
    public SectionTranslation translate(LessonSection section, PlayerLocale targetLanguage, String modelConfigurationOwner) {
        if (targetLanguage != PlayerLocale.EN || !available(modelConfigurationOwner)) {
            throw new IllegalStateException("lesson localization model is unavailable");
        }
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.TEACHING, modelConfigurationOwner)).prompt();
        String provider = models.providerFor(Role.TEACHING, modelConfigurationOwner);
        if (models.usesDeepSeekNonThinkingGeneration(Role.TEACHING, modelConfigurationOwner) || "qwen".equals(provider)) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(models.modelNameFor(Role.TEACHING, modelConfigurationOwner));
            if (models.usesDeepSeekNonThinkingGeneration(Role.TEACHING, modelConfigurationOwner)) {
                options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else {
                options.extraBody(Map.of("enable_thinking", false));
                options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
            }
            prompt = prompt.options(options);
        }
        SectionTranslationDraft draft = prompt
                .system(prompts.lessonLocalizationSystem())
                .user(user -> user.text(prompts.lessonLocalizationUser())
                        .param("targetLanguage", targetLanguage.promptName())
                        .param("section", SectionInput.from(section)))
                .call()
                .entity(SectionTranslationDraft.class);
        return toDomain(section, draft);
    }

    private SectionTranslation toDomain(LessonSection source, SectionTranslationDraft draft) {
        if (draft == null || draft.position() != source.position() || draft.steps() == null) {
            throw new IllegalArgumentException("localized lesson section is structurally invalid");
        }
        List<StepTranslation> steps = draft.steps().stream()
                .map(step -> new StepTranslation(
                        step.position(),
                        step.heading(),
                        step.text(),
                        step.visualLabel(),
                        step.visualDescription()))
                .toList();
        return new SectionTranslation(draft.position(), draft.title(), draft.visualCaption(), steps);
    }

    private record SectionInput(int position, String title, String visualCaption, List<StepInput> steps) {
        static SectionInput from(LessonSection section) {
            return new SectionInput(
                    section.position(),
                    section.title(),
                    section.visualCaption(),
                    section.steps().stream().map(StepInput::from).toList());
        }
    }

    private record StepInput(
            int position,
            String heading,
            String text,
            String visualLabel,
            String visualDescription) {
        static StepInput from(LessonStep step) {
            return new StepInput(
                    step.position(),
                    step.heading(),
                    step.text(),
                    step.visualFocus() == null ? "" : step.visualFocus().label(),
                    step.visualFocus() == null ? "" : step.visualFocus().visibleDescription());
        }
    }

    private record SectionTranslationDraft(int position, String title, String visualCaption, List<StepTranslationDraft> steps) {}

    private record StepTranslationDraft(
            int position,
            String heading,
            String text,
            String visualLabel,
            String visualDescription) {}
}
