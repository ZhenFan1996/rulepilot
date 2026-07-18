package com.rulepilot.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.gamesession.GameSessionContextLookup;
import com.rulepilot.gamesession.GameSessionContextLookup.SessionContext;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RulePilotMcpToolsTest {

    private final UUID sessionId = UUID.randomUUID();
    private final UUID editionId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID expansionId = UUID.randomUUID();
    private final GameSessionContextLookup sessions = mock(GameSessionContextLookup.class);
    private final HybridRuleSearch search = mock(HybridRuleSearch.class);
    private final DocumentProcessing documents = mock(DocumentProcessing.class);
    private final ConfirmedRulingLookup rulings = mock(ConfirmedRulingLookup.class);
    private final RulePilotMcpTools tools = new RulePilotMcpTools(sessions, search, documents, rulings);

    @BeforeEach
    void authenticatePlayer() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "player", "unused", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(sessions.findOwned(sessionId, "player")).thenReturn(Optional.of(context()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesFourClosedWorldReadOnlyTools() {
        var annotations = java.util.Arrays.stream(RulePilotMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(annotations).extracting(McpTool::name)
                .containsExactlyInAnyOrder(
                        "search_rules", "get_rule_page", "get_confirmed_ruling", "get_session_context");
        assertThat(annotations).allSatisfy(annotation -> {
            assertThat(annotation.annotations().readOnlyHint()).isTrue();
            assertThat(annotation.annotations().destructiveHint()).isFalse();
            assertThat(annotation.annotations().openWorldHint()).isFalse();
        });
    }

    @Test
    void scopesEveryReadToTheOwnedSessionAndAuthenticatedUser() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                chunkId, versionId, "SCORING", "Final scoring", "Score completed routes.", 8, 8, 0.8);
        when(search.search(
                        org.mockito.ArgumentMatchers.eq(versionId),
                        org.mockito.ArgumentMatchers.eq("How do I score?"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new HybridEvidenceHit(evidence, 0.03, 1, 1, false)));
        when(documents.pages(versionId)).thenReturn(List.of(
                new DocumentProcessing.PageView(8, "Score completed routes.", 23)));
        var answer = new ConfirmedRulingLookup.ConfirmedAnswer(
                UUID.randomUUID(), versionId, "Score routes", "Add each completed route.",
                List.of(new ConfirmedRulingLookup.Citation(
                        chunkId, versionId, "SCORING", "Final scoring", "Score completed routes.", 8, 8)),
                List.of(), "HIGH", false, 1);
        when(rulings.find(versionId, Set.of(expansionId), "How do I score?", "player"))
                .thenReturn(Optional.of(answer));

        assertThat(tools.searchRules(sessionId, "How do I score?", 5).hits()).hasSize(1);
        assertThat(tools.getRulePage(sessionId, 8).text()).isEqualTo("Score completed routes.");
        assertThat(tools.getConfirmedRuling(sessionId, "How do I score?").rulingId())
                .isEqualTo(answer.rulingId());
        var result = tools.getSessionContext(sessionId);
        assertThat(result.documentVersionId()).isEqualTo(versionId);
        assertThat(result.activePlayerAssigned()).isTrue();
        assertThat(result.activePlayer()).isEqualTo(2);

        verify(sessions, org.mockito.Mockito.times(4)).findOwned(sessionId, "player");
        verify(rulings).find(versionId, Set.of(expansionId), "How do I score?", "player");
    }

    @Test
    void rejectsCallsWithoutAuthentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> tools.getSessionContext(sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP authentication is required");
    }

    private SessionContext context() {
        return new SessionContext(
                sessionId, editionId, versionId, Set.of(expansionId), 4, 3, "SCORING", 2, "ACTIVE");
    }
}
