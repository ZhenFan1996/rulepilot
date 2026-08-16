package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Mechanical player-facing detection; semantic review is risk-selected after deterministic citation gates. */
final class AnswerDraftSafetyPolicy {

    private static final List<String> INTERNAL_IDENTIFIERS = List.of(
            "assistantrunid",
            "conversationturnid",
            "documentversionid",
            "citationid",
            "citationids",
            "hybridrulesearch",
            "calculaterulemath",
            "traceruledependencies",
            "defineruleterms",
            "illustraterule",
            "compareruleconcepts",
            "listruleoptions",
            "showruleevidence",
            "answered_with_warning",
            "clarification_required",
            "insufficient_evidence",
            "invalid_model_output",
            "model_timeout",
            "version_conflict",
            "system prompt",
            "system_prompt",
            "system-prompt",
            "assistant prompt",
            "assistant_prompt",
            "assistant-prompt",
            "user prompt",
            "user_prompt",
            "user-prompt",
            "系统提示词",
            "助手提示词",
            "用户提示词",
            "系统提示",
            "助手提示",
            "用户提示",
            "模型输出校验",
            "模型输出验证",
            "模型输出解析",
            "模型输出错误",
            "模型输出失败",
            "模型输出无效",
            "模型输出未通过",
            "模型响应校验",
            "结构化输出校验");
    private static final List<String> TOOL_PREFIXES = List.of("checkRule", "buildRule", "resolveRule");
    private static final List<String> DIAGNOSTIC_SUBJECTS = List.of("model", "schema", "json", "yaml");
    private static final List<String> DIAGNOSTIC_RESULTS = List.of(
            "output", "response", "validation", "parse", "parsing", "error", "failure", "invalid");
    private AnswerDraftSafetyPolicy() {}

    /** Compatibility boundary: semantic source-absence claims are no longer deleted from prose. */
    static ModelDraft normalizeSourceAbsenceClaims(ModelRequest request, ModelDraft draft) {
        return draft;
    }

    static boolean containsInternalEvidenceReference(ModelDraft draft) {
        return draft != null && containsInternalEvidenceReference(playerFacingText(draft));
    }

    static boolean containsInternalCoreReference(ModelDraft draft) {
        if (draft == null) return false;
        return containsInternalEvidenceReference(draft.shortVerdict())
                || containsInternalEvidenceReference(draft.explanation())
                || draft.exceptions().stream().anyMatch(AnswerDraftSafetyPolicy::containsInternalEvidenceReference);
    }

    static boolean containsInternalEvidenceReference(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return containsUuid(value)
                || containsStandaloneWord(lower, "chunk")
                || containsStandaloneWord(lower, "chunkid")
                || containsEvidenceLabel(lower)
                || containsShortHexLabel(lower)
                || INTERNAL_IDENTIFIERS.stream().anyMatch(lower::contains)
                || containsCamelCaseProtocolName(value, "native")
                || containsCamelCaseProtocolName(value, "repair")
                || TOOL_PREFIXES.stream().anyMatch(prefix -> containsCamelCaseProtocolName(value, prefix))
                || containsEnglishDiagnostic(lower);
    }

    private static boolean containsUuid(String value) {
        for (int start = 0; start + 36 <= value.length(); start++) {
            if (isUuidAt(value, start)) return true;
        }
        return false;
    }

