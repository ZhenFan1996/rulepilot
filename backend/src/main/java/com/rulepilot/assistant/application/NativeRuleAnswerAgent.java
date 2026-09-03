package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.ObservationRecord;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolAgent.TerminalContract;
import com.rulepilot.assistant.NativeToolAgent.TerminalValidation;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.PriorTurnReference;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.application.RuleAnswerNumericBoundary.NumericClaim;
import com.rulepilot.assistant.application.RuleAnswerNumericBoundary.ObservedEvidence;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** One native observe-decide-act loop owns both evidence acquisition and player prose. */
@Service
@Profile("!test")
public class NativeRuleAnswerAgent {

    static final Set<String> READ_TOOLS = Set.of(
            "search_rule_evidence",
            "search_visual_page_facts",
            "search_rule_relationships",
            "expand_rule_evidence_context",
            "read_rule_pages",
            "read_visual_page_facts",
            "read_rule_page_image",
            "crop_rule_page_image");

    static final String TERMINAL_SCHEMA = """
            {
              "type": "object",
              "required": ["kind", "shortVerdict"],
              "properties": {
                "kind": {"type": "string", "enum": ["CHAT", "RULE_ANSWER", "CLARIFICATION"]},
                "shortVerdict": {"type": "string"},
                "explanation": {"type": ["string", "null"], "description": "Optional additional player-facing explanation. Leave empty when shortVerdict already gives the complete useful answer."},
                "clarification": {"type": ["string", "null"]},
                "citationIds": {
                  "type": "array",
                  "items": {"type": "string", "format": "uuid"},
                  "uniqueItems": true
                },
                "exceptions": {"type": "array", "items": {"type": "string"}},
                "numericClaims": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["value", "evidenceId"],
                    "properties": {
                      "value": {"type": "string"},
                      "evidenceId": {"type": "string", "format": "uuid"}
                    },
                    "additionalProperties": true
                  }
                }
              },
              "additionalProperties": true
            }
            """;

    private final NativeToolAgent agent;
    private final NativeToolScopes scopes;
    private final RuleEvidenceLookup evidenceLookup;
    private final RuleAnswerRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public NativeRuleAnswerAgent(
            NativeToolAgent agent,
            NativeToolScopes scopes,
            RuleEvidenceLookup evidenceLookup,
            RuleAnswerRateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        this.agent = agent;
        this.scopes = scopes;
        this.evidenceLookup = evidenceLookup;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    AgentOutcome answer(
            String question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            UUID runId) {
        if (question == null || question.isBlank() || context == null
                || username == null || username.isBlank() || runId == null) {
            throw new IllegalArgumentException("native rule answer request is invalid");
        }
        rateLimiter.checkUser(username);
        if (!agent.supports(Role.ANSWER, username)) {
            return fallback(context, AnswerStatus.MODEL_UNAVAILABLE, "当前没有可用的答疑模型。", 0);
        }
        var scope = scopes.create(username, context.documentVersionId(), runId);
        if (scope.isEmpty()) {
            return fallback(context, AnswerStatus.INVALID_MODEL_OUTPUT, "当前答疑范围不可用。", 0);
        }
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope.orElseThrow(),
                systemPrompt(context),
                playerRequest(question, context),
                fallbackText(context.outputLanguage()),
                READ_TOOLS,
                Set.of(),
                TerminalContract.json(
                        TERMINAL_SCHEMA,
                        (candidate, observations) -> validateTerminal(candidate, observations, context)));
        RunResult result;
        try (RuleAnswerRateLimiter.Permit ignored = rateLimiter.acquireModel(
                username, gameSessionId, agent.providerId(Role.ANSWER, username))) {
            result = agent.run(request);
        }
        if (result.status() != RunStatus.COMPLETED) {
            return fallback(context, fallbackStatus(result.reason()), fallbackMessage(result.reason(), context),
                    result.toolCalls());
        }
        Candidate candidate = parseAccepted(result.text());
        return new AgentOutcome(publish(context, candidate), result.toolCalls());
    }

