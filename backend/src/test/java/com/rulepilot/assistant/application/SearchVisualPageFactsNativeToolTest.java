package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.RuleFactStatus;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit.ContentKind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SearchVisualPageFactsNativeToolTest {

    @Test
    void discoversAVisualPageThroughAPageBoundHandleWithoutGrantingRuleAuthority() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        VisualRulebookPageFactSearch facts = Mockito.mock(VisualRulebookPageFactSearch.class);
        RuleEvidenceLookup evidence = Mockito.mock(RuleEvidenceLookup.class);
        when(facts.search(versionId, "red prism A7", 2))
                .thenReturn(List.of(new PageFactMatch(
                        4,
                        "A7 red prism",
                        "A red prism is pictured beside the A7 lane.",
                        List.of("A7", "red prism"),
                        10.5,
                        RuleFactStatus.CURRENT_RULE_FACTS)));
        when(evidence.findByPageNumbers(versionId, Set.of(4)))
                .thenReturn(List.of(new RuleEvidenceHit(
                        evidenceId,
                        versionId,
                        "GENERAL",
                        "Visual page 4",
                        "Inspect the rendered page.",
                        4,
                        4,
                        1.0,
                        ContentKind.VISUAL_PLACEHOLDER,
                        "Inspect the rendered page.")));
        var tool = new SearchVisualPageFactsNativeTool(
                facts, evidence, JsonMapper.builder().findAndAddModules().build());

        var observation = tool.execute(
                "{\"query\":\"red prism A7\",\"limit\":2}",
                new ToolScope("player", versionId, UUID.randomUUID(), Instant.now().plusSeconds(30)));

        assertThat(observation.status()).isEqualTo(ObservationStatus.SUCCESS);
        assertThat(observation.code()).isEqualTo("VISUAL_PAGE_FACTS_FOUND");
        assertThat(observation.data()).containsEntry("mechanicalRuleAuthority", false);
        assertThat(observation.data().toString())
                .contains(evidenceId.toString(), "A red prism is pictured", "mechanicalRuleAuthority=false");
        assertThat(observation.evidenceCount()).isEqualTo(1);
    }

    @Test
    void withholdsAnUnboundVisualMatchFromTheAgent() {
        UUID versionId = UUID.randomUUID();
        VisualRulebookPageFactSearch facts = Mockito.mock(VisualRulebookPageFactSearch.class);
        RuleEvidenceLookup evidence = Mockito.mock(RuleEvidenceLookup.class);
        when(facts.search(versionId, "unbound icon", 1))
                .thenReturn(List.of(new PageFactMatch(
                        9,
                        "unbound icon",
                        "An icon is visible.",
                        List.of("icon"),
                        1.0,
                        RuleFactStatus.FACTS_INCOMPLETE)));
        when(evidence.findByPageNumbers(versionId, Set.of(9))).thenReturn(List.of());
        var tool = new SearchVisualPageFactsNativeTool(
                facts, evidence, JsonMapper.builder().findAndAddModules().build());

        var observation = tool.execute(
                "{\"query\":\"unbound icon\",\"limit\":1}",
                new ToolScope("player", versionId, UUID.randomUUID(), Instant.now().plusSeconds(30)));

        assertThat(observation.status()).isEqualTo(ObservationStatus.PARTIAL);
        assertThat(observation.code()).isEqualTo("VISUAL_PAGE_FACTS_NOT_FOUND");
        assertThat(observation.evidenceCount()).isZero();
    }
}