    private static boolean isUuidAt(String value, int start) {
        for (int offset = 0; offset < 36; offset++) {
            char character = value.charAt(start + offset);
            if (offset == 8 || offset == 13 || offset == 18 || offset == 23) {
                if (character != '-') return false;
            } else if (!isHex(Character.toLowerCase(character))) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsEvidenceLabel(String value) {
        if (containsBracketedEvidenceLabel(value)) return true;
        for (String prefix : List.of("evidence", "source", "证据", "引用")) {
            int start = value.indexOf(prefix);
            while (start >= 0) {
                int cursor = start + prefix.length();
                while (cursor < value.length()
                        && (Character.isWhitespace(value.charAt(cursor)) || value.charAt(cursor) == '[')) {
                    cursor++;
                }
                if (cursor < value.length() && value.charAt(cursor) == 'e' && hasDigitsAfter(value, cursor + 1)) {
                    return true;
                }
                start = value.indexOf(prefix, start + 1);
            }
        }
        return false;
    }

    private static boolean containsBracketedEvidenceLabel(String value) {
        for (int start = 0; start + 3 < value.length(); start++) {
            if (value.charAt(start) == '['
                    && value.charAt(start + 1) == 'e'
                    && hasDigitsAfter(value, start + 2)) {
                int cursor = start + 2;
                while (cursor < value.length() && Character.isDigit(value.charAt(cursor))) cursor++;
                if (cursor < value.length() && value.charAt(cursor) == ']') return true;
            }
        }
        return false;
    }

    private static boolean hasDigitsAfter(String value, int cursor) {
        return cursor < value.length() && Character.isDigit(value.charAt(cursor));
    }

    private static boolean containsShortHexLabel(String value) {
        for (int start = 0; start + 9 < value.length(); start++) {
            if (value.charAt(start) != '[' || value.charAt(start + 9) != ']') continue;
            boolean allHex = true;
            for (int cursor = start + 1; cursor < start + 9; cursor++) {
                if (!isHex(value.charAt(cursor))) {
                    allHex = false;
                    break;
                }
            }
            if (allHex) return true;
        }
        return false;
    }

    private static boolean containsStandaloneWord(String value, String word) {
        int start = value.indexOf(word);
        while (start >= 0) {
            int end = start + word.length();
            boolean leftBoundary = start == 0 || !Character.isLetterOrDigit(value.charAt(start - 1));
            boolean rightBoundary = end == value.length() || !Character.isLetterOrDigit(value.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            start = value.indexOf(word, start + 1);
        }
        return false;
    }

    private static boolean containsCamelCaseProtocolName(String value, String prefix) {
        int start = value.indexOf(prefix);
        while (start >= 0) {
            int next = start + prefix.length();
            boolean leftBoundary = start == 0 || !Character.isLetterOrDigit(value.charAt(start - 1));
            if (leftBoundary && next < value.length() && Character.isUpperCase(value.charAt(next))) return true;
            start = value.indexOf(prefix, start + 1);
        }
        return false;
    }

    private static boolean containsEnglishDiagnostic(String lower) {
        return DIAGNOSTIC_SUBJECTS.stream().anyMatch(subject -> containsStandaloneWord(lower, subject))
                && DIAGNOSTIC_RESULTS.stream().anyMatch(result -> containsStandaloneWord(lower, result));
    }

    static boolean containsKnownEvidenceReference(String value, Collection<UUID> evidenceIds) {
        if (value == null || value.isBlank() || evidenceIds == null || evidenceIds.isEmpty()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return evidenceIds.stream().anyMatch(id -> {
            if (id == null) return false;
            String full = id.toString().toLowerCase(Locale.ROOT);
            return containsHexToken(normalized, full) || containsHexToken(normalized, full.substring(0, 8));
        });
    }

    private static boolean containsHexToken(String value, String candidate) {
        int start = value.indexOf(candidate);
        while (start >= 0) {
            int end = start + candidate.length();
            boolean leftBoundary = start == 0 || !isHex(value.charAt(start - 1));
            boolean rightBoundary = end == value.length() || !isHex(value.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            start = value.indexOf(candidate, start + 1);
        }
        return false;
    }

    private static boolean isHex(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }

    private static String playerFacingText(ModelDraft draft) {
        List<String> text = new ArrayList<>();
        text.add(draft.shortVerdict());
        text.add(draft.explanation());
        text.addAll(draft.exceptions());
        draft.calculations().forEach(value -> {
            if (value != null) text.add(value.expression());
        });
        draft.situationChecks().forEach(value -> {
            if (value != null) {
                text.add(value.requirement());
                text.add(value.playerFact());
            }
        });
        draft.walkthroughSteps().forEach(value -> {
            if (value != null) {
                text.add(value.instruction());
                text.add(value.explanation());
            }
        });
        draft.decisionBranches().forEach(value -> {
            if (value != null) {
                text.add(value.condition());
                text.add(value.outcome());
            }
        });
        draft.exceptionClauses().forEach(value -> {
            if (value != null) {
                text.add(value.condition());
                text.add(value.effect());
            }
        });
        draft.termDefinitions().forEach(value -> {
            if (value != null) {
                text.add(value.term());
                text.add(value.definition());
                text.add(value.boundary());
            }
        });
        draft.workedExamples().forEach(value -> {
            if (value != null) {
                text.add(value.setup());
                text.add(value.action());
                text.add(value.outcome());
            }
        });
        draft.priorityResolutions().forEach(value -> {
            if (value != null) {
                text.add(value.baseRule());
                text.add(value.competingRule());
                text.add(value.resolution());
            }
        });
        draft.timingResolutions().forEach(value -> {
            if (value != null) {
                text.add(value.timingContext());
                text.add(value.resolutionOrder());
                text.add(value.orderSource());
            }
        });
        draft.tieResolutions().forEach(value -> {
            if (value != null) {
                text.add(value.tieContext());
                if (value.resolutionSteps() != null) text.addAll(value.resolutionSteps());
                text.add(value.finalOutcome());
            }
        });
        draft.scopeResolutions().forEach(value -> {
            if (value != null) {
                text.add(value.ruleContext());
                text.add(value.governingCondition());
                text.add(value.currentSituation());
                text.add(value.effect());
            }
        });
        draft.conceptComparisons().forEach(value -> {
            if (value != null) {
                text.add(value.leftConcept());
                text.add(value.leftDefinition());
                text.add(value.rightConcept());
                text.add(value.rightDefinition());
                text.add(value.commonGround());
                text.add(value.keyDifference());
                text.add(value.practicalBoundary());
            }
        });
        draft.ruleOptions().forEach(value -> {
            if (value != null) {
                text.add(value.decisionContext());
                text.add(value.selectionRule());
                text.add(value.optionName());
                text.add(value.availabilityCondition());
                text.add(value.result());
            }
        });
        return text.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
    }
}
