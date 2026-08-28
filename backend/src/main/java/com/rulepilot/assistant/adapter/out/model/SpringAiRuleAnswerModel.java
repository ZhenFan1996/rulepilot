package com.rulepilot.assistant.adapter.out.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        requireConfigured(ownerUsername);
        RuntimeException firstFailure;
        try {
            return composeOnce(request, "", ownerUsername);
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer model timed out", exception);
            }
            firstFailure = exception;
        }
        try {
            return composeOnce(request, prompts.structuredOutputRepair(), ownerUsername);
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
        requireConfigured(ownerUsername);
        try {
            return repairOnce(request, previousDraft, feedback, ownerUsername);
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
            options.responseFormat(responseFormat(MODEL_DRAFT_SCHEMA, ownerUsername));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(answerTemperature));
        }
        String content = prompt
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
                        .param("repair", repairInstruction))
                .call()
                .content();
        return parseModelDraft(content);
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
            options.responseFormat(responseFormat(MODEL_DRAFT_SCHEMA, ownerUsername));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(interpretationTemperature));
        }
        String content = prompt
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
                        .param("feedback", feedback))
                .call()
                .content();
        return parseModelDraft(content);
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
