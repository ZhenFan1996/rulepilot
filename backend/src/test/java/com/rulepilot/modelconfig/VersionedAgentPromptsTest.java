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
                        "Return only the requested JSON schema",
                        "no unsupported next-player handoff")
                .doesNotContain("revision v", "maximum step count", "allotted step count");

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
                        "Use only supplied evidence",
                        "Focused answer-aid routing revision v55",
                        "at most one primary structured aid family",
                        "situationChecks` must always be empty",
                        "Source-authored advice boundary revision v56")
                .doesNotContain("Cited-tie-resolution revision v37", "Cited-worked-example revision v34");

        assertThat(prompts.answerSystem("TIE"))
                .contains("Cited-tie-resolution revision v37")
                .doesNotContain("Cited-rule-options revision v40", "Cited-worked-example revision v34");
        assertThat(prompts.answerSystem("EXAMPLE"))
                .contains("Cited-worked-example revision v34")
                .doesNotContain("Cited-decision-table revision v28", "Cited-tie-resolution revision v37");

        assertThat(prompts.answerUser())
                .contains("{questionType}", "{evidenceNeeds}", "{answerAid}", "{evidence}")
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
                        "defectConfirmed=true",
                        "empty issues array",
                        "Return only the requested JSON schema")
                .doesNotContain("revision v", "keyword checklist", "ATOMIC_CONFIRMATION");

        assertThat(prompts.criticUser()).contains("{mode}", "{objective}", "{claims}", "{evidence}");
        assertThat(prompts.atomicCriticUser())
                .contains(
                        "defectConfirmed=false",
                        "defectConfirmed=true",
                        "complete claim",
                        "current-turn/later-turn distinction",
                        "{claims}",
                        "{evidence}");
        assertThat(prompts.structuredOutputRepair()).contains("schema-valid object only");
        assertThat(prompts.criticOutputRepair()).contains(
                "Return {\"issues\":[]}",
                "defectConfirmed: true",
                "exactly one allowed issue type",
                "Never place a supported");
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
