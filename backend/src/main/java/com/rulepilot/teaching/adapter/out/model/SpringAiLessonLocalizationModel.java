package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.LessonLocalizationModel;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.LessonLocalization.SectionTranslation;
import com.rulepilot.teaching.domain.LessonLocalization.RuleFactTranslation;
import com.rulepilot.teaching.domain.LessonLocalization.StepTranslation;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiLessonLocalizationModel implements LessonLocalizationModel {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String TRACE_LOCALIZATION_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"position\",\"title\",\"visualCaption\",\"steps\"],\"additionalProperties\":false}";

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
        return translate(
                section,
                targetLanguage,
                modelConfigurationOwner,
                CaptureHandle.noop(),
                null,
                1);
    }

    @Override
    public SectionTranslation translate(
            LessonSection section,
            PlayerLocale targetLanguage,
            String modelConfigurationOwner,
            CaptureHandle capture,
            TraceEventContext context,
            int attempt) {
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
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
        SectionTranslationDraft draft;
        try {
            captureModelStarted(trace, context, modelConfigurationOwner, section, attempt);
            String content = prompt
                    .system(prompts.lessonLocalizationSystem())
                    .user(user -> user.text(prompts.lessonLocalizationUser())
                            .param("targetLanguage", targetLanguage.promptName())
                            .param("section", SectionInput.from(section)))
                    .call()
                    .content();
            captureModelTurn(trace, context, modelConfigurationOwner, content, attempt);
            draft = parseSectionTranslation(content);
        } catch (JsonProcessingException invalid) {
            captureFailure(trace, context, "LESSON_LOCALIZATION_INVALID_JSON");
            throw new IllegalArgumentException("lesson localization returned invalid structured output", invalid);
        } catch (RuntimeException failure) {
            captureFailure(trace, context, "LESSON_LOCALIZATION_MODEL_FAILED");
            throw failure;
        }
        try {
            return toDomain(section, draft);
        } catch (RuntimeException invalidContract) {
            captureFailure(trace, context, "LESSON_LOCALIZATION_CONTRACT_REJECTED");
            throw invalidContract;
        }
    }

    private void captureModelStarted(
            CaptureHandle capture,
            TraceEventContext context,
            String owner,
            LessonSection section,
            int attempt) {
        if (context == null) return;
        capture(capture, () -> capture.modelCallStarted(new ModelCallStarted(
                freshContext(context),
                models.providerFor(Role.TEACHING, owner),
                models.modelNameFor(Role.TEACHING, owner),
                attempt,
                "lesson-localization-v1",
                "lesson-localization-v1",
                sha256(TRACE_LOCALIZATION_SCHEMA),
                Math.max(1, section.toString().length() / 4),
                4_096)));
    }

    private void captureModelTurn(
            CaptureHandle capture,
            TraceEventContext context,
            String owner,
            String content,
            int attempt) {
        if (context == null) return;
        capture(capture, () -> capture.modelTurn(new ModelTurn(
                freshContext(context),
                models.providerFor(Role.TEACHING, owner),
                models.modelNameFor(Role.TEACHING, owner),
                attempt,
                content == null ? "" : content,
                List.of(),
                "RESPONSE_RECEIVED",
                0,
                0,
                content == null || content.isBlank())));
    }

    private void captureFailure(CaptureHandle capture, TraceEventContext context, String code) {
        if (context == null) return;
        capture(capture, () -> capture.bindingOrFailure(new BindingOrFailure(
                freshContext(context),
                LifecycleSignal.FAILURE,
                code,
                context.resource(),
                null)));
    }

    private void capture(CaptureHandle capture, Runnable emission) {
        try {
            if (capture != null && capture.enabled()) emission.run();
        } catch (RuntimeException ignored) {
            // Localization trace diagnostics never change the cached source-bound projection.
        }
    }

    private TraceEventContext freshContext(TraceEventContext context) {
        return new TraceEventContext(
                UUID.randomUUID(),
                Instant.now(),
                context.stage(),
                context.operationId(),
                context.parentOperationId(),
                context.resource());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    static SectionTranslationDraft parseSectionTranslation(String content) throws JsonProcessingException {
        return JSON.readValue(content, SectionTranslationDraft.class);
    }

    static SectionTranslation toDomain(LessonSection source, SectionTranslationDraft draft) {
        if (draft == null || draft.position() != source.position() || draft.steps() == null) {
            throw new IllegalArgumentException("localized lesson section is structurally invalid");
        }
        Map<Integer, LessonStep> sourceSteps = new HashMap<>();
        source.steps().forEach(step -> sourceSteps.put(step.position(), step));
        LinkedHashSet<Integer> translatedStepPositions = new LinkedHashSet<>();
        List<StepTranslation> steps = draft.steps().stream()
                .map(step -> {
                    if (step == null || !translatedStepPositions.add(step.position())) {
                        throw new IllegalArgumentException("localized lesson duplicated or omitted a step position");
                    }
                    LessonStep sourceStep = sourceSteps.get(step.position());
                    if (sourceStep == null) {
                        throw new IllegalArgumentException("localized lesson step position is invalid");
                    }
                    boolean hasVisual = sourceStep.visualFocus() != null;
                    if (step.visualLabel() == null || step.visualDescription() == null
                            || (hasVisual && (step.visualLabel().isBlank() || step.visualDescription().isBlank()))
                            || (!hasVisual && (!step.visualLabel().isEmpty() || !step.visualDescription().isEmpty()))) {
                        throw new IllegalArgumentException("localized lesson visual fields do not match the source step");
                    }
                    String visualLabel = step.visualLabel().strip();
                    String visualDescription = step.visualDescription().strip();
                    Map<Integer, com.rulepilot.teaching.domain.IllustratedLesson.RuleFact> sourceFacts = new HashMap<>();
                    sourceStep.ruleFacts().forEach(fact -> sourceFacts.put(fact.position(), fact));
                    if (step.ruleFacts() == null) {
                        throw new IllegalArgumentException("localized lesson omitted rule facts");
                    }
                    LinkedHashSet<Integer> translatedFactPositions = new LinkedHashSet<>();
                    List<RuleFactTranslation> translatedFacts = step.ruleFacts().stream()
                            .map(fact -> {
                                if (fact == null || !sourceFacts.containsKey(fact.position())
                                        || !translatedFactPositions.add(fact.position())) {
                                    throw new IllegalArgumentException(
                                            "localized rule fact position is invalid");
                                }
                                return new RuleFactTranslation(fact.position(), fact.text());
                            })
                            .toList();
                    if (!translatedFactPositions.equals(sourceFacts.keySet())) {
                        throw new IllegalArgumentException("localized lesson omitted a rule fact");
                    }
                    return new StepTranslation(
                            step.position(),
                            step.heading(),
                            step.text(),
                            visualLabel,
                            visualDescription,
                            translatedFacts);
                })
                .toList();
        if (!translatedStepPositions.equals(sourceSteps.keySet())) {
            throw new IllegalArgumentException("localized lesson omitted a step");
        }
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
            String visualDescription,
            List<RuleFactInput> ruleFacts) {
        static StepInput from(LessonStep step) {
            return new StepInput(
                    step.position(),
                    step.heading(),
                    step.text(),
                    step.visualFocus() == null ? "" : step.visualFocus().label(),
                    step.visualFocus() == null ? "" : step.visualFocus().visibleDescription(),
                    step.ruleFacts().stream()
                            .map(fact -> new RuleFactInput(fact.position(), fact.role(), fact.text()))
                            .toList());
        }
    }

    private record RuleFactInput(
            int position,
            com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole role,
            String text) {}

    record SectionTranslationDraft(int position, String title, String visualCaption, List<StepTranslationDraft> steps) {}

    record StepTranslationDraft(
            int position,
            String heading,
            String text,
            String visualLabel,
            String visualDescription,
            List<RuleFactTranslationDraft> ruleFacts) {}

    record RuleFactTranslationDraft(int position, String text) {}
}
