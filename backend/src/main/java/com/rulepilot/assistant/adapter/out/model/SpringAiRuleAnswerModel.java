package com.rulepilot.assistant.adapter.out.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.PlayerFacingField;
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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiRuleAnswerModel.class);
    private static final String QUESTION_INTERPRETATION_SYSTEM = readPrompt(
            "prompts/rule-answer-question-interpretation-v8-lean-runtime-system.txt");
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
    private final FakeRuleAnswerModel fakeModel;
    private final VersionedAgentPrompts prompts;
    private final double answerTemperature;
    private final double interpretationTemperature;

    public SpringAiRuleAnswerModel(
            RuntimeModelConfiguration models, FakeRuleAnswerModel fakeModel, VersionedAgentPrompts prompts) {
        this(models, fakeModel, prompts, 0.15, 0.0);
    }

    @Autowired
    public SpringAiRuleAnswerModel(
            RuntimeModelConfiguration models,
            FakeRuleAnswerModel fakeModel,
            VersionedAgentPrompts prompts,
            @Value("${rulepilot.answer.temperature:0.15}") double answerTemperature,
            @Value("${rulepilot.answer.interpretation-temperature:0.0}") double interpretationTemperature) {
        requireValidTemperature("answer", answerTemperature);
        requireValidTemperature("answer interpretation", interpretationTemperature);
        this.models = models;
        this.fakeModel = fakeModel;
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
        if (usesFake(ownerUsername)) {
            return fakeModel.compose(request);
        }
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
        if (usesFake(ownerUsername)) {
            return fakeModel.revise(request, previousDraft, feedback);
        }
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
    public ModelDraft revisePlayerFacing(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            Set<PlayerFacingField> editableFields,
            String ownerUsername) {
        if (usesFake(ownerUsername)) {
            return fakeModel.revise(request, previousDraft, feedback);
        }
        try {
            PlayerFacingRepairDraft repaired = repairPlayerFacingOnce(
                    request, previousDraft, feedback, editableFields, ownerUsername);
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
    public List<String> rewriteRetrievalQueries(RetrievalQueryRequest request) {
        return rewriteRetrievalQueries(request, null);
    }

    @Override
    public List<String> rewriteRetrievalQueries(
            RetrievalQueryRequest request, String ownerUsername) {
        if (usesFake(ownerUsername)) {
            return List.of();
        }
        try {
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
            RetrievalQueryDraft draft = prompt
                    .system(prompts.answerRetrievalRewriteSystem())
                    .user(user -> user.text(prompts.answerRetrievalRewriteUser())
                            .param("question", request.question())
                            .param("previousQuestion", request.previousQuestion()))
                    .call()
                    .entity(RetrievalQueryDraft.class);
            if (draft == null || draft.queries() == null) {
                return List.of();
            }
            return draft.queries().stream()
                    .filter(query -> query != null && !query.isBlank())
                    .map(String::strip)
                    .distinct()
                    .limit(2)
                    .collect(Collectors.toUnmodifiableList());
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                throw new RuleAnswerModelTimeoutException("answer retrieval rewrite timed out", exception);
            }
            return List.of();
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
        if (usesFake(ownerUsername)) return Optional.empty();
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
            options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
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
            options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(answerTemperature));
        }
        return prompt
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
                .entity(ModelDraft.class);
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
            options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(interpretationTemperature));
        }
        return prompt
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
                .entity(ModelDraft.class);
    }

    private PlayerFacingRepairDraft repairPlayerFacingOnce(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            Set<PlayerFacingField> editableFields,
            String ownerUsername) {
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
        String content = prompt
                .system(PLAYER_FACING_REPAIR_SYSTEM)
                .user(user -> user.text(PLAYER_FACING_REPAIR_USER)
                        .param("question", request.question())
                        .param("subquestions", request.subquestions())
                        .param("outputLanguage", request.context().outputLanguageForPrompt())
                        .param("evidence", request.evidence())
                        .param("editableFields", editableFields)
                        .param("rejectedFields", rejectedFieldsJson(previousDraft, editableFields))
                        .param("feedback", feedback))
                .call()
                .content();
        return parsePlayerFacingRepair(content, editableFields);
    }

    private boolean usesQwen(String ownerUsername) {
        return "qwen".equals(providerFor(ownerUsername));
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

    private boolean usesDeepSeekNonThinkingGeneration(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesDeepSeekNonThinkingGeneration(Role.ANSWER)
                : models.usesDeepSeekNonThinkingGeneration(Role.ANSWER, ownerUsername);
    }

    private record RetrievalQueryDraft(List<String> queries) {}

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
        value.forEach(item -> {
            if (!item.isTextual()) {
                throw new IllegalStateException("player-facing repair returned a non-text exception");
            }
            values.add(item.textValue());
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
        return List.copyOf(new LinkedHashSet<>(values));
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
                    previous.situationChecks(),
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
            return Optional.of(JSON.readValue(content, QuestionInterpretationDraft.class));
        } catch (IOException invalidOutput) {
            return Optional.empty();
        }
    }

    private String interpretationOutputStatus(String content) {
        if (content == null || content.isBlank()) return "BLANK";
        if (content.length() > 4_000) return "TOO_LONG";
        try {
            JSON.readValue(content, QuestionInterpretationDraft.class);
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
