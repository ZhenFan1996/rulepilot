package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Verifies that a player-facing can/cannot ruling preserves the modal direction of its cited rules. */
final class AnswerPermissionResolver {

    private static final Pattern OTHER_QUESTION_INTENT = Pattern.compile(
            "(?iu)^\\s*(?:how|why|where|when|what)\\b.{0,48}\\b(?:can|may)\\b|"
                    + "^\\s*(?:can|may) you (?:explain|show|tell|find|quote|summarize)\\b|"
                    + "^\\s*怎样才能|^\\s*如何才能|需要.{0,24}吗|需不需要");
    private static final Pattern PERMISSION_QUESTION = Pattern.compile(
            "(?iu)^\\s*(?:can|may)\\b|\\b(?:can|may) (?:i|we|you|a|an|the|players?)\\b|"
                    + "\\b(?:is|are|am) [^?]{0,100}\\b(?:allowed|permitted)\\b|"
                    + "能不能|可不可以|是否可以|可以.{0,12}吗|能.{0,12}吗|允许.{0,12}吗|能否|可否");
    private static final Pattern PROHIBITION = Pattern.compile(
            "(?iu)\\b(?:may not|must not|cannot|can't|can never|never may|is not allowed|are not allowed)\\b|"
                    + "不得|禁止|不能|不可以|不可");
    private static final Pattern PERMISSION = Pattern.compile(
            "(?iu)\\b(?:may|can|is allowed|are allowed|permitted)\\b|可以|允许|可|能");
    private static final Pattern CONDITIONAL_BOUNDARY = Pattern.compile(
            "(?iu)\\b(?:unless|except|if|when|after|before|only if)\\b|除非|例外|如果|当|之后|之前|前提");

    List<UUID> resolve(ModelRequest request, ModelDraft draft) {
        if (request == null || draft == null) {
            throw new IllegalArgumentException("permission-check input is invalid");
        }
        if (!asksForPermission(request.question()) || !draft.answerable()) return List.of();
        List<UUID> citationIds = draft.citationIds();
        if (citationIds.isEmpty() || citationIds.stream().anyMatch(java.util.Objects::isNull)
                || citationIds.stream().distinct().count() != citationIds.size()) {
            throw new IllegalArgumentException("permission ruling requires direct, unique citations");
        }
        Set<UUID> cited = Set.copyOf(citationIds);
        List<EvidenceInput> citedEvidence = request.evidence().stream()
                .filter(item -> cited.contains(item.chunkId()))
                .toList();
        if (citedEvidence.size() != cited.size()) {
            throw new IllegalArgumentException("permission ruling cites evidence outside the answer scope");
        }
        String evidenceText = citedEvidence.stream()
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(" "));
        boolean evidenceDenies = PROHIBITION.matcher(evidenceText).find();
        boolean evidenceAllows = containsAffirmativePermission(evidenceText);
        if (!evidenceDenies && !evidenceAllows) return List.copyOf(citationIds);

