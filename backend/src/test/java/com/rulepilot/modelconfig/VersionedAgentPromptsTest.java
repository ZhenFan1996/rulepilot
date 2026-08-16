package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = VersionedAgentPrompts.class)
class VersionedAgentPromptsTest {

    @Autowired
    private VersionedAgentPrompts prompts;

    @Test
    void runtimeTeachingPromptKeepsStableEvidenceAndOutputBoundaries() {
        assertThat(prompts.teachingRuntimeSystem())
                .contains(
                        "evidence-grounded Teaching Agent",
                        "untrusted data",
                        "current-section evidence",
                        "directly support the whole claim",
                        "complete tie handling",
                        "aggregation dimension",
                        "per card, per player, per category",
                        "once overall",
                        "coverage_checklist contains source_coverage",
                        "or source_contract_v1",
                        "Source identifiers are internal evidence locators",
                        "directEvidenceIds is",
                        "retrieval-bound evidence allow-list",
                        "coverage_checklist contains source_dependency",
                        "procedure is not present in the current document",
                        "sole exception for plainly naming a cited missing source",
                        "Return only the requested JSON schema",
                        "no unsupported next-player handoff")
                .doesNotContain("revision v", "maximum step count", "allotted step count")
                .hasSizeLessThan(8_500);

        assertThat(prompts.teachingUser())
                .contains(
                        "{objective}",
                        "{coverage}",
                        "{evidence}",
                        "{visualPages}",
                        "{repair}",
                        "Without page images, visualCaption describes a concise",
                        "text-based rules aid")
                .doesNotContain("{totalDuration}", "{maxSteps}", "{players}");
    }

    @Test
    void answerRuntimeLoadsOnlyTheSelectedStructuredAidFamily() {
        assertThat(prompts.answerSystem("NONE"))
                .contains(
                        "Use only supplied evidence for factual claims",
                        "Treat validated subquestions independently",
                        "fully answer every supported part",
                        "ask one precise, useful source-locating question",
                        "must end with exactly one direct clarification question",
                        "A search miss or an excerpt's silence is never proof",
                        "A neighboring or merely related excerpt is not a citation",
                        "leave their current satisfaction",
                        "do not convert objectives, scoring routes, or legal actions into advice",
                        "Use at most the one structured aid family selected by `answerAid`",
                        "Return no markdown, analysis, chain of thought")
                .doesNotContain(
                        "revision v",
                        "Cited-tie-resolution revision v37",
                        "Cited-worked-example revision v34")
                .hasSizeLessThan(8_000);

        assertThat(prompts.answerSystem("TIE"))
                .contains("Cited-tie-resolution revision v37")
                .doesNotContain("Cited-rule-options revision v40", "Cited-worked-example revision v34")
                .hasSizeLessThan(10_000);
        assertThat(prompts.answerSystem("EXAMPLE"))
                .contains("Cited-worked-example revision v34")
                .doesNotContain("Cited-decision-table revision v28", "Cited-tie-resolution revision v37")
                .hasSizeLessThan(10_000);

        assertThat(prompts.answerUser())
                .contains(
                        "{questionType}",
                        "{evidenceNeeds}",
                        "{subquestions}",
                        "{answerAid}",
                        "{referenceBinding}",
                        "{currentRuleObjects}",
                        "{pageHints}",
                        "Page locator hints only identify",
                        "candidate pages",
                        "Never turn excerpt silence or",
                        "Do not ask the player whether the rulebook contains the missing advice",
                        "{evidence}")
                .doesNotContain("{lessonSection}", "{playerCount}");
    }

    @Test
    void criticRuntimeUsesOneCompactSemanticDefectContract() {
        assertThat(prompts.criticSystem())
                .contains(
                        "independent evidence critic",
                        "judge meaning rather than keyword overlap",
                        "UNSUPPORTED_CLAIM",
                        "CONTRADICTION",
                        "MISSING_EXCEPTION",
                        "MISSING_CRITICAL_RULE",
                        "OVERREACH",
                        "CHAPTER_SCOPE_DUPLICATION",
                        "at most 12 issues",
                        "setup, legal actions, timing, scoring, game end",
                        "aggregation owner",
                        "worked total",
                        "claimAspect",
                        "QUANTITY",
                        "MULTIPLIER",
                        "TIMING",
                        "SUBJECT",
                        "NEGATION",
                        "defectConfirmed=true",
                        "empty issues array",
                        "explicitly named rule objects",
                        "page hint is only a retrieval locator",
                        "Advertising copy, contents or listings, placeholders",
                        "Return only the requested JSON schema")
                .doesNotContain("revision v", "keyword checklist", "ATOMIC_CONFIRMATION");

        assertThat(prompts.criticUser()).contains("{mode}", "{objective}", "{claims}", "{evidence}");
        assertThat(prompts.atomicCriticUser())
                .contains(
                        "defectConfirmed=false",
                        "defectConfirmed=true",
                        "claim-position/type/claimAspect",
                        "complete claim",
                        "current-turn/later-turn distinction",
                        "{claims}",
                        "{evidence}");
        assertThat(prompts.atomicCriticSystem())
                .contains(
                        "authoritative target",
                        "page hint only locates candidates",
                        "descriptive visual",
                        "metadata",
                        "does not directly entail the mechanical conclusion");
        assertThat(prompts.objectiveCoverageCriticSystem())
                .contains("claimAspect", "MISSING_CRITICAL_RULE", "evidenceIds");
        assertThat(prompts.structuredOutputRepair()).contains("schema-valid object only");
        assertThat(prompts.criticOutputRepair()).contains(
                "Return {\"issues\":[]}",
                "defectConfirmed: true",
                "claimAspect",
                "at least one evidenceIds reference owned by that claim",
                "exactly one allowed issue type",
                "Never place a supported");

        assertThat(prompts.teachingOutlineSystem()).contains(
                "quantitative relationship",
                "aggregation owner",
                "worked example",
                "Source-dependency planning revision v17",
                "source_dependency",
                "missing_setup_source",
                "must not also receive the setup tag",
                "Source coverage contract revision v18",
                "sourceCoverageInventoryComplete",
                "sourceCoverageSlots",
                "LEGAL_ACTION",
                "NECESSARY_EXCEPTION",
                "ownerTopicKey");
    }

    @Test
    void supportingPromptContractsKeepUntrustedInputAndLocalizationBoundaries() {
        assertThat(prompts.answerRetrievalRewriteSystem())
                .contains("retrieval", "untrusted data", "Return JSON only");
        assertThat(prompts.answerRetrievalRewriteUser())
                .contains("{question}", "{previousQuestion}")
                .doesNotContain("{lessonSection}");
        assertThat(prompts.lessonLocalizationSystem())
                .contains("constrained translation", "Do not add rules");
        assertThat(prompts.lessonLocalizationUser()).contains("{targetLanguage}", "{section}");
    }
}