    TerminalValidation validateTerminal(
            String rawCandidate, List<ObservationRecord> observations, QuestionContext context) {
        Map<String, ObservedEvidence> allowedEvidence = allowedEvidence(observations, context.allowedEvidencePages());
        Set<String> allowedIds = Set.copyOf(allowedEvidence.keySet());
        if (rawCandidate == null || rawCandidate.isBlank()) {
            return rejected("TERMINAL_EMPTY", "/", "A terminal response must not be empty.", allowedIds);
        }
        final Candidate candidate;
        try {
            candidate = parseJsonCandidate(rawCandidate);
        } catch (CandidateFailure failure) {
            return rejected(failure.code, failure.path, failure.getMessage(), allowedIds);
        }
        if (candidate.kind == Kind.CHAT) {
            if (!candidate.citationIds.isEmpty()) {
                return rejected(
                        "CHAT_CITATION_FORBIDDEN", "/citationIds",
                        "Ordinary conversation must not publish rule citations.", allowedIds);
            }
            if (candidate.clarification != null) {
                return rejected(
                        "CHAT_CLARIFICATION_FORBIDDEN", "/clarification",
                        "Ordinary conversation must not masquerade as a rule clarification.", allowedIds);
            }
            return TerminalValidation.accepted();
        }
        if (candidate.kind == Kind.CLARIFICATION) {
            if (candidate.clarification == null || candidate.clarification.isBlank()) {
                return rejected(
                        "CLARIFICATION_REQUIRED", "/clarification",
                        "A clarification terminal must provide the complete player-facing clarification.", allowedIds);
            }
            if (!candidate.citationIds.isEmpty()) {
                return rejected(
                        "CLARIFICATION_CITATION_FORBIDDEN", "/citationIds",
                        "A clarification must not publish rule citations.", allowedIds);
            }
            return TerminalValidation.accepted();
        }
        if (candidate.citationIds.isEmpty()) {
            return rejected(
                    "CITATION_REQUIRED", "/citationIds",
                    "A rule answer must cite at least one source-bearing evidence identity observed in this Agent run.",
                    allowedIds);
        }
        for (int index = 0; index < candidate.citationIds.size(); index++) {
            String id = candidate.citationIds.get(index);
            if (!allowedEvidence.containsKey(id)) {
                return rejected(
                        "CITATION_NOT_OBSERVED", "/citationIds/" + index,
                        "The citation identity was not returned by a source-bearing search or exact page read in this Agent run.",
                        allowedIds);
            }
        }
        if (new LinkedHashSet<>(candidate.citationIds).size() != candidate.citationIds.size()) {
            return rejected(
                    "CITATION_DUPLICATE", "/citationIds",
                    "Citation identities must be unique while preserving the intended publication order.", allowedIds);
        }
        TerminalValidation identity = validateCanonicalIdentity(candidate, context, allowedEvidence, allowedIds);
        if (identity != null) return identity;
        return RuleAnswerNumericBoundary.validate(
                candidate.numericClaims,
                candidate.citationIds,
                allowedEvidence,
                allowedIds);
    }