        String verdict = draft.shortVerdict() == null ? "" : draft.shortVerdict().strip();
        boolean verdictDenies = PROHIBITION.matcher(verdict).find() || startsWithNo(verdict);
        boolean verdictAllows = containsAffirmativePermission(verdict) || startsWithYes(verdict);
        if (!verdictDenies && !verdictAllows) {
            throw new IllegalArgumentException("permission ruling must answer can or cannot in the short verdict");
        }
        if (!CONDITIONAL_BOUNDARY.matcher(evidenceText).find()) {
            if (evidenceDenies && !evidenceAllows && (!verdictDenies || verdictAllows)) {
                throw new IllegalArgumentException("permission ruling reversed a cited prohibition");
            }
            if (evidenceAllows && !evidenceDenies && (!verdictAllows || verdictDenies)) {
                throw new IllegalArgumentException("permission ruling reversed a cited permission");
            }
        }
        validateNoNewTemporalScope(request.question() + " " + evidenceText, verdict + " " + draft.explanation());
        validateNoNewExactTemporalBoundary(
                request.question() + " " + evidenceText, verdict + " " + draft.explanation());
        validateNoNewMandatoryModal(
                request.question() + " " + evidenceText, verdict + " " + draft.explanation());
        validateNoNewRepresentationImpossibility(
                request.question() + " " + evidenceText, verdict + " " + draft.explanation());
        return List.copyOf(citationIds);
    }

    static boolean asksForPermission(String question) {
        return question != null
                && !OTHER_QUESTION_INTENT.matcher(question).find()
                && PERMISSION_QUESTION.matcher(question).find();
    }

    private boolean containsAffirmativePermission(String value) {
        String withoutProhibitions = PROHIBITION.matcher(value == null ? "" : value).replaceAll(" ");
        return PERMISSION.matcher(withoutProhibitions).find();
    }

    private boolean startsWithYes(String value) {
        return Pattern.compile("(?iu)^\\s*(?:yes|是|可以|能)[,.:：，。—-]?").matcher(value).find();
    }

    private boolean startsWithNo(String value) {
        return Pattern.compile("(?iu)^\\s*(?:no|否|不行|不可以|不能)[,.:：，。—-]?").matcher(value).find();
    }

    private void validateNoNewTemporalScope(String allowedContext, String answer) {
        List<Pattern> temporalScopes = List.of(
                Pattern.compile("(?iu)\\b(?:current|this) turn\\b|当前回合|本回合"),
                Pattern.compile("(?iu)\\b(?:next|later) turn\\b|下一回合|之后的回合"),
                Pattern.compile("(?iu)\\b(?:current|this) round\\b|当前轮|本轮"),
                Pattern.compile("(?iu)\\b(?:next|later) round\\b|下一轮|之后的轮次"),
                Pattern.compile("(?iu)\\b(?:current|this) phase\\b|当前阶段|本阶段"),
                Pattern.compile("(?iu)\\b(?:next|later) phase\\b|下一阶段|之后的阶段"));
        for (Pattern scope : temporalScopes) {
            if (scope.matcher(answer == null ? "" : answer).find()
                    && !scope.matcher(allowedContext == null ? "" : allowedContext).find()) {
                throw new IllegalArgumentException(
                        "permission ruling introduced a temporal scope absent from the question and cited rules");
            }
        }
    }

    private void validateNoNewExactTemporalBoundary(String allowedContext, String answer) {
        Pattern boundary = Pattern.compile(
                "(?iu)\\b(?:at |by )?(?:the )?end of\\b|\\b(?:the )?completion of\\b|结束时|结束后|完成时");
        if (boundary.matcher(answer == null ? "" : answer).find()
                && !boundary.matcher(allowedContext == null ? "" : allowedContext).find()) {
            throw new IllegalArgumentException(
                    "permission ruling introduced an exact temporal boundary absent from the question and cited rules");
        }
    }

    private void validateNoNewMandatoryModal(String allowedContext, String answer) {
        Pattern mandatory = Pattern.compile("(?iu)\\bmust\\b|必须");
        if (mandatory.matcher(answer == null ? "" : answer).find()
                && !mandatory.matcher(allowedContext == null ? "" : allowedContext).find()) {
            throw new IllegalArgumentException(
                    "permission ruling introduced a mandatory modal absent from the question and cited rules");
        }
    }

    private void validateNoNewRepresentationImpossibility(String allowedContext, String answer) {
        Pattern impossibleRepresentation = Pattern.compile(
                "(?iu)\\b(?:cannot|can't) be (?:represented|shown|tracked)\\b|不能(?:被)?(?:表示|展示|追踪)");
        if (impossibleRepresentation.matcher(answer == null ? "" : answer).find()
                && !impossibleRepresentation.matcher(allowedContext == null ? "" : allowedContext).find()) {
            throw new IllegalArgumentException(
                    "permission ruling changed a descriptive representation fact into an impossibility");
        }
    }
}
