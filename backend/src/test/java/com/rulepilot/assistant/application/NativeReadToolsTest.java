package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContext;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidencePage;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NativeReadToolsTest {

    @Test
    void contextExpansionReturnsBoundedAnchorsAndNeighborsWithoutClaimingApplicability() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        UUID neighborId = UUID.randomUUID();
        when(readTools.readRuleEvidenceContext(documentVersionId, Set.of(anchorId), 2)).thenReturn(
                new RuleEvidenceContext(
                        List.of(new RuleEvidence(
                                anchorId, documentVersionId, "ACTION", "Take an action",
                                "Choose one action from the list.", 6, 6)),
                        List.of(new RuleEvidence(
                                neighborId, documentVersionId, "ACTION", "Restriction",
                                "You may choose each action only once.", 7, 7))));
        var tool = new ExpandRuleEvidenceContextNativeTool(readTools, JsonMapper.builder().build());

        var result = tool.execute(
                "{\"evidenceIds\":[\"" + anchorId + "\"],\"radius\":2}",
                new ToolScope("player", documentVersionId, UUID.randomUUID(), Instant.now().plusSeconds(30)));

        assertThat(result.code()).isEqualTo("EVIDENCE_CONTEXT_EXPANDED");
        assertThat(result.evidenceCount()).isEqualTo(2);
        assertThat(result.data())
                .containsEntry("requestedAnchorCount", 1)
                .containsEntry("returnedAnchorCount", 1)
                .containsEntry("contextApplicabilityAuthority", false)
                .containsEntry("nextAction", "READ_EXACT_PAGES_AND_CHECK_APPLICABILITY");
        assertThat(result.data().toString())
                .contains(anchorId.toString(), neighborId.toString(), "You may choose each action only once.")
                .doesNotContain(documentVersionId.toString());
        verify(readTools).readRuleEvidenceContext(documentVersionId, Set.of(anchorId), 2);
    }

    @Test
    void contextExpansionPassesEverySelectedHandleAndPositiveRadius() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        List<UUID> anchorIds = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        Set<UUID> anchors = Set.copyOf(anchorIds);
        ToolScope scope = scope();
        when(readTools.readRuleEvidenceContext(scope.documentVersionId(), anchors, 3))
                .thenReturn(new RuleEvidenceContext(List.of(), List.of()));
        var tool = new ExpandRuleEvidenceContextNativeTool(readTools, JsonMapper.builder().build());
        String arguments = anchorIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{\"evidenceIds\":[", "],\"radius\":3}"));

        var result = tool.execute(arguments, scope);

        assertThat(result.code()).isEqualTo("NO_CONTEXT_ANCHOR");
        verify(readTools).readRuleEvidenceContext(scope.documentVersionId(), anchors, 3);
    }

    @Test
    void contextExpansionRejectsMalformedDuplicateAndHiddenScopeArguments() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        var tool = new ExpandRuleEvidenceContextNativeTool(readTools, JsonMapper.builder().build());
        UUID anchorId = UUID.randomUUID();

        assertThatThrownBy(() -> tool.execute("{\"evidenceIds\":[\"not-a-uuid\"],\"radius\":1}", scope()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(
                        "{\"evidenceIds\":[\"" + anchorId + "\",\"" + anchorId + "\"],\"radius\":1}", scope()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(
                        "{\"evidenceIds\":[\"" + anchorId + "\"],\"radius\":1,"
                                + "\"documentVersionId\":\"attacker\"}", scope()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(readTools, never()).readRuleEvidenceContext(any(), any(), any(Integer.class));
    }

    @Test
    void relationshipSearchUsesTheCompleteModelSelectedTopicWithoutClassifyingItsMeaning() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        UUID documentVersionId = UUID.randomUUID();
        RuleEvidence ordinary = new RuleEvidence(
                UUID.randomUUID(), documentVersionId, "RULE", "Movement", "Move one space.", 2, 2);
        RuleEvidence exception = new RuleEvidence(
                UUID.randomUUID(), documentVersionId, "POWER", "Heavy cargo",
                "Unless the cargo is secured, this movement does not apply.", 7, 7);
        RuleEvidence replacement = new RuleEvidence(
                UUID.randomUUID(), documentVersionId, "ABILITY", "快速移动", "改为移动两格。", 9, 9);
        when(readTools.searchRuleEvidence(any())).thenAnswer(invocation -> {
            AssistantReadTools.SearchRuleEvidence request = invocation.getArgument(0);
            if (request.query().contains("例外")) return List.of(replacement);
            if (request.query().contains("exception")) return List.of(ordinary, exception);
            return List.of(ordinary);
        });
        var tool = new SearchRuleRelationshipsNativeTool(readTools, JsonMapper.builder().build());

        String completeTopic = "movement timing and conditional replacement ".repeat(10).strip();
        var result = tool.execute(
                "{\"topic\":\"" + completeTopic + "\",\"limit\":9}",
                new ToolScope(
                        "player", documentVersionId, UUID.randomUUID(), Instant.now().plusSeconds(30), 20_000));

        ArgumentCaptor<AssistantReadTools.SearchRuleEvidence> requests =
                ArgumentCaptor.forClass(AssistantReadTools.SearchRuleEvidence.class);
        verify(readTools).searchRuleEvidence(requests.capture());
        assertThat(requests.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.documentVersionId()).isEqualTo(documentVersionId);
                    assertThat(request.query()).isEqualTo(completeTopic);
                    assertThat(request.limit()).isEqualTo(9);
                    assertThat(request.includeAdjacentContext()).isTrue();
                    assertThat(request.includePageImages()).isFalse();
                });
        assertThat(result.code()).isEqualTo("RELATIONSHIP_CANDIDATES_FOUND");
        assertThat(result.evidenceCount()).isEqualTo(1);
        assertThat(result.data())
                .containsEntry("relationshipClassificationAuthority", false)
                .containsEntry("nextAction", "READ_EXACT_PAGES_AND_COMPARE_APPLICABILITY");
        assertThat(result.data().toString())
                .contains("Move one space.")
                .contains("classificationAuthority=false", "evidenceMechanicalAuthority=true")
                .doesNotContain(documentVersionId.toString());
    }

    @Test
    void relationshipSearchRejectsUnknownFieldsAndOutOfScopeEvidence() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        var tool = new SearchRuleRelationshipsNativeTool(readTools, JsonMapper.builder().build());

        assertThatThrownBy(() -> tool.execute(
                        "{\"topic\":\"movement\",\"limit\":3,\"documentVersionId\":\"attacker\"}", scope()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(readTools, never()).searchRuleEvidence(any());

        when(readTools.searchRuleEvidence(any())).thenReturn(List.of(new RuleEvidence(
                UUID.randomUUID(), UUID.randomUUID(), "RULE", "Movement", "Unless stopped, move.", 2, 2)));
        assertThatThrownBy(() -> tool.execute("{\"topic\":\"movement\",\"limit\":3}", scope()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void searchUsesHiddenDocumentScopeAndReturnsCompleteCanonicalEvidenceHandles() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        UUID documentVersionId = UUID.randomUUID();
        String completeExcerpt = "A complete canonical rule sentence. ".repeat(70) + "END_OF_CANONICAL_CHUNK";
        when(readTools.searchRuleEvidence(any())).thenReturn(List.of(new RuleEvidence(
                UUID.randomUUID(), documentVersionId, "SETUP", "Setup", completeExcerpt, 2, 2)));
        SearchRuleEvidenceNativeTool tool = new SearchRuleEvidenceNativeTool(readTools, JsonMapper.builder().build());
        ToolScope scope = new ToolScope("player", documentVersionId, UUID.randomUUID(), Instant.now().plusSeconds(30));

        var result = tool.execute(
                "{\"query\":\"setup\",\"limit\":3,\"sectionTypes\":[],\"includeAdjacentContext\":true}",
                scope);

        ArgumentCaptor<AssistantReadTools.SearchRuleEvidence> request =
                ArgumentCaptor.forClass(AssistantReadTools.SearchRuleEvidence.class);
        verify(readTools).searchRuleEvidence(request.capture());
        assertThat(request.getValue().documentVersionId()).isEqualTo(documentVersionId);
        assertThat(result.evidenceCount()).isEqualTo(1);
        assertThat(result.data().toString())
                .contains("evidenceId", "pageFrom", "END_OF_CANONICAL_CHUNK")
                .doesNotContain(documentVersionId.toString());
    }

    @Test
    void searchPassesCompleteTypedQueryFiltersAndRequestedCandidateCount() throws Exception {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        SearchRuleEvidenceNativeTool tool = new SearchRuleEvidenceNativeTool(
                readTools, JsonMapper.builder().build());
        String query = "complete turn order condition ".repeat(30).strip();
        List<String> sectionTypes = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> "SECTION_" + index)
                .toList();
        ToolScope scope = scope();
        when(readTools.searchRuleEvidencePage(any(), org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(6), org.mockito.ArgumentMatchers.eq(Set.of())))
                .thenReturn(new RuleEvidencePage(List.of(), false, 0));
        String arguments = JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
                "query", query,
                "limit", 12,
                "sectionTypes", sectionTypes,
                "includeAdjacentContext", false));

        var result = tool.execute(arguments, scope);

        ArgumentCaptor<AssistantReadTools.SearchRuleEvidence> request =
                ArgumentCaptor.forClass(AssistantReadTools.SearchRuleEvidence.class);
        verify(readTools).searchRuleEvidencePage(request.capture(),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(6),
                org.mockito.ArgumentMatchers.eq(Set.of()));
        assertThat(request.getValue().query()).isEqualTo(query);
        assertThat(request.getValue().limit()).isEqualTo(12);
        assertThat(request.getValue().sectionTypes()).containsExactlyInAnyOrderElementsOf(sectionTypes);
        assertThat(result.code()).isEqualTo("NO_EVIDENCE");
    }

    @Test
    void invalidSearchArgumentsNeverReachRetrieval() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        SearchRuleEvidenceNativeTool tool = new SearchRuleEvidenceNativeTool(readTools, JsonMapper.builder().build());

        assertThatThrownBy(() -> tool.execute(
                        "{\"query\":\"x\",\"limit\":0,\"sectionTypes\":[],"
                                + "\"includeAdjacentContext\":false}",
                        scope()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(
                        "{\"query\":\"x\",\"limit\":1,\"sectionTypes\":[],"
                                + "\"includeAdjacentContext\":false,\"documentVersionId\":\"attacker-value\"}",
                        scope()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(readTools, never()).searchRuleEvidence(any());
    }

    @Test
    void pageReadRejectsDuplicatesButPassesEveryValidExactPageToAdaptiveStorage() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        ReadRulePagesNativeTool tool = new ReadRulePagesNativeTool(readTools, JsonMapper.builder().build());

        assertThatThrownBy(() -> tool.execute("{\"pageNumbers\":[1,1]}", scope()))
                .isInstanceOf(IllegalArgumentException.class);
        ToolScope scope = scope();
        Set<Integer> pages = Set.of(1, 2, 3, 4, 5, 6, 7, 8);
        Set<Integer> firstBatch = Set.of(1, 2, 3, 4, 5, 6);
        when(readTools.readRuleEvidencePagesPage(scope.documentVersionId(), firstBatch, false, 0, 6))
                .thenReturn(new RuleEvidencePage(List.of(), false, 0));

        var result = tool.execute("{\"pageNumbers\":[1,2,3,4,5,6,7,8]}", scope);

        assertThat(result.code()).isEqualTo("NO_PAGE_EVIDENCE");
        assertThat(result.data()).containsEntry("hasMore", true);
        verify(readTools).readRuleEvidencePagesPage(scope.documentVersionId(), firstBatch, false, 0, 6);
    }

    @Test
    void pageReadReturnsNoImagesAndKeepsTheHiddenVersionScope() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        UUID documentVersionId = UUID.randomUUID();
        when(readTools.readRuleEvidencePages(documentVersionId, Set.of(3), false)).thenReturn(List.of());
        ReadRulePagesNativeTool tool = new ReadRulePagesNativeTool(readTools, JsonMapper.builder().build());

        var result = tool.execute(
                "{\"pageNumbers\":[3]}",
                new ToolScope("player", documentVersionId, UUID.randomUUID(), Instant.now().plusSeconds(30)));

        assertThat(result.code()).isEqualTo("NO_PAGE_EVIDENCE");
        assertThat(result.evidenceCount()).isZero();
        verify(readTools).readRuleEvidencePages(documentVersionId, Set.of(3), false);
    }

    private ToolScope scope() {
        return new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30));
    }
}
