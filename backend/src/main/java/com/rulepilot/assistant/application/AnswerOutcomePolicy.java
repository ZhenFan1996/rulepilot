package com.rulepilot.assistant.application;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.UUID;
import java.util.stream.Collectors;

/** Maps the already validated Agent terminal without rewriting player prose. */
final class AnswerOutcomePolicy {

    private AnswerOutcomePolicy() {}

    static RuleAnswering.AnswerResult publicReaderAnswer(
            UUID assistantRunId,
            StructuredRuleAnswer answer,
            String currentQuestion,
            PlayerLocale requestedLanguage) {
        PlayerFacingRuleAnswer presented = PlayerFacingAnswerPresenter.present(
                answer, currentQuestion, requestedLanguage);
        return new RuleAnswering.AnswerResult(
                assistantRunId,
                new RuleAnswering.Answer(
                        presented.status().name(),
                        presented.shortVerdict(),
                        presented.explanation(),
                        presented.citations().stream()
                                .map(citation -> new RuleAnswering.Citation(
                                        citation.heading(), citation.pageFrom(), citation.pageTo()))
                                .toList(),
                        presented.exceptions(),
                        presented.confidence().name(),
                        presented.answerBasis() == null ? null : presented.answerBasis().name(),
                        presented.clarification(),
                        presented.warnings().stream()
                                .map(warning -> new RuleAnswering.Warning(warning.type().name()))
                                .toList()),
                answer.citations().stream()
                        .map(RuleCitation::chunkId)
                        .collect(Collectors.toUnmodifiableSet()));
    }
}
