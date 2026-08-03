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
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NativeReadToolsTest {

    @Test
    void searchUsesHiddenDocumentScopeAndReturnsBoundedEvidenceHandles() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        UUID documentVersionId = UUID.randomUUID();
        when(readTools.searchRuleEvidence(any())).thenReturn(List.of(new RuleEvidence(
                UUID.randomUUID(), documentVersionId, "SETUP", "Setup", "Place the bounded components.", 2, 2)));
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
        assertThat(result.data().toString()).contains("evidenceId", "pageFrom").doesNotContain(documentVersionId.toString());
    }

    @Test
    void invalidSearchArgumentsNeverReachRetrieval() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        SearchRuleEvidenceNativeTool tool = new SearchRuleEvidenceNativeTool(readTools, JsonMapper.builder().build());

        assertThatThrownBy(() -> tool.execute("{\"query\":\"x\",\"limit\":99}", scope()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(
                        "{\"query\":\"x\",\"limit\":1,\"sectionTypes\":[],"
                                + "\"includeAdjacentContext\":false,\"documentVersionId\":\"attacker-value\"}",
                        scope()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(readTools, never()).searchRuleEvidence(any());
    }

    @Test
    void pageReadRejectsUnboundedOrDuplicatePagesBeforeStorage() {
        AssistantReadTools readTools = mock(AssistantReadTools.class);
        ReadRulePagesNativeTool tool = new ReadRulePagesNativeTool(readTools, JsonMapper.builder().build());

        assertThatThrownBy(() -> tool.execute("{\"pageNumbers\":[1,1]}", scope()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute("{\"pageNumbers\":[1,2,3,4,5,6]}", scope()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(readTools, never()).readRuleEvidencePages(any(), any(), any(Boolean.class));
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
