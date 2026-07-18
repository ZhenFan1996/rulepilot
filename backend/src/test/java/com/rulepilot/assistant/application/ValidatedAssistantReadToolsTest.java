package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidatedAssistantReadToolsTest {

    @Test
    void validatesAndScopesRuleSearchBeforeReturningEvidence() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        HybridRuleSearch retrieval = (requestedVersion, query, options) -> {
            assertThat(requestedVersion).isEqualTo(versionId);
            assertThat(query).isEqualTo("setup board");
            assertThat(options.limit()).isEqualTo(4);
            assertThat(options.sectionTypes()).containsExactly("SETUP");
            return List.of(new HybridEvidenceHit(
                    evidence(chunkId, versionId), 0.01, 1, null, true));
        };
        var tools = new ValidatedAssistantReadTools(retrieval);

        var result = tools.searchRuleEvidence(new SearchRuleEvidence(
                versionId, " setup board ", 4, Set.of("setup"), "setup"));

        assertThat(result).singleElement().satisfies(hit -> {
            assertThat(hit.chunkId()).isEqualTo(chunkId);
            assertThat(hit.documentVersionId()).isEqualTo(versionId);
            assertThat(hit.pageFrom()).isEqualTo(2);
        });
    }

    @Test
    void rejectsInvalidParametersAndCrossVersionEvidence() {
        UUID versionId = UUID.randomUUID();
        var tools = new ValidatedAssistantReadTools((requestedVersion, query, options) -> List.of(
                new HybridEvidenceHit(evidence(UUID.randomUUID(), UUID.randomUUID()), 0.01, 1, null, true)));

        assertThatThrownBy(() -> tools.searchRuleEvidence(
                        new SearchRuleEvidence(versionId, "setup", 11, Set.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.searchRuleEvidence(
                        new SearchRuleEvidence(versionId, "setup", 4, Set.of("SETUP"), "SETUP")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside document scope");
    }

    private RuleEvidenceHit evidence(UUID chunkId, UUID versionId) {
        return new RuleEvidenceHit(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Place the board in the center.",
                2,
                2,
                0.9);
    }
}