    private TerminalValidation validateCanonicalIdentity(
            Candidate candidate,
            QuestionContext context,
            Map<String, ObservedEvidence> observed,
            Set<String> allowedIds) {
        LinkedHashSet<UUID> requested = new LinkedHashSet<>();
        for (int index = 0; index < candidate.citationIds.size(); index++) {
            try {
                requested.add(UUID.fromString(candidate.citationIds.get(index)));
            } catch (IllegalArgumentException invalidId) {
                return rejected(
                        "CITATION_ID_INVALID", "/citationIds/" + index,
                        "Every citation identity must be a UUID.", allowedIds);
            }
        }
        Map<UUID, RuleEvidenceHit> canonical = new LinkedHashMap<>();
        for (RuleEvidenceHit hit : evidenceLookup.findByChunkIds(context.documentVersionId(), requested)) {
            if (!context.documentVersionId().equals(hit.documentVersionId())) {
                return rejected(
                        "CITATION_DOCUMENT_CONFLICT", "/citationIds",
                        "A citation belongs to a different immutable document version.", allowedIds);
            }
            canonical.putIfAbsent(hit.chunkId(), hit);
        }
        if (!canonical.keySet().equals(requested)) {
            return rejected(
                    "CITATION_IDENTITY_MISSING", "/citationIds",
                    "One or more cited evidence identities are no longer canonical for this document version.",
                    allowedIds);
        }
        for (UUID id : requested) {
            RuleEvidenceHit hit = canonical.get(id);
            ObservedEvidence seen = observed.get(id.toString());
            if (seen == null || seen.pageFrom() != hit.pageFrom() || seen.pageTo() != hit.pageTo()) {
                return rejected(
                        "CITATION_SNAPSHOT_CONFLICT", "/citationIds",
                        "The cited page identity changed between observation and publication.", allowedIds);
            }
        }
        return null;
    }

