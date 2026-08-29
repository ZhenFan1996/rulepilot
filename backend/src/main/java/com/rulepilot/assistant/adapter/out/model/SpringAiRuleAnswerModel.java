package com.rulepilot.assistant.adapter.out.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModelInvalidOutputException;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.RuleAnswerModelUnavailableException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiRuleAnswerModel implements RuleAnswerModel {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiRuleAnswerModel.class);
    private static final String QUESTION_INTERPRETATION_SCHEMA =
            new BeanOutputConverter<>(QuestionInterpretationDraft.class).getJsonSchema();
    private static final String LEGACY_MODEL_DRAFT_SCHEMA =
            new BeanOutputConverter<>(ModelDraft.class).getJsonSchema();
    private static final Map<AnswerAid, String> AID_ARRAY_FIELDS = Map.ofEntries(
            Map.entry(AnswerAid.CALCULATION, "calculations"),
            Map.entry(AnswerAid.WALKTHROUGH, "walkthroughSteps"),
            Map.entry(AnswerAid.DECISION_TABLE, "decisionBranches"),
            Map.entry(AnswerAid.EXCEPTIONS, "exceptionClauses"),
            Map.entry(AnswerAid.DEFINITIONS, "termDefinitions"),
            Map.entry(AnswerAid.EXAMPLE, "workedExamples"),
            Map.entry(AnswerAid.RULE_PRIORITY, "priorityResolutions"),
            Map.entry(AnswerAid.TIMING, "timingResolutions"),
            Map.entry(AnswerAid.TIE, "tieResolutions"),
            Map.entry(AnswerAid.SCOPE, "scopeResolutions"),
            Map.entry(AnswerAid.CONCEPT_COMPARISON, "conceptComparisons"),
            Map.entry(AnswerAid.OPTIONS, "ruleOptions"));
    private static final Map<AnswerAid, String> MODEL_DRAFT_SCHEMAS = modelDraftSchemas();
    private static final Set<String> ANSWER_ARRAY_FIELDS = Set.of("citationIds", "exceptions");
    private static final Set<String> QUESTION_INTERPRETATION_ARRAY_FIELDS = Set.of(
            "terms", "ruleObjectSpans", "pageHints", "missingContext", "subquestions");
    private static final String QUESTION_INTERPRETATION_SYSTEM = readPrompt(
            "prompts/rule-answer-question-interpretation-v9-structured-owner-few-shot-system.txt");
    private static final String QUESTION_INTERPRETATION_USER = readPrompt(
            "prompts/rule-answer-question-interpretation-v3-user.txt");
    private static final String QUESTION_INTERPRETATION_REPAIR = readPrompt(
            "prompts/rule-answer-question-interpretation-repair-v2-intent-ownership-system.txt");
    private static final String ANSWER_REPAIR_SYSTEM = readPrompt(
            "prompts/rule-answer-repair-v1-lean-runtime-system.txt");
    private static final String ANSWER_REPAIR_USER = readPrompt(
            "prompts/rule-answer-repair-v1-user.txt");
    private final RuntimeModelConfiguration models;
    private final VersionedAgentPrompts prompts;
    private final double answerTemperature;
    private final double interpretationTemperature;

    public SpringAiRuleAnswerModel(RuntimeModelConfiguration models, VersionedAgentPrompts prompts) {
        this(models, prompts, 0.15, 0.0);
    }

    @Autowired
    public SpringAiRuleAnswerModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            @Value("${rulepilot.answer.temperature:0.15}") double answerTemperature,
            @Value("${rulepilot.answer.interpretation-temperature:0.0}") double interpretationTemperature) {
        requireValidTemperature("answer", answerTemperature);
        requireValidTemperature("answer interpretation", interpretationTemperature);
        this.models = models;
        this.prompts = prompts;
        this.answerTemperature = answerTemperature;
        this.interpretationTemperature = interpretationTemperature;
    }

    @Override
    public String providerId() {
        return providerId(null);
    }

    @Override
    public String providerId(String ownerUsername) {
        try {
            return providerFor(ownerUsername);
        } catch (RuleAnswerModelUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException configurationFailure) {
            throw new RuleAnswerModelUnavailableException(
                    "answer model provider configuration is unavailable", configurationFailure);
        }
    }

    @Override
    public ModelDraft compose(ModelRequest request) {
        return compose(request, null);
    }

    @Override
    public ModelDraft compose(ModelRequest request, String ownerUsername) {
        requireConfigured(ownerUsername);
        try {
            return composeOnce(request, "", ownerUsername);
        } catch (RuleAnswerModelInvalidOutputException invalidOutput) {
            throw invalidOutput;
        } catch (RuntimeException providerFailure) {
            throw classifyInvocationFailure("answer model", providerFailure);
        }
    }

    @Override
    public ModelDraft replaceInvalidOutput(ModelRequest request, String rejectionDiagnostic) {
        return replaceInvalidOutput(request, rejectionDiagnostic, null);
    }

    @Override
    public ModelDraft replaceInvalidOutput(
            ModelRequest request, String rejectionDiagnostic, String ownerUsername) {
        requireConfigured(ownerUsername);
        try {
            return composeOnce(
                    request,
                    structuredOutputReplacementInstruction(rejectionDiagnostic),
                    ownerUsername);
        } catch (RuleAnswerModelInvalidOutputException invalidOutput) {
            throw invalidOutput;
        } catch (RuntimeException exception) {
            throw classifyInvocationFailure("answer model structured-output replacement", exception);
        }
    }

    @Override
    public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, java.util.List<String> feedback) {
        return revise(request, previousDraft, feedback, null);
    }

    @Override
    public ModelDraft revise(
            ModelRequest request,
            ModelDraft previousDraft,
            java.util.List<String> feedback,
            String ownerUsername) {
        requireConfigured(ownerUsername);
        try {
            return repairOnce(request, previousDraft, feedback, ownerUsername);
        } catch (RuleAnswerModelInvalidOutputException invalidOutput) {
            throw invalidOutput;
        } catch (RuntimeException exception) {
            throw classifyInvocationFailure("answer model revision", exception);
        }
    }

    @Override
    public boolean supportsQuestionInterpretation() {
        return supportsQuestionInterpretation(null);
    }

    @Override
    public boolean supportsQuestionInterpretation(String ownerUsername) {
        try {
            return !usesFake(ownerUsername);
        } catch (RuleAnswerModelUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException configurationFailure) {
            throw new RuleAnswerModelUnavailableException(
                    "answer model configuration is unavailable", configurationFailure);
        }
    }

    @Override
    public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
        return interpretQuestion(request, null);
    }

    @Override
    public Optional<QuestionInterpretationDraft> interpretQuestion(
            QuestionInterpretationRequest request, String ownerUsername) {
        requireConfigured(ownerUsername);
        try {
            String content = interpretQuestionOnce(request, "", ownerUsername);
            Optional<QuestionInterpretationDraft> interpretation = parseQuestionInterpretation(content);
            if (interpretation.isPresent()) return interpretation;

            LOGGER.warn(
                    "Answer question interpretation rejected; requesting one bounded contract repair: provider={}, status={}",
                    providerId(ownerUsername),
                    interpretationOutputStatus(content));
            String repairedContent = interpretQuestionOnce(
                    request, QUESTION_INTERPRETATION_REPAIR, ownerUsername);
            Optional<QuestionInterpretationDraft> repaired = parseQuestionInterpretation(repairedContent);
            if (repaired.isEmpty()) {
                LOGGER.warn(
                        "Answer question interpretation repair rejected: provider={}, status={}",
                        providerId(ownerUsername),
                        interpretationOutputStatus(repairedContent));
            }
            return repaired;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Answer question interpretation failed: provider={}, failureType={}",
                    safeProviderId(ownerUsername),
                    exception.getClass().getSimpleName());
            throw classifyInvocationFailure("answer question interpretation", exception);
        }
    }

    private String interpretQuestionOnce(
            QuestionInterpretationRequest request,
            String repairInstruction,
            String ownerUsername) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(modelFor(ownerUsername)).prompt();
        if (usesDeepSeekNonThinkingGeneration(ownerUsername) || usesQwen(ownerUsername)) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
            options.model(modelNameFor(ownerUsername));
            options.maxTokens(384);
            options.temperature(interpretationTemperature);
            if (usesDeepSeekNonThinkingGeneration(ownerUsername)) {
                options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else {
                options.extraBody(Map.of("enable_thinking", false));
            }
            options.responseFormat(responseFormat(QUESTION_INTERPRETATION_SCHEMA, ownerUsername));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .maxTokens(384)
                    .temperature(interpretationTemperature));
        }
        String system = repairInstruction == null || repairInstruction.isBlank()
                ? QUESTION_INTERPRETATION_SYSTEM
                : QUESTION_INTERPRETATION_SYSTEM + "\n\n" + repairInstruction;
        return prompt
                .system(system)
                .user(user -> user.text(QUESTION_INTERPRETATION_USER)
                        .param("question", request.question())
                        .param("previousQuestion", optional(request.previousQuestion()))
                        .param("priorGroundedQuestion", optional(request.priorGroundedQuestion()))
                        .param("priorGroundedVerdict", optional(request.priorGroundedVerdict()))
                        .param("deterministicType", request.deterministicType().name())
                        .param("deterministicMissingContext", request.deterministicMissingContext())
                        .param("explicitLearningIntent", request.explicitLearningIntentForPrompt())
                        .param("outputLanguage", request.outputLanguage().promptName()))
                .call()
                .content();
    }

    private ModelDraft composeOnce(
            ModelRequest request, String repairInstruction, String ownerUsername) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(modelFor(ownerUsername)).prompt();
        if (usesDeepSeekNonThinkingGeneration(ownerUsername) || usesQwen(ownerUsername)) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
            options.model(modelNameFor(ownerUsername));
            options.temperature(answerTemperature);
            if (usesDeepSeekNonThinkingGeneration(ownerUsername)) {
                options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else {
                options.extraBody(Map.of("enable_thinking", false));
            }
            options.responseFormat(responseFormat(modelDraftSchema(request.answerAid()), ownerUsername));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(answerTemperature));
        }
        String content = prompt
                .system(answerOutputSystem(prompts.answerSystem(request.answerAid().name()), request.answerAid()))
                .user(user -> user.text(prompts.answerUser())
                        .param("question", request.question())
                        .param("questionType", request.questionType().name())
                        .param("evidenceNeeds", request.evidenceNeeds())
                        .param("subquestions", request.subquestions())
                        .param("answerAid", request.answerAid())
                        .param("referenceBinding", request.context().referenceBinding())
                        .param("currentRuleObjects", request.context().currentRuleObjectSpans())
                        .param("pageHints", request.context().pageHints())
                        .param("previousQuestion", request.context().previousQuestion())
                        .param("learningIntent", request.context().learningIntentForPrompt())
                        .param("outputLanguage", request.context().outputLanguageForPrompt())
                        .param("evidence", request.evidence())
                        .param("repair", repairInstruction))
                .call()
                .content();
        return parseModelDraft(content, request.answerAid());
    }

    private String structuredOutputReplacementInstruction(String rejectionDiagnostic) {
        String repair = prompts.structuredOutputRepair();
        String instruction = repair == null ? "" : repair.strip();
        if (rejectionDiagnostic == null || rejectionDiagnostic.isBlank()) return instruction;
        String diagnostic = rejectionDiagnostic.strip();
        if (diagnostic.length() > 600) diagnostic = diagnostic.substring(0, 600);
        return instruction
                + "\n\nThe application rejected the prior complete response: "
                + diagnostic
                + "\nReturn one complete replacement object; do not return a field patch.";
    }

    private ModelDraft repairOnce(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            String ownerUsername) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(modelFor(ownerUsername)).prompt();
        if (usesDeepSeekNonThinkingGeneration(ownerUsername) || usesQwen(ownerUsername)) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
            options.model(modelNameFor(ownerUsername));
            options.temperature(interpretationTemperature);
            if (usesDeepSeekNonThinkingGeneration(ownerUsername)) {
                options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else {
                options.extraBody(Map.of("enable_thinking", false));
            }
            options.responseFormat(responseFormat(modelDraftSchema(request.answerAid()), ownerUsername));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(interpretationTemperature));
        }
        String content = prompt
                .system(answerOutputSystem(ANSWER_REPAIR_SYSTEM, request.answerAid()))
                .user(user -> user.text(ANSWER_REPAIR_USER)
                        .param("question", request.question())
                        .param("questionType", request.questionType().name())
                        .param("subquestions", request.subquestions())
                        .param("answerAid", request.answerAid())
                        .param("referenceBinding", request.context().referenceBinding())
                        .param("currentRuleObjects", request.context().currentRuleObjectSpans())
                        .param("pageHints", request.context().pageHints())
                        .param("outputLanguage", request.context().outputLanguageForPrompt())
                        .param("evidence", request.evidence())
                        .param("previousDraft", providerDraftJson(previousDraft, request.answerAid()))
                        .param("feedback", feedback))
                .call()
                .content();
        return parseModelDraft(content, request.answerAid());
    }

    /** Exact admission for the combined natural-answer and machine-decision JSON envelope. */
    static ModelDraft parseModelDraft(String content) {
        return parseModelDraft(content, declaredAid(content));
    }

    static ModelDraft parseModelDraft(String content, AnswerAid expectedAid) {
        if (content == null || content.isBlank()) {
            throw new RuleAnswerModelInvalidOutputException("answer model returned no structured output");
        }
        if (expectedAid == null) throw new IllegalArgumentException("expected answer aid is required");
        try {
            JsonNode parsed = JSON.readTree(content);
            if (!(parsed instanceof ObjectNode provider)) {
                throw JsonMappingException.from((JsonParser) null, "answer model output must be an object");
            }
            if (expectedAid == AnswerAid.CALCULATION) {
                validateStructuredArrays(provider, ANSWER_ARRAY_FIELDS, "answer model output");
                return toModelDraft(provider, expectedAid);
            }
            ModelDraft core = toCoreModelDraft(provider);
            try {
                validateStructuredArrays(provider, ANSWER_ARRAY_FIELDS, "answer model output");
                return toModelDraft(provider, expectedAid);
            } catch (IOException invalidAid) {
                LOGGER.info(
                        "Dropping invalid optional {} answer aid while preserving its strictly decoded core",
                        expectedAid);
                return core;
            }
        } catch (IOException invalidOutput) {
            throw new RuleAnswerModelInvalidOutputException(
                    "answer model returned an invalid structured output contract", invalidOutput);
        }
    }

    private boolean usesQwen(String ownerUsername) {
        return "qwen".equals(providerFor(ownerUsername));
    }

    private ResponseFormat responseFormat(String schema, String ownerUsername) {
        if (usesQwen(ownerUsername)) {
            return ResponseFormat.builder()
                    .type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(schema)
                    .build();
        }
        return ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();
    }

    static String questionInterpretationSchema() {
        return QUESTION_INTERPRETATION_SCHEMA;
    }

    static String modelDraftSchema() {
        return modelDraftSchema(AnswerAid.NONE);
    }

    static String modelDraftSchema(AnswerAid aid) {
        String schema = MODEL_DRAFT_SCHEMAS.get(aid);
        if (schema == null) throw new IllegalArgumentException("answer aid schema is unavailable");
        return schema;
    }

    private static Map<AnswerAid, String> modelDraftSchemas() {
        EnumMap<AnswerAid, String> schemas = new EnumMap<>(AnswerAid.class);
        for (AnswerAid aid : AnswerAid.values()) schemas.put(aid, providerSchema(aid));
        return Map.copyOf(schemas);
    }

    private static String providerSchema(AnswerAid aid) {
        try {
            ObjectNode schema = (ObjectNode) JSON.readTree(LEGACY_MODEL_DRAFT_SCHEMA);
            ObjectNode properties = (ObjectNode) schema.required("properties");
            ArrayNode required = (ArrayNode) schema.required("required");
            String payloadField = AID_ARRAY_FIELDS.get(aid);
            JsonNode payloadSchema = payloadField == null ? null : properties.get(payloadField).deepCopy();
            for (String field : AID_ARRAY_FIELDS.values()) {
                properties.remove(field);
                for (int index = required.size() - 1; index >= 0; index--) {
                    if (field.equals(required.get(index).asText())) required.remove(index);
                }
            }

            ObjectNode aidSchema = JSON.createObjectNode();
            aidSchema.put("type", "object");
            aidSchema.put("additionalProperties", false);
            ObjectNode aidProperties = aidSchema.putObject("properties");
            aidProperties.putObject("type").put("type", "string").putArray("enum").add(aid.name());
            ArrayNode aidRequired = aidSchema.putArray("required").add("type");
            if (payloadSchema != null) {
                aidProperties.set("payload", payloadSchema);
                aidRequired.add("payload");
            }
            properties.set("aid", aidSchema);
            required.add("aid");
            return JSON.writeValueAsString(schema);
        } catch (IOException invalidSchema) {
            throw new IllegalStateException("answer model schema is unavailable", invalidSchema);
        }
    }

    private static String answerOutputSystem(String configuredPrompt, AnswerAid aid) {
        String prompt = configuredPrompt == null ? "" : configuredPrompt;
        return prompt + "\n\nThe final provider response contract below replaces every older final-envelope example. "
                + "Return exactly one aid object and no legacy top-level aid arrays. The aid discriminator must be "
                + aid.name() + ". Do not change the player-facing shortVerdict or explanation merely to satisfy this "
                + "wire shape.\n" + modelDraftSchema(aid);
    }

    private static AnswerAid declaredAid(String content) {
        if (content == null || content.isBlank()) {
            throw new RuleAnswerModelInvalidOutputException("answer model returned no structured output");
        }
        try {
            JsonNode root = JSON.readTree(content);
            if (!(root instanceof ObjectNode provider)) {
                throw JsonMappingException.from((JsonParser) null, "answer model output must be an object");
            }
            return readAidType(provider);
        } catch (IOException invalidOutput) {
            throw new RuleAnswerModelInvalidOutputException(
                    "answer model returned an invalid structured output contract", invalidOutput);
        }
    }

    private static ModelDraft toModelDraft(ObjectNode provider, AnswerAid expectedAid) throws IOException {
        for (String legacyField : AID_ARRAY_FIELDS.values()) {
            if (provider.has(legacyField)) {
                throw JsonMappingException.from(
                        (JsonParser) null, "answer model output contains a legacy top-level aid array");
            }
        }
        JsonNode aidNode = provider.get("aid");
        if (!(aidNode instanceof ObjectNode aid)) {
            throw JsonMappingException.from((JsonParser) null, "answer model output aid is invalid");
        }
        AnswerAid actualAid = readAidType(provider);
        if (actualAid != expectedAid) {
            throw JsonMappingException.from(
                    (JsonParser) null, "answer model output aid does not match the accepted answer plan");
        }
        String payloadField = AID_ARRAY_FIELDS.get(expectedAid);
        JsonNode payload = aid.get("payload");
        int expectedAidFields = payloadField == null ? 1 : 2;
        if (aid.size() != expectedAidFields
                || payloadField != null && (payload == null || !payload.isArray())) {
            throw JsonMappingException.from((JsonParser) null, "answer model output aid payload is invalid");
        }

        ObjectNode legacy = provider.deepCopy();
        legacy.remove("aid");
        for (String field : AID_ARRAY_FIELDS.values()) legacy.putArray(field);
        if (payloadField != null) legacy.set(payloadField, payload.deepCopy());
        return JSON.treeToValue(legacy, ModelDraft.class);
    }

    private static ModelDraft toCoreModelDraft(ObjectNode provider) throws IOException {
        ObjectNode core = provider.deepCopy();
        core.remove("aid");
        for (String field : AID_ARRAY_FIELDS.values()) {
            core.remove(field);
            core.putArray(field);
        }
        validateStructuredArrays(core, ANSWER_ARRAY_FIELDS, "answer model output core");
        return JSON.treeToValue(core, ModelDraft.class);
    }

    private static AnswerAid readAidType(ObjectNode provider) throws IOException {
        JsonNode aid = provider.get("aid");
        JsonNode type = aid == null ? null : aid.get("type");
        if (aid == null || !aid.isObject() || type == null || !type.isTextual()) {
            throw JsonMappingException.from((JsonParser) null, "answer model output aid is invalid");
        }
        return JSON.treeToValue(type, AnswerAid.class);
    }

    static String providerDraftJson(ModelDraft draft, AnswerAid aid) {
        try {
            ObjectNode provider = JSON.valueToTree(draft);
            String payloadField = AID_ARRAY_FIELDS.get(aid);
            JsonNode payload = payloadField == null ? null : provider.get(payloadField);
            for (String field : AID_ARRAY_FIELDS.values()) provider.remove(field);
            ObjectNode selected = provider.putObject("aid");
            selected.put("type", aid.name());
            if (payload != null) selected.set("payload", payload);
            return JSON.writeValueAsString(provider);
        } catch (IOException serializationFailure) {
            throw new IllegalStateException("previous answer draft could not be serialized", serializationFailure);
        }
    }

    private ChatModel modelFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.modelFor(Role.ANSWER)
                : models.modelFor(Role.ANSWER, ownerUsername);
    }

    private String providerFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.providerFor(Role.ANSWER)
                : models.providerFor(Role.ANSWER, ownerUsername);
    }

    private String modelNameFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.modelNameFor(Role.ANSWER)
                : models.modelNameFor(Role.ANSWER, ownerUsername);
    }

    private boolean usesFake(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesFake(Role.ANSWER)
                : models.usesFake(Role.ANSWER, ownerUsername);
    }

    private void requireConfigured(String ownerUsername) {
        try {
            if (usesFake(ownerUsername)) {
                throw new RuleAnswerModelUnavailableException("a real answer model is required for rule Q&A");
            }
        } catch (RuleAnswerModelUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException configurationFailure) {
            throw new RuleAnswerModelUnavailableException(
                    "answer model configuration is unavailable", configurationFailure);
        }
    }

    private RuntimeException classifyInvocationFailure(String operation, RuntimeException failure) {
        if (failure instanceof RuleAnswerModelTimeoutException
                || failure instanceof RuleAnswerModelUnavailableException
                || failure instanceof RuleAnswerModelInvalidOutputException) {
            return failure;
        }
        if (isTimeout(failure)) {
            return new RuleAnswerModelTimeoutException(operation + " timed out", failure);
        }
        return new RuleAnswerModelUnavailableException(operation + " provider is unavailable", failure);
    }

    private String safeProviderId(String ownerUsername) {
        try {
            return providerFor(ownerUsername);
        } catch (RuntimeException unavailable) {
            return "unavailable";
        }
    }

    private boolean usesDeepSeekNonThinkingGeneration(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesDeepSeekNonThinkingGeneration(Role.ANSWER)
                : models.usesDeepSeekNonThinkingGeneration(Role.ANSWER, ownerUsername);
    }

    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? "not provided" : value;
    }

    private Optional<QuestionInterpretationDraft> parseQuestionInterpretation(String content) {
        if (content == null || content.isBlank() || content.length() > 4_000) return Optional.empty();
        try {
            return Optional.of(parseQuestionInterpretationDraft(content));
        } catch (IOException invalidOutput) {
            return Optional.empty();
        }
    }

    static QuestionInterpretationDraft parseQuestionInterpretationDraft(String content) throws IOException {
        validateStructuredArrays(
                content, QUESTION_INTERPRETATION_ARRAY_FIELDS, "question interpretation output");
        return JSON.readValue(content, QuestionInterpretationDraft.class);
    }

    private static void validateStructuredArrays(
            String content, Set<String> requiredTopLevelArrays, String contract) throws IOException {
        validateStructuredArrays(JSON.readTree(content), requiredTopLevelArrays, contract);
    }

    private static void validateStructuredArrays(
            JsonNode root, Set<String> requiredTopLevelArrays, String contract) throws JsonMappingException {
        if (root == null || !root.isObject()) {
            throw JsonMappingException.from((JsonParser) null, contract + " must be a JSON object");
        }
        for (String field : requiredTopLevelArrays) {
            JsonNode value = root.get(field);
            if (value == null || !value.isArray()) {
                throw JsonMappingException.from(
                        (JsonParser) null, contract + " field " + field + " must be an array");
            }
        }
        rejectDuplicateArrayItems(root, contract);
    }

    private static void rejectDuplicateArrayItems(JsonNode node, String path) throws JsonMappingException {
        if (node.isArray()) {
            LinkedHashSet<JsonNode> unique = new LinkedHashSet<>();
            int index = 0;
            for (JsonNode item : node) {
                if (!unique.add(item)) {
                    throw JsonMappingException.from(
                            (JsonParser) null, path + " contains a duplicate array item at index " + index);
                }
                rejectDuplicateArrayItems(item, path + "[" + index + "]");
                index++;
            }
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                rejectDuplicateArrayItems(field.getValue(), path + "." + field.getKey());
            }
        }
    }

    private String interpretationOutputStatus(String content) {
        if (content == null || content.isBlank()) return "BLANK";
        if (content.length() > 4_000) return "TOO_LONG";
        try {
            parseQuestionInterpretationDraft(content);
            return "VALID";
        } catch (IOException invalidJson) {
            try {
                JSON.readTree(content);
                return "INVALID_CONTRACT_" + invalidJson.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
            } catch (IOException malformedJson) {
                return "INVALID_JSON";
            }
        }
    }

    private static String readPrompt(String path) {
        try {
            String prompt = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).strip();
            if (prompt.isBlank()) throw new IllegalStateException("answer interpretation prompt is blank");
            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException("answer interpretation prompt is unavailable", exception);
        }
    }

    private static void requireValidTemperature(String operation, double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException(operation + " model temperature must be between 0 and 2");
        }
    }

}
