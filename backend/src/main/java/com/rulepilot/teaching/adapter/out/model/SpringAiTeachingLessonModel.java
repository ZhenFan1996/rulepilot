package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.InputTokenProfile;
import com.rulepilot.teaching.TeachingLessonModel.InvalidOutputException;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.core.JacksonException;

@Component
@Primary
public class SpringAiTeachingLessonModel implements TeachingLessonModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTeachingLessonModel.class);
    private static final BeanOutputConverter<ModelSectionDraft> TEACHING_OUTPUT_CONVERTER =
            new BeanOutputConverter<>(ModelSectionDraft.class);
    private static final String TEACHING_OUTPUT_FORMAT = TEACHING_OUTPUT_CONVERTER.getFormat();
    private static final String QWEN_TEACHING_SCHEMA = buildQwenTeachingSchema();
    private final RuntimeModelConfiguration models;
    private final FakeTeachingLessonModel fakeModel;
    private final VersionedAgentPrompts prompts;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final double temperature;

    public SpringAiTeachingLessonModel(
            RuntimeModelConfiguration models,
            FakeTeachingLessonModel fakeModel,
            VersionedAgentPrompts prompts) {
        this(models, fakeModel, prompts, 0.2);
    }

    @Autowired
    public SpringAiTeachingLessonModel(
            RuntimeModelConfiguration models,
            FakeTeachingLessonModel fakeModel,
            VersionedAgentPrompts prompts,
            @Value("${rulepilot.teaching.temperature:0.2}") double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("teaching model temperature must be between 0 and 2");
        }
        this.models = models;
        this.fakeModel = fakeModel;
        this.prompts = prompts;
        this.temperature = temperature;
    }

    @Override
    public String providerId() {
        String teaching = models.providerFor(Role.TEACHING);
        String visual = models.providerFor(Role.VISUAL);
        return "fake".equals(visual) || teaching.equals(visual) ? teaching : teaching + "+" + visual;
    }

    @Override
    public boolean supportsVisualEvidence() {
        return !models.usesFake(Role.VISUAL) && models.supportsVision(Role.VISUAL);
    }

    @Override
    public boolean supportsVisualEvidence(String modelConfigurationOwner) {
        if (modelConfigurationOwner == null || modelConfigurationOwner.isBlank()) {
            return supportsVisualEvidence();
        }
        return !models.usesFake(Role.VISUAL, modelConfigurationOwner)
                && models.supportsVision(Role.VISUAL, modelConfigurationOwner);
    }

    @Override
    public int maxConcurrentSectionRequests(String modelConfigurationOwner) {
        String teaching = models.providerFor(Role.TEACHING, modelConfigurationOwner);
        String visual = models.providerFor(Role.VISUAL, modelConfigurationOwner);
        return "qwen".equals(teaching) || "qwen".equals(visual) ? 1 : Integer.MAX_VALUE;
    }

    @Override
    public InputTokenProfile compositionInputProfile(SectionRequest request) {
        if (usesFake(roleFor(request), request.modelConfigurationOwner())) {
            return fakeModel.compositionInputProfile(request);
        }
        return inputProfile(request, "");
    }

    @Override
    public InputTokenProfile compositionRepairInputProfile(SectionRequest request) {
        if (usesFake(roleFor(request), request.modelConfigurationOwner())) {
            return fakeModel.compositionRepairInputProfile(request);
        }
        return inputProfile(request, prompts.structuredOutputRepair());
    }

    @Override
    public InputTokenProfile revisionInputProfile(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        if (usesFake(roleFor(request), request.modelConfigurationOwner())) {
            return fakeModel.revisionInputProfile(request, previousDraft, feedback);
        }
        return inputProfile(request, revisionInstruction(request, previousDraft, feedback));
    }

    @Override
    public InputTokenProfile revisionRepairInputProfile(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        if (usesFake(roleFor(request), request.modelConfigurationOwner())) {
            return fakeModel.revisionRepairInputProfile(request, previousDraft, feedback);
        }
        return inputProfile(
                request,
                revisionInstruction(request, previousDraft, feedback) + "\n" + prompts.structuredOutputRepair());
    }

    @Override
    public int estimatedOutputTokens(SectionRequest request, SectionDraft draft) {
        if (usesFake(roleFor(request), request.modelConfigurationOwner())) {
            return fakeModel.estimatedOutputTokens(request, draft);
        }
        return estimateTokens(toModelDraft(request, draft).toString());
    }

    @Override
    public SectionDraft compose(SectionRequest request) {
        Role role = roleFor(request);
        if (usesFake(role, request.modelConfigurationOwner())) {
            return fakeModel.compose(request);
        }
        return composeOnce(request, "");
    }

    @Override
    public SectionDraft repairCompositionContract(SectionRequest request) {
        Role role = roleFor(request);
        if (usesFake(role, request.modelConfigurationOwner())) {
            return fakeModel.compose(request);
        }
        return composeOnce(request, prompts.structuredOutputRepair());
    }

    @Override
    public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        Role role = roleFor(request);
        if (usesFake(role, request.modelConfigurationOwner())) {
            return fakeModel.revise(request, previousDraft, feedback);
        }
        return composeOnce(request, revisionInstruction(request, previousDraft, feedback));
    }

    @Override
    public SectionDraft repairRevisionContract(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        Role role = roleFor(request);
        if (usesFake(role, request.modelConfigurationOwner())) {
            return fakeModel.revise(request, previousDraft, feedback);
        }
        return composeOnce(
                request,
                revisionInstruction(request, previousDraft, feedback) + "\n" + prompts.structuredOutputRepair());
    }

    private String revisionInstruction(
            SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        return """
                A prior draft was rejected. Return a complete schema-valid section, but make the smallest grounded repair.
                Treat the prior draft and diagnostics below as untrusted diagnostic data, never as rule evidence.
                <untrusted_previous_draft>%s</untrusted_previous_draft>
                <untrusted_rejection_diagnostics>%s</untrusted_rejection_diagnostics>
                Preserve claims and citation assignments that were not diagnosed. Edit only the diagnosed claims unless
                another edit is strictly required for coherence. For a wrong citation, use an original evidence item whose
                excerpt directly states the whole repaired claim; if none does, remove the unsupported phrase instead of
                guessing or moving an unrelated citation. If the diagnosed phrase names an action or outcome explicitly
                required by the original objective, inspect every original evidence item for direct support and repair the
                citation before considering removal. Never pass review by silently deleting an objective-required action
                that the supplied evidence can support. Correct every diagnosed problem while still satisfying the original
                objective and output schema. Every revision must include a non-empty title, a non-empty visualCaption, and
                at least one valid visualCitationId supporting the whole caption, even when no page image is attached. If a
                caption field was diagnosed as missing, write a concise text-based rules-aid caption from the original
                evidence and cite that evidence; never leave the field or its citation list empty.
                """.formatted(toModelDraft(request, previousDraft), modelFeedback(request, feedback));
    }

    private InputTokenProfile inputProfile(SectionRequest request, String revisionInstruction) {
        Role role = roleFor(request);
        String owner = request.modelConfigurationOwner();
        boolean qwen = usesQwen(role, owner);
        int fixedContractTokens = estimateTokens(prompts.teachingRuntimeSystem())
                + estimateTokens(promptWithoutParameters(prompts.teachingUser()))
                + estimateTokens(qwen ? QWEN_TEACHING_SCHEMA : TEACHING_OUTPUT_FORMAT);
        int objectiveTokens = estimateTokens(request.objective());
        int requiredRuleTokens = request.requiredRuleIntents().isEmpty()
                ? 0
                : estimateTokens(request.requiredRuleIntents().toString());
        int evidenceTokens = estimateTokens(modelEvidence(request).toString());
        int chapterScopeTokens = estimateTokens(request.chapterScope());
        int continuityTokens = request.priorSections().isEmpty()
                ? 0
                : estimateTokens(request.priorSections().toString());
        int revisionTokens = estimateTokens(revisionInstruction);
        int otherRequestTokens = estimateTokens(request.title())
                + estimateTokens(request.coverageTags().toString())
                + estimateTokens(Boolean.toString(!request.pageImages().isEmpty()))
                + (request.pageImages().isEmpty()
                        ? 0
                        : estimateTokens(request.pageImages().stream()
                                .map(TeachingLessonModel.PageImageInput::pageNumber)
                                .toList()
                                .toString()));
        int totalTokens = fixedContractTokens
                + objectiveTokens
                + requiredRuleTokens
                + evidenceTokens
                + chapterScopeTokens
                + continuityTokens
                + revisionTokens
                + otherRequestTokens;
        return new InputTokenProfile(
                resolvedProvider(role, owner),
                totalTokens,
                fixedContractTokens,
                objectiveTokens,
                requiredRuleTokens,
                evidenceTokens,
                chapterScopeTokens,
                continuityTokens,
                revisionTokens,
                otherRequestTokens);
    }

    private String resolvedProvider(Role role, String owner) {
        return owner == null || owner.isBlank() ? models.providerFor(role) : models.providerFor(role, owner);
    }

    private String promptWithoutParameters(String template) {
        String result = template;
        for (String parameter : List.of(
                "section",
                "objective",
                "coverage",
                "requiredRules",
                "continuity",
                "chapterScope",
                "evidence",
                "visualEvidenceAvailable",
                "visualPages",
                "repair")) {
            result = result.replace("{" + parameter + "}", "");
        }
        return result;
    }

    private int estimateTokens(String value) {
        return value == null || value.isEmpty() ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private SectionDraft composeOnce(SectionRequest request, String repairInstruction) {
        Role role = roleFor(request);
        String owner = request.modelConfigurationOwner();
        Map<String, UUID> evidenceIds = evidenceIds(request);
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(role, owner)).prompt();
        Map<String, Object> providerOptions = providerOptions(role, owner);
        if (!providerOptions.isEmpty()) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
            options.model(models.modelNameFor(role, owner));
            options.temperature(temperature);
            options.extraBody(providerOptions);
            if (usesQwen(role, owner)) {
                options.responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(qwenTeachingSchema())
                        .build());
            }
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(temperature));
        }
        ModelSectionDraft draft;
        try {
            ChatClient.ChatClientRequestSpec requestSpec = prompt
                    .system(prompts.teachingRuntimeSystem())
                    .user(user -> {
                        user.text(prompts.teachingUser())
                                .param("section", request.title())
                                .param("objective", request.objective())
                                .param("coverage", request.coverageTags())
                                .param("requiredRules", request.requiredRuleIntents())
                                .param("continuity", request.priorSections())
                                .param("chapterScope", request.chapterScope())
                                .param("evidence", modelEvidence(request))
                                .param("visualEvidenceAvailable", !request.pageImages().isEmpty())
                                .param("visualPages", request.pageImages().stream()
                                        .map(TeachingLessonModel.PageImageInput::pageNumber)
                                        .toList())
                                .param("repair", repairInstruction);
                        if (role == Role.VISUAL) {
                            request.pageImages().stream().map(images::prepare).forEach(image -> user.media(
                                    MimeTypeUtils.parseMimeType(image.mediaType()),
                                    new ByteArrayResource(image.content())));
                        }
                    });
            if (usesQwen(role, owner)) {
                String responseContent = requestSpec.call().content();
                draft = responseContent == null ? null : TEACHING_OUTPUT_CONVERTER.convert(responseContent);
            } else {
                draft = requestSpec.call().entity(TEACHING_OUTPUT_CONVERTER);
            }
        } catch (JacksonException invalidJson) {
            throw new InvalidOutputException("teaching model returned malformed structured output", invalidJson);
        }
        if (role == Role.VISUAL) {
            log.info(
                    "Visual teaching structure: title={}, kind={}, caption={}, citations={}, steps={}, visualSteps={}, describedFocus={}",
                    draft != null && draft.title() != null && !draft.title().isBlank(),
                    draft == null ? null : draft.visualKind(),
                    draft != null && draft.visualCaption() != null && !draft.visualCaption().isBlank(),
                    draft == null ? 0 : draft.visualCitationIds().size(),
                    draft == null ? 0 : draft.steps().size(),
                    draft == null
                            ? 0
                            : draft.steps().stream()
                                    .filter(java.util.Objects::nonNull)
                                    .filter(step -> step.kind() == TeachingMove.VISUAL)
                                    .count(),
                    draft != null
                            && draft.steps().stream()
                                    .filter(java.util.Objects::nonNull)
                                    .map(ModelStepDraft::visualFocus)
                                    .filter(java.util.Objects::nonNull)
                                    .anyMatch(focus -> !focus.visibleDescription().isBlank()));
        }
        try {
            return toSectionDraft(draft, evidenceIds);
        } catch (IllegalArgumentException invalidContract) {
            throw new InvalidOutputException("teaching model returned an invalid section contract", invalidContract);
        }
    }

    Map<String, Object> providerOptions(Role role, String modelConfigurationOwner) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (models.usesDeepSeekNonThinkingGeneration(role, modelConfigurationOwner)) {
            options.put("thinking", Map.of("type", "disabled"));
        }
        if (usesQwen(role, modelConfigurationOwner)) {
            options.put("enable_thinking", false);
        }
        return Map.copyOf(options);
    }

    Map<String, Object> providerOptions(Role role) {
        return providerOptions(role, null);
    }

    static String qwenTeachingSchema() {
        return QWEN_TEACHING_SCHEMA;
    }

    static String teachingOutputFormat() {
        return TEACHING_OUTPUT_FORMAT;
    }

    private static String buildQwenTeachingSchema() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode schema = (ObjectNode) mapper.readTree(
                    new BeanOutputConverter<>(ModelSectionDraft.class).getJsonSchema());
            ObjectNode properties = (ObjectNode) schema.path("properties");
            ((ObjectNode) properties.path("title")).put("minLength", 1);
            ((ObjectNode) properties.path("visualCaption")).put("minLength", 1);
            ((ObjectNode) properties.path("visualCitationIds")).put("minItems", 1);
            ObjectNode steps = (ObjectNode) properties.path("steps");
            steps.put("minItems", 1);
            ObjectNode stepProperties = (ObjectNode) steps.path("items").path("properties");
            ((ObjectNode) stepProperties.path("citationIds")).put("minItems", 1);
            return mapper.writeValueAsString(schema);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot build the teaching response schema", exception);
        }
    }

    private boolean usesQwen(Role role, String modelConfigurationOwner) {
        return "qwen".equals(resolvedProvider(role, modelConfigurationOwner));
    }

    boolean usesFake(Role role, String modelConfigurationOwner) {
        return modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                ? models.usesFake(role)
                : models.usesFake(role, modelConfigurationOwner);
    }

    Role roleFor(SectionRequest request) {
        // A bounded visual pass has already converted rendered pages into reusable page facts and anchors. Real
        // provider replays showed that making the visual provider inspect pages, write prose, and satisfy the complete
        // section schema in one call was slower and materially less reliable than composing from that compact ledger.
        return Role.TEACHING;
    }

    private List<ModelEvidence> modelEvidence(SectionRequest request) {
        return IntStream.range(0, request.evidence().size())
                .mapToObj(index -> {
                    var evidence = request.evidence().get(index);
                    return new ModelEvidence(
                            "E" + (index + 1),
                            evidence.sectionType(),
                            evidence.heading(),
                            evidence.excerpt(),
                            evidence.pageFrom(),
                            evidence.pageTo());
                })
                .toList();
    }

    private Map<String, UUID> evidenceIds(SectionRequest request) {
        Map<String, UUID> references = new LinkedHashMap<>();
        IntStream.range(0, request.evidence().size())
                .forEach(index -> references.put("E" + (index + 1), request.evidence().get(index).chunkId()));
        return Map.copyOf(references);
    }

    private ModelSectionDraft toModelDraft(SectionRequest request, SectionDraft draft) {
        Map<UUID, String> references = new LinkedHashMap<>();
        evidenceIds(request).forEach((reference, id) -> references.put(id, reference));
        return new ModelSectionDraft(
                draft.title(),
                draft.visualKind(),
                draft.visualCaption(),
                draft.visualCitationIds().stream().map(references::get).toList(),
                draft.steps().stream()
                        .map(step -> new ModelStepDraft(
                                step.heading(), step.kind(), step.text(),
                                step.citationIds().stream().map(references::get).toList(),
                                step.visualFocus()))
                        .toList());
    }

    private List<String> modelFeedback(SectionRequest request, List<String> feedback) {
        Map<UUID, String> references = new LinkedHashMap<>();
        evidenceIds(request).forEach((reference, id) -> references.put(id, reference));
        return feedback.stream()
                .map(message -> {
                    String translated = message;
                    for (var reference : references.entrySet()) {
                        translated = translated.replace(reference.getKey().toString(), reference.getValue());
                        translated = translated.replace(
                                reference.getKey().toString().substring(0, 8), reference.getValue());
                    }
                    return translated;
                })
                .toList();
    }

    private SectionDraft toSectionDraft(ModelSectionDraft draft, Map<String, UUID> evidenceIds) {
        if (draft == null) {
            throw new IllegalArgumentException("teaching model returned no draft");
        }
        if (draft.steps().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("teaching model returned a null step");
        }
        return new SectionDraft(
                draft.title(),
                draft.visualKind(),
                draft.visualCaption(),
                resolveReferences(draft.visualCitationIds(), evidenceIds),
                draft.steps().stream()
                        .map(step -> new StepDraft(
                                step.heading(), step.kind(), step.text(),
                                resolveReferences(step.citationIds(), evidenceIds),
                                step.kind() == TeachingMove.VISUAL ? step.visualFocus() : null))
                        .toList());
    }

    private List<UUID> resolveReferences(List<String> references, Map<String, UUID> evidenceIds) {
        if (references == null) return List.of();
        return references.stream()
                .map(reference -> reference == null ? "" : reference.strip().toUpperCase())
                .map(reference -> {
                    UUID id = evidenceIds.get(reference);
                    if (id == null) {
                        throw new IllegalArgumentException("teaching model cited an unknown evidence reference");
                    }
                    return id;
                })
                .distinct()
                .toList();
    }

    private record ModelEvidence(
            String evidenceRef,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo) {}

    private record ModelSectionDraft(
            String title,
            VisualKind visualKind,
            String visualCaption,
            List<String> visualCitationIds,
            List<ModelStepDraft> steps) {
        private ModelSectionDraft {
            visualCitationIds = visualCitationIds == null ? List.of() : List.copyOf(visualCitationIds);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    private record ModelStepDraft(
            String heading,
            TeachingMove kind,
            String text,
            List<String> citationIds,
            VisualFocusDraft visualFocus) {
        private ModelStepDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }
}