    private StructuredRuleAnswer publish(QuestionContext context, Candidate candidate) {
        if (candidate.kind == Kind.CHAT) {
            return answer(
                    context.documentVersionId(), AnswerStatus.ANSWERED,
                    candidate.shortVerdict, candidate.explanation, List.of(), List.of(),
                    AnswerConfidence.MEDIUM, null, null);
        }
        if (candidate.kind == Kind.CLARIFICATION) {
            return answer(
                    context.documentVersionId(), AnswerStatus.CLARIFICATION_REQUIRED,
                    candidate.shortVerdict, candidate.explanation, List.of(), List.of(),
                    AnswerConfidence.LOW, null, candidate.clarification);
        }
        LinkedHashSet<UUID> ids = candidate.citationIds.stream()
                .map(UUID::fromString)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, RuleEvidenceHit> byId = evidenceLookup.findByChunkIds(context.documentVersionId(), ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        RuleEvidenceHit::chunkId, hit -> hit, (left, right) -> left, LinkedHashMap::new));
        List<RuleCitation> citations = ids.stream().map(id -> citation(byId.get(id))).toList();
        return answer(
                context.documentVersionId(), AnswerStatus.ANSWERED,
                candidate.shortVerdict, candidate.explanation, citations, candidate.exceptions,
                AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE, null);
    }

    private StructuredRuleAnswer answer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis basis,
            String clarification) {
        return new StructuredRuleAnswer(
                documentVersionId,
                status,
                shortVerdict,
                explanation,
                citations,
                exceptions,
                confidence,
                basis,
                false,
                null,
                null,
                clarification,
                List.of());
    }

    private RuleCitation citation(RuleEvidenceHit evidence) {
        if (evidence == null) throw new IllegalStateException("validated answer evidence disappeared");
        return new RuleCitation(
                evidence.chunkId(), evidence.documentVersionId(), evidence.sectionType(), evidence.heading(),
                evidence.playerExcerpt(), evidence.pageFrom(), evidence.pageTo());
    }

    private Candidate parseAccepted(String raw) {
        return parseJsonCandidate(raw);
    }

    private Candidate parseJsonCandidate(String raw) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException exception) {
            throw new CandidateFailure("TERMINAL_JSON_INVALID", "/", "Terminal JSON could not be decoded.");
        }
        if (root == null || !root.isObject()) {
            throw new CandidateFailure("TERMINAL_TYPE_INVALID", "/", "Terminal JSON must be one object.");
        }
        Kind kind;
        String kindName = requiredString(root, "kind", false);
        try {
            kind = Kind.valueOf(kindName);
        } catch (IllegalArgumentException invalidKind) {
            throw new CandidateFailure(
                    "TERMINAL_VALUE_INVALID", "/kind", "kind must be CHAT, RULE_ANSWER, or CLARIFICATION.");
        }
        return new Candidate(
                kind,
                requiredString(root, "shortVerdict", true),
                optionalStringOrEmpty(root, "explanation"),
                optionalString(root, "clarification"),
                stringArray(root, "citationIds"),
                stringArray(root, "exceptions"),
                numericClaims(root));
    }

    private String requiredString(JsonNode root, String field, boolean nonBlank) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || nonBlank && value.textValue().isBlank()) {
            throw new CandidateFailure(
                    "TERMINAL_FIELD_INVALID", "/" + field,
                    field + " must be " + (nonBlank ? "a non-blank" : "a") + " JSON string.");
        }
        return value.textValue();
    }

    private String optionalString(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new CandidateFailure(
                    "TERMINAL_FIELD_INVALID", "/" + field, field + " must be a JSON string or null.");
        }
        return value.textValue();
    }

    private String optionalStringOrEmpty(JsonNode root, String field) {
        String value = optionalString(root, field);
        return value == null ? "" : value;
    }

    private List<String> stringArray(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) {
            throw new CandidateFailure(
                    "TERMINAL_FIELD_INVALID", "/" + field, field + " must be a JSON array.");
        }
        List<String> strings = new ArrayList<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new CandidateFailure(
                        "TERMINAL_FIELD_INVALID", "/" + field + "/" + index,
                        field + " entries must be non-blank JSON strings.");
            }
            strings.add(item.textValue());
        }
        return List.copyOf(strings);
    }

    private List<NumericClaim> numericClaims(JsonNode root) {
        JsonNode values = root.get("numericClaims");
        if (values == null || values.isNull()) return List.of();
        if (!values.isArray()) {
            throw new CandidateFailure(
                    "TERMINAL_FIELD_INVALID", "/numericClaims", "numericClaims must be a JSON array.");
        }
        List<NumericClaim> claims = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            if (!value.isObject()) {
                throw new CandidateFailure(
                        "TERMINAL_FIELD_INVALID", "/numericClaims/" + index,
                        "Each numeric claim must be one JSON object.");
            }
            claims.add(new NumericClaim(
                    nestedString(value, "value", index),
                    nestedString(value, "evidenceId", index)));
        }
        return List.copyOf(claims);
    }

    private String nestedString(JsonNode value, String field, int index) {
        JsonNode child = value.get(field);
        if (child == null || !child.isTextual() || child.textValue().isBlank()) {
            throw new CandidateFailure(
                    "TERMINAL_FIELD_INVALID", "/numericClaims/" + index + "/" + field,
                    field + " must be a non-blank JSON string.");
        }
        return child.textValue();
    }

    private Map<String, ObservedEvidence> allowedEvidence(
            List<ObservationRecord> observations, Set<Integer> allowedPages) {
        Map<String, ObservedEvidence> allowed = new LinkedHashMap<>();
        for (ObservationRecord observation : observations) {
            if (!("search_rule_evidence".equals(observation.toolName())
                            || "read_rule_pages".equals(observation.toolName()))
                    || observation.observation().status() == ObservationStatus.ERROR) {
                continue;
            }
            JsonNode evidence = objectMapper.valueToTree(observation.observation().data()).path("evidence");
            if (!evidence.isArray()) continue;
            for (JsonNode value : evidence) {
                if (!value.path("evidenceId").isTextual()
                        || !value.path("excerpt").isTextual()
                        || !value.path("pageFrom").canConvertToInt()
                        || !value.path("pageTo").canConvertToInt()) {
                    continue;
                }
                int pageFrom = value.path("pageFrom").intValue();
                int pageTo = value.path("pageTo").intValue();
                if (!withinAllowedPages(pageFrom, pageTo, allowedPages)) continue;
                allowed.putIfAbsent(
                        value.path("evidenceId").textValue(),
                        new ObservedEvidence(value.path("excerpt").textValue(), pageFrom, pageTo));
            }
        }
        return Map.copyOf(allowed);
    }

    private boolean withinAllowedPages(int pageFrom, int pageTo, Set<Integer> allowedPages) {
        if (pageFrom < 1 || pageTo < pageFrom) return false;
        if (allowedPages == null) return true;
        return java.util.stream.IntStream.rangeClosed(pageFrom, pageTo).allMatch(allowedPages::contains);
    }

    private String systemPrompt(QuestionContext context) {
        return """
                You are the sole answer Agent for one active immutable board-game rulebook.
                Decide the next action yourself. Ordinary non-rule conversation uses kind CHAT and no citation.
                Answer only the unresolved obligations in the player's request. Do not broaden into related rules that
                are unnecessary to make the verdict correct. Prefer the smallest complete supported ruling; every
                included claim must earn its evidence and numeric-validation cost.
                For a rule claim, search only when needed. A search_rule_evidence result is source-bearing when its
                excerpt directly contains enough subject, condition, exception, and applicability context for the
                answer; cite that observed evidenceId without repeating or reconfirming the read. Use read_rule_pages
                only when the search excerpt needs fuller page context, a crossed chunk boundary, a condition, an
                exception, a list continuation, or an applicability check. Relationship results remain candidates and
                cannot be cited.
                Use search_visual_page_facts only to locate a visible printed label, icon, table, diagram, or board
                location that canonical text search did not locate. Its observations have no mechanical-rule
                authority and are never citation-bearing; use the returned page-bound handle for a dependent visual
                read, then confirm any ruling with canonical text.
                Call mutually independent read-only tools together in one decision; choose a dependent read only after
                observing its prerequisite. Preserve supported portions when one sibling read fails and localize only
                what remains unresolved.
                Every terminal response must be one complete JSON object satisfying the schema below. Cite only
                evidenceId values observed from source-bearing search_rule_evidence or read_rule_pages results in this
                run. Do not print raw evidence IDs or page excerpts in player prose; the application publishes
                citations separately. Declare every numeric
                literal used as a hard rule fact in numericClaims with the exact literal and its evidenceId. Unknown
                additive fields are ignored. Write all player-facing prose yourself in %s. No reviewer or prose
                template follows this turn.
                Terminal JSON schema:
                %s
                """.formatted(context.outputLanguage().promptName(), TERMINAL_SCHEMA);
    }

    private String playerRequest(String question, QuestionContext context) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("previousQuestion", context.previousQuestion());
        request.put("learningIntent", context.learningIntent() == null ? null : context.learningIntent().name());
        request.put("priorGroundedTurn", priorTurn(context.priorTurnReference()));
        request.put("allowedPublicPages", context.allowedEvidencePages() == null
                ? null
                : context.allowedEvidencePages().stream().sorted().toList());
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("answer request context could not be serialized", exception);
        }
    }

    private Object priorTurn(PriorTurnReference prior) {
        if (prior == null) return null;
        return Map.of(
                "question", prior.question(),
                "groundedVerdict", prior.groundedVerdict(),
                "citationLocators", prior.citations().stream().map(citation -> Map.of(
                        "evidenceId", citation.chunkId().toString(),
                        "pageFrom", citation.pageFrom(),
                        "pageTo", citation.pageTo())).toList());
    }

    private AgentOutcome fallback(
            QuestionContext context, AnswerStatus status, String message, int toolCalls) {
        return new AgentOutcome(answer(
                context.documentVersionId(), status, message, message, List.of(), List.of(),
                AnswerConfidence.LOW, null, null), toolCalls);
    }

    private AnswerStatus fallbackStatus(String reason) {
        if (Set.of("MODEL_CAPABILITY_UNAVAILABLE", "MODEL_REQUEST_UNAVAILABLE").contains(reason)) {
            return AnswerStatus.MODEL_UNAVAILABLE;
        }
        if (Set.of(
                        "TIMEOUT",
                        "MODEL_REQUEST_TIMEOUT",
                        "STEP_BUDGET",
                        "TOOL_BUDGET",
                        "MODEL_BUDGET",
                        "TOKEN_BUDGET",
                        "OBSERVATION_BUDGET_EXHAUSTED",
                        "OBSERVATION_BUDGET_EXCEEDED")
                .contains(reason)) {
            return AnswerStatus.MODEL_TIMEOUT;
        }
        return AnswerStatus.INVALID_MODEL_OUTPUT;
    }

    private String fallbackMessage(String reason, QuestionContext context) {
        boolean english = context.outputLanguage() == PlayerLocale.EN;
        if ("MODEL_CAPABILITY_UNAVAILABLE".equals(reason)) {
            return english ? "No answer model is currently available." : "当前没有可用的答疑模型。";
        }
        if ("MODEL_REQUEST_UNAVAILABLE".equals(reason)) {
            return english
                    ? "The answer model or provider was temporarily unavailable for this request."
                    : "本次请求的答疑模型或模型提供方暂时不可用。";
        }
        return switch (reason) {
            case "TIMEOUT", "MODEL_REQUEST_TIMEOUT" -> english
                    ? "This answer did not finish before the request deadline."
                    : "这次答疑未能在请求时限内完成。";
            case "STEP_BUDGET", "TOOL_BUDGET", "MODEL_BUDGET", "TOKEN_BUDGET",
                    "OBSERVATION_BUDGET_EXHAUSTED", "OBSERVATION_BUDGET_EXCEEDED" -> english
                        ? "This Agent run used its available resource budget and stopped without discarding prior observations."
                        : "本次 Agent 运行已用完可用资源预算，并在保留既有 observation 后停止。";
            case "COMPLETION_NO_PROGRESS" -> english
                    ? "The Agent repeated the same complete response after a typed JSON or evidence-identity rejection, so correction stopped."
                    : "Agent 在收到 JSON 或证据身份校验结果后仍重复同一份完整回复，因此已停止修正。";
            case "TERMINAL_REPAIR_EXHAUSTED" -> english
                    ? "The replacement answer still failed the typed publication boundary, so correction stopped after one targeted repair."
                    : "替换答案仍未通过类型化发布边界，因此一次定向修正后已停止。";
            case "ACTION_NO_PROGRESS" -> english
                    ? "The Agent repeated the same rejected tool action, so the read loop stopped without replaying it again."
                    : "Agent 重复了同一项已拒绝的工具动作，因此只读循环已停止且不会再次重放。";
            case "OBSERVATION_NO_PROGRESS" -> english
                    ? "The same read returned the same observation again, so the Agent stopped that no-progress path."
                    : "同一只读动作再次返回相同 observation，Agent 已停止这条无进展路径。";
            case "TOOL_ALLOWLIST_UNAVAILABLE" -> english
                    ? "The current read-tool identity set was incomplete, so the Agent stopped at the capability boundary."
                    : "当前只读工具身份集合不完整，Agent 已在能力边界停止。";
            case "CANCELLED" -> english
                    ? "This answer run was cancelled and no further model or tool action was started."
                    : "本次答疑已取消，未再启动后续模型或工具动作。";
            default -> english
                    ? "The answer Agent stopped at an execution or publication boundary; the question itself was not rejected."
                    : "答疑 Agent 已在执行或发布边界停止；问题本身并未被拒绝。";
        };
    }

    private String fallbackText(PlayerLocale locale) {
        return locale == PlayerLocale.EN
                ? "The answer Agent could not produce a publishable response."
                : "答疑 Agent 未能生成可发布的回复。";
    }

    private TerminalValidation rejected(
            String code, String path, String reason, Set<String> allowedEvidenceIds) {
        return TerminalValidation.rejected(code, path, reason, allowedEvidenceIds);
    }

    enum Kind {
        CHAT,
        RULE_ANSWER,
        CLARIFICATION
    }

    record AgentOutcome(StructuredRuleAnswer answer, int toolCalls) {}

    private record Candidate(
            Kind kind,
            String shortVerdict,
            String explanation,
            String clarification,
            List<String> citationIds,
            List<String> exceptions,
            List<NumericClaim> numericClaims) {}

    private static final class CandidateFailure extends RuntimeException {
        private final String code;
        private final String path;

        private CandidateFailure(String code, String path, String reason) {
            super(reason);
            this.code = code;
            this.path = path;
        }
    }
}
