package com.rulepilot.assistant.adapter.out.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.PlayerFacingField;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
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
    private static final String MODEL_DRAFT_SCHEMA =
            new BeanOutputConverter<>(ModelDraft.class).getJsonSchema();
    private static final Set<String> ANSWER_ARRAY_FIELDS = Set.of(
            "citationIds",
            "exceptions",
            "calculations",
            "walkthroughSteps",
            "decisionBranches",
            "exceptionClauses",
            "termDefinitions",
            "workedExamples",
            "priorityResolutions",
            "timingResolutions",
            "tieResolutions",
            "scopeResolutions",
            "conceptComparisons",
            "ruleOptions");
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
    private static final String PLAYER_FACING_REPAIR_SYSTEM = readPrompt(
            "prompts/rule-answer-player-facing-repair-v1-lean-system.txt");
    private static final String PLAYER_FACING_REPAIR_USER = readPrompt(
            "prompts/rule-answer-player-facing-repair-v1-user.txt");
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
        return providerFor(ownerUsername);
    }

    @Override
    public ModelDraft compose(ModelRequest request) {
        return compose(request, null);
    }

    @Override
    public ModelDraft compose(ModelRequest request, String ownerUsername) {
        return compose(request, ownerUsername, CaptureHandle.noop(), null, null);
    }

    @Override
    public ModelDraft compose(
            ModelRequest request,
            String ownerUsername,
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId) {
        requireConfigured(ownerUsername);
        RuntimeException firstFailure;
        try {
            return composeOnce(request, "", ownerUsername, capture, resource, parentOperationId, 1);
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer model timed out", exception);
            }
            firstFailure = exception;
        }
        try {
            return composeOnce(
                    request,
                    prompts.structuredOutputRepair(),
                    ownerUsername,
                    capture,
                    resource,
                    parentOperationId,
                    2);
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer model timed out", exception);
            }
            exception.addSuppressed(firstFailure);
            throw exception;
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
        return revise(
                request,
                previousDraft,
                feedback,
                ownerUsername,
                CaptureHandle.noop(),
                null,
                null);
    }

    @Override
    public ModelDraft revise(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            String ownerUsername,
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId) {
        requireConfigured(ownerUsername);
        try {
            return repairOnce(
                    request,
                    previousDraft,
                    feedback,
                    ownerUsername,
                    capture,
                    resource,
                    parentOperationId);
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer model timed out", exception);
            }
            throw exception;
        }
    }

    @Override
    public ModelDraft revisePlayerFacing(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            Set<PlayerFacingField> editableFields,
            String ownerUsername) {
        return revisePlayerFacing(
                request,
                previousDraft,
                feedback,
                editableFields,
                ownerUsername,
                CaptureHandle.noop(),
                null,
                null);
    }

    @Override
    public ModelDraft revisePlayerFacing(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            Set<PlayerFacingField> editableFields,
            String ownerUsername,
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId) {
        requireConfigured(ownerUsername);
        try {
            PlayerFacingRepairDraft repaired = repairPlayerFacingOnce(
                    request,
                    previousDraft,
                    feedback,
                    editableFields,
                    ownerUsername,
                    capture,
                    resource,
                    parentOperationId);
            if (repaired == null) {
                throw new IllegalStateException("player-facing repair returned no structured fields");
            }
            return repaired.mergeWith(previousDraft, editableFields);
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer model timed out", exception);
            }
            throw exception;
        }
    }

    @Override
    public boolean supportsQuestionInterpretation() {
        return supportsQuestionInterpretation(null);
    }

    @Override
    public boolean supportsQuestionInterpretation(String ownerUsername) {
        return !usesFake(ownerUsername);
    }

    @Override
    public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
        return interpretQuestion(request, null);
    }

    @Override
    public Optional<QuestionInterpretationDraft> interpretQuestion(
            QuestionInterpretationRequest request, String ownerUsername) {
        return interpretQuestion(request, ownerUsername, CaptureHandle.noop(), null, null);
    }

    @Override
    public Optional<QuestionInterpretationDraft> interpretQuestion(
            QuestionInterpretationRequest request,
            String ownerUsername,
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId) {
        requireConfigured(ownerUsername);
        try {
            String content = interpretQuestionOnce(
                    request, "", ownerUsername, capture, resource, parentOperationId, 1);
            Optional<QuestionInterpretationDraft> interpretation = parseQuestionInterpretation(content);
            if (interpretation.isPresent()) return interpretation;
            captureFailure(capture, resource, parentOperationId, "QUESTION_INTERPRETATION_RESPONSE_REJECTED");

            LOGGER.warn(
                    "Answer question interpretation rejected; requesting one bounded contract repair: provider={}, status={}",
                    providerId(ownerUsername),
                    interpretationOutputStatus(content));
            String repairedContent = interpretQuestionOnce(
                    request,
                    QUESTION_INTERPRETATION_REPAIR,
                    ownerUsername,
                    capture,
                    resource,
                    parentOperationId,
                    2);
            Optional<QuestionInterpretationDraft> repaired = parseQuestionInterpretation(repairedContent);
            if (repaired.isEmpty()) {
                captureFailure(capture, resource, parentOperationId, "QUESTION_INTERPRETATION_REPAIR_REJECTED");
                LOGGER.warn(
                        "Answer question interpretation repair rejected: provider={}, status={}",
                        providerId(ownerUsername),
                        interpretationOutputStatus(repairedContent));
            }
            return repaired;
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer question interpretation timed out", exception);
            }
            LOGGER.warn(
                    "Answer question interpretation failed: provider={}, failureType={}",
                    providerId(ownerUsername),
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String interpretQuestionOnce(
            QuestionInterpretationRequest request,
            String repairInstruction,
            String ownerUsername,
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId,
            int attempt) {
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
        ChatClient.ChatClientRequestSpec call = prompt
                .system(system)
                .user(user -> user.text(QUESTION_INTERPRETATION_USER)
                        .param("question", request.question())
                        .param("previousQuestion", optional(request.previousQuestion()))
                        .param("priorGroundedQuestion", optional(request.priorGroundedQuestion()))
                        .param("priorGroundedVerdict", optional(request.priorGroundedVerdict()))
                        .param("deterministicType", request.deterministicType().name())
                        .param("deterministicMissingContext", request.deterministicMissingContext())
                        .param("explicitLearningIntent", request.explicitLearningIntentForPrompt())
                        .param("outputLanguage", request.outputLanguage().promptName()));
        return tracedContent(
                capture,
                resource,
                parentOperationId,
                ownerUsername,
                attempt,
                "rule-answer-question-interpretation-v9",
                "question-interpretation-v1",
                QUESTION_INTERPRETATION_SCHEMA,
                estimateTokens(request.toString()),
                384,
                () -> call.call().content());
    }

    private ModelDraft composeOnce(
            ModelRequest request,
            String repairInstruction,
            String ownerUsername,
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId,
            int attempt) {
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
            options.responseFormat(responseFormat(MODEL_DRAFT_SCHEMA, ownerUsername));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(answerTemperature));
        }
        ChatClient.ChatClientRequestSpec call = prompt
                .system(prompts.answerSystem(request.answerAid().name()))
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
                        .param("repair", repairInstruction));
        String content = tracedContent(
                capture,
                resource,
                parentOperationId,
                ownerUsername,
                attempt,
                repairInstruction == null || repairInstruction.isBlank()
                        ? "rule-answer-v60"
                        : "structured-output-repair-v1",
                "rule-answer-model-draft-v1",
                MODEL_DRAFT_SCHEMA,
                estimateTokens(request.toString()),
                8192,
                () -> call.call().content());
        try {
            return parseModelDraft(content);
        } catch (RuntimeException rejected) {
            captureFailure(capture, resource, parentOperationId, "ANSWER_MODEL_RESPONSE_REJECTED");
            throw rejected;
        }
    }

    private ModelDraft repairOnce(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            String ownerUsername,
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId) {
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
            options.responseFormat(responseFormat(MODEL_DRAFT_SCHEMA, ownerUsername));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(interpretationTemperature));
        }
        ChatClient.ChatClientRequestSpec call = prompt
                .system(ANSWER_REPAIR_SYSTEM)
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
                        .param("previousDraft", previousDraft)
                        .param("feedback", feedback));
        String content = tracedContent(
                capture,
                resource,
                parentOperationId,
                ownerUsername,
                1,
                "rule-answer-repair-v1",
                "rule-answer-model-draft-v1",
                MODEL_DRAFT_SCHEMA,
                estimateTokens(request.toString()) + estimateTokens(feedback.toString()),
                8192,
                () -> call.call().content());
        try {
            return parseModelDraft(content);
        } catch (RuntimeException rejected) {
            captureFailure(capture, resource, parentOperationId, "ANSWER_MODEL_REVISION_REJECTED");
            throw rejected;
        }
    }

    /** Exact admission for the combined natural-answer and machine-decision JSON envelope. */
    static ModelDraft parseModelDraft(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("answer model returned no structured output");
        }
        try {
            validateStructuredArrays(content, ANSWER_ARRAY_FIELDS, "answer model output");
            return JSON.readValue(content, ModelDraft.class);
        } catch (IOException invalidOutput) {
            throw new IllegalStateException("answer model returned an invalid structured output contract", invalidOutput);
        }
    }

    private PlayerFacingRepairDraft repairPlayerFacingOnce(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            Set<PlayerFacingField> editableFields,
            String ownerUsername,
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId) {
        if (editableFields == null || editableFields.isEmpty()) {
            throw new IllegalArgumentException("player-facing repair requires at least one editable field");
        }
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
            options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(interpretationTemperature));
        }
        ChatClient.ChatClientRequestSpec call = prompt
                .system(PLAYER_FACING_REPAIR_SYSTEM)
                .user(user -> user.text(PLAYER_FACING_REPAIR_USER)
                        .param("question", request.question())
                        .param("subquestions", request.subquestions())
                        .param("outputLanguage", request.context().outputLanguageForPrompt())
                        .param("evidence", request.evidence())
                        .param("editableFields", editableFields)
                        .param("rejectedFields", rejectedFieldsJson(previousDraft, editableFields))
                        .param("feedback", feedback));
        String content = tracedContent(
                capture,
                resource,
                parentOperationId,
                ownerUsername,
                1,
                "rule-answer-player-facing-repair-v1",
                "player-facing-repair-v1",
                "",
                estimateTokens(request.toString()) + estimateTokens(feedback.toString()),
                8192,
                () -> call.call().content());
        try {
            return parsePlayerFacingRepair(content, editableFields);
        } catch (RuntimeException rejected) {
            captureFailure(capture, resource, parentOperationId, "ANSWER_PLAYER_FACING_REPAIR_REJECTED");
            throw rejected;
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
        return MODEL_DRAFT_SCHEMA;
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
        if (usesFake(ownerUsername)) {
            throw new IllegalStateException("a real answer model is required for rule Q&A");
        }
    }

    private boolean usesDeepSeekNonThinkingGeneration(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesDeepSeekNonThinkingGeneration(Role.ANSWER)
                : models.usesDeepSeekNonThinkingGeneration(Role.ANSWER, ownerUsername);
    }

    private Map<String, Object> rejectedFields(ModelDraft previousDraft, Set<PlayerFacingField> editableFields) {
        Map<String, Object> rejected = new LinkedHashMap<>();
        if (editableFields.contains(PlayerFacingField.SHORT_VERDICT)) {
            rejected.put("shortVerdict", previousDraft.shortVerdict());
        }
        if (editableFields.contains(PlayerFacingField.EXPLANATION)) {
            rejected.put("explanation", previousDraft.explanation());
        }
        if (editableFields.contains(PlayerFacingField.EXCEPTIONS)) {
            rejected.put("exceptions", previousDraft.exceptions());
        }
        if (editableFields.contains(PlayerFacingField.CITATION_IDS)) {
            rejected.put("citationIds", previousDraft.citationIds());
        }
        return Map.copyOf(rejected);
    }

    private String rejectedFieldsJson(ModelDraft previousDraft, Set<PlayerFacingField> editableFields) {
        try {
            return JSON.writeValueAsString(rejectedFields(previousDraft, editableFields));
        } catch (IOException exception) {
            throw new IllegalStateException("player-facing repair fields could not be encoded", exception);
        }
    }

    private PlayerFacingRepairDraft parsePlayerFacingRepair(
            String content, Set<PlayerFacingField> editableFields) {
        try {
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("player-facing repair is not a JSON object");
            }
            Set<String> expected = editableFieldNames(editableFields);
            Set<String> actual = new LinkedHashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(expected)) {
                throw new IllegalStateException("player-facing repair returned fields outside its edit scope");
            }
            return new PlayerFacingRepairDraft(
                    expected.contains("shortVerdict") ? requiredText(root, "shortVerdict") : null,
                    expected.contains("explanation") ? requiredText(root, "explanation") : null,
                    expected.contains("exceptions") ? stringList(root, "exceptions") : null,
                    expected.contains("citationIds") ? uuidList(root, "citationIds") : null);
        } catch (IOException exception) {
            throw new IllegalStateException("player-facing repair is not valid JSON", exception);
        }
    }

    private Set<String> editableFieldNames(Set<PlayerFacingField> editableFields) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (editableFields.contains(PlayerFacingField.SHORT_VERDICT)) names.add("shortVerdict");
        if (editableFields.contains(PlayerFacingField.EXPLANATION)) names.add("explanation");
        if (editableFields.contains(PlayerFacingField.EXCEPTIONS)) names.add("exceptions");
        if (editableFields.contains(PlayerFacingField.CITATION_IDS)) names.add("citationIds");
        return Set.copyOf(names);
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("player-facing repair omitted " + field);
        }
        return value.textValue();
    }

    private List<String> stringList(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("player-facing repair omitted " + field);
        }
        List<String> values = new java.util.ArrayList<>();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.textValue().isBlank() || !distinct.add(item.textValue().strip())) {
                throw new IllegalStateException("player-facing repair returned an invalid or duplicated exception");
            }
            values.add(item.textValue().strip());
        });
        return List.copyOf(values);
    }

    private List<UUID> uuidList(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IllegalStateException("player-facing repair omitted " + field);
        }
        List<UUID> values = new java.util.ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual()) {
                throw new IllegalStateException("player-facing repair returned a non-text citation ID");
            }
            try {
                values.add(UUID.fromString(item.textValue()));
            } catch (IllegalArgumentException invalidId) {
                throw new IllegalStateException("player-facing repair returned an invalid citation ID", invalidId);
            }
        });
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalStateException("player-facing repair returned duplicated citation IDs");
        }
        return List.copyOf(values);
    }

    private record PlayerFacingRepairDraft(
            String shortVerdict,
            String explanation,
            List<String> exceptions,
            List<UUID> citationIds) {

        ModelDraft mergeWith(ModelDraft previous, Set<PlayerFacingField> editableFields) {
            requireReturnedFields(editableFields);
            return new ModelDraft(
                    previous.answerable(),
                    previous.insufficiencyReason(),
                    editableFields.contains(PlayerFacingField.SHORT_VERDICT) ? shortVerdict : previous.shortVerdict(),
                    editableFields.contains(PlayerFacingField.EXPLANATION) ? explanation : previous.explanation(),
                    editableFields.contains(PlayerFacingField.CITATION_IDS)
                            ? citationIds
                            : previous.citationIds(),
                    editableFields.contains(PlayerFacingField.EXCEPTIONS) ? exceptions : previous.exceptions(),
                    previous.confidence(),
                    previous.answerBasis(),
                    previous.calculations(),
                    previous.walkthroughSteps(),
                    previous.decisionBranches(),
                    previous.exceptionClauses(),
                    previous.termDefinitions(),
                    previous.workedExamples(),
                    previous.priorityResolutions(),
                    previous.timingResolutions(),
                    previous.tieResolutions(),
                    previous.scopeResolutions(),
                    previous.conceptComparisons(),
                    previous.ruleOptions());
        }

        private void requireReturnedFields(Set<PlayerFacingField> editableFields) {
            if (editableFields.contains(PlayerFacingField.SHORT_VERDICT)
                    && (shortVerdict == null || shortVerdict.isBlank())) {
                throw new IllegalStateException("player-facing repair omitted shortVerdict");
            }
            if (editableFields.contains(PlayerFacingField.EXPLANATION)
                    && (explanation == null || explanation.isBlank())) {
                throw new IllegalStateException("player-facing repair omitted explanation");
            }
            if (editableFields.contains(PlayerFacingField.EXCEPTIONS) && exceptions == null) {
                throw new IllegalStateException("player-facing repair omitted exceptions");
            }
            if (editableFields.contains(PlayerFacingField.CITATION_IDS)
                    && (citationIds == null || citationIds.isEmpty())) {
                throw new IllegalStateException("player-facing repair omitted citationIds");
            }
        }
    }

    private String tracedContent(
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId,
            String ownerUsername,
            int attempt,
            String templateVersion,
            String outputSchemaVersion,
            String outputSchema,
            int inputTokenEstimate,
            int maximumOutputTokens,
            Supplier<String> invocation) {
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        if (!trace.enabled()) return invocation.get();
        UUID operationId = UUID.randomUUID();
        TraceEventContext started = TraceEventContext.create(
                java.time.Instant.now(), JourneyStage.ANSWER, operationId, parentOperationId, resource);
        capture(trace, () -> trace.modelCallStarted(new ModelCallStarted(
                        started,
                        providerFor(ownerUsername),
                        modelNameFor(ownerUsername),
                        attempt,
                        templateVersion,
                        outputSchemaVersion,
                        outputSchema == null || outputSchema.isBlank() ? "" : sha256(outputSchema),
                        inputTokenEstimate,
                        maximumOutputTokens)));
        String content;
        try {
            content = invocation.get();
        } catch (RuntimeException failure) {
            capture(trace, () -> trace.bindingOrFailure(new BindingOrFailure(
                            nextEvent(started),
                            LifecycleSignal.FAILURE,
                            isTimeout(failure) ? "MODEL_CALL_TIMEOUT" : "MODEL_CALL_FAILED",
                            resource,
                            null)));
            throw failure;
        }
        String rawContent = content;
        capture(trace, () -> trace.modelTurn(new com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn(
                        nextEvent(started),
                        providerFor(ownerUsername),
                        modelNameFor(ownerUsername),
                        attempt,
                        rawContent == null ? "" : rawContent,
                        List.of(),
                        rawContent == null || rawContent.isBlank() ? "NO_CONTENT" : "STOP",
                        0,
                        0,
                        rawContent == null || rawContent.isBlank())));
        return content;
    }

    private void capture(CaptureHandle trace, Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException ignored) {
            // Private diagnostics never alter the provider or publication result.
        }
    }

    private void captureFailure(
            CaptureHandle capture,
            ResourceRef resource,
            UUID parentOperationId,
            String code) {
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        if (!trace.enabled()) return;
        capture(trace, () -> trace.bindingOrFailure(new BindingOrFailure(
                        TraceEventContext.create(
                                java.time.Instant.now(),
                                JourneyStage.ANSWER,
                                UUID.randomUUID(),
                                parentOperationId,
                                resource),
                        LifecycleSignal.FAILURE,
                        code,
                        resource,
                        null)));
    }

    private TraceEventContext nextEvent(TraceEventContext context) {
        return TraceEventContext.create(
                java.time.Instant.now(),
                context.stage(),
                context.operationId(),
                context.parentOperationId(),
                context.resource());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int estimateTokens(String value) {
        return value == null || value.isEmpty() ? 0 : Math.max(1, (value.length() + 3) / 4);
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
        JsonNode root = JSON.readTree(content);
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
