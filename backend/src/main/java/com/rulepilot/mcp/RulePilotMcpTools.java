package com.rulepilot.mcp;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.gamesession.GameSessionContextLookup;
import com.rulepilot.gamesession.GameSessionContextLookup.SessionContext;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class RulePilotMcpTools {

    private final GameSessionContextLookup sessions;
    private final HybridRuleSearch search;
    private final DocumentProcessing documents;
    private final ConfirmedRulingLookup rulings;

    public RulePilotMcpTools(
            GameSessionContextLookup sessions,
            HybridRuleSearch search,
            DocumentProcessing documents,
            ConfirmedRulingLookup rulings) {
        this.sessions = sessions;
        this.search = search;
        this.documents = documents;
        this.rulings = rulings;
    }

    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "search_rules",
            description = "Search cited rule evidence within a game session owned by the authenticated user.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Search session rules",
                    readOnlyHint = true,
                    destructiveHint = false,
                    openWorldHint = false))
    public RuleSearchResult searchRules(
            @McpToolParam(description = "Owned game session UUID", required = true) UUID sessionId,
            @McpToolParam(description = "Natural-language rule question", required = true) String query,
            @McpToolParam(description = "Maximum evidence hits from 1 to 20", required = true) int limit) {
        SessionContext context = ownedSession(sessionId);
        var hits = search.search(
                context.documentVersionId(), query, new RetrievalOptions(limit, null, context.phase()));
        return new RuleSearchResult(
                context.sessionId(),
                context.documentVersionId(),
                hits.stream().map(hit -> new RuleHit(
                        hit.evidence().chunkId(),
                        hit.evidence().sectionType(),
                        hit.evidence().heading(),
                        hit.evidence().excerpt(),
                        hit.evidence().pageFrom(),
                        hit.evidence().pageTo(),
                        hit.score())).toList());
    }

    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "get_rule_page",
            description = "Get one rulebook page from a game session owned by the authenticated user.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Get rulebook page",
                    readOnlyHint = true,
                    destructiveHint = false,
                    openWorldHint = false))
    public RulePageResult getRulePage(
            @McpToolParam(description = "Owned game session UUID", required = true) UUID sessionId,
            @McpToolParam(description = "One-based rulebook page number", required = true) int pageNumber) {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("page number must be positive");
        }
        SessionContext context = ownedSession(sessionId);
        var page = documents.pages(context.documentVersionId()).stream()
                .filter(candidate -> candidate.pageNumber() == pageNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("rule page does not exist"));
        return new RulePageResult(
                context.sessionId(), context.documentVersionId(), page.pageNumber(), page.text(), page.characterCount());
    }

    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "get_confirmed_ruling",
            description = "Get an existing confirmed ruling for an owned game session and exact question.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Get confirmed ruling",
                    readOnlyHint = true,
                    destructiveHint = false,
                    openWorldHint = false))
    public ConfirmedRulingResult getConfirmedRuling(
            @McpToolParam(description = "Owned game session UUID", required = true) UUID sessionId,
            @McpToolParam(description = "Question used when the ruling was confirmed", required = true) String question) {
        SessionContext context = ownedSession(sessionId);
        var ruling = rulings.find(
                        context.documentVersionId(), context.expansionIds(), question, username())
                .orElseThrow(() -> new IllegalArgumentException("confirmed ruling does not exist"));
        return new ConfirmedRulingResult(
                context.sessionId(),
                ruling.rulingId(),
                ruling.documentVersionId(),
                ruling.shortVerdict(),
                ruling.explanation(),
                ruling.citations().stream().map(citation -> new RulingCitation(
                        citation.chunkId(), citation.sectionType(), citation.heading(), citation.excerpt(),
                        citation.pageFrom(), citation.pageTo())).toList(),
                ruling.exceptions(),
                ruling.confidence(),
                ruling.official(),
                ruling.version());
    }

    @PreAuthorize("isAuthenticated()")
    @McpTool(
            name = "get_session_context",
            description = "Get the current state of a game session owned by the authenticated user.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Get game session context",
                    readOnlyHint = true,
                    destructiveHint = false,
                    openWorldHint = false))
    public SessionContextResult getSessionContext(
            @McpToolParam(description = "Owned game session UUID", required = true) UUID sessionId) {
        SessionContext context = ownedSession(sessionId);
        return new SessionContextResult(
                context.sessionId(),
                context.editionId(),
                context.documentVersionId(),
                context.expansionIds().stream().sorted().toList(),
                context.playerCount(),
                context.roundNumber(),
                context.phase(),
                context.activePlayer() != null,
                context.activePlayer() == null ? 0 : context.activePlayer(),
                context.status());
    }

    private SessionContext ownedSession(UUID sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("game session is required");
        }
        return sessions.findOwned(sessionId, username())
                .orElseThrow(() -> new IllegalArgumentException("game session does not exist"));
    }

    private String username() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("MCP authentication is required");
        }
        return authentication.getName();
    }

    public record RuleSearchResult(UUID sessionId, UUID documentVersionId, List<RuleHit> hits) {
        public RuleSearchResult {
            hits = List.copyOf(hits);
        }
    }

    public record RuleHit(
            UUID chunkId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo,
            double score) {}

    public record RulePageResult(
            UUID sessionId,
            UUID documentVersionId,
            int pageNumber,
            String text,
            int characterCount) {}

    public record SessionContextResult(
            UUID sessionId,
            UUID editionId,
            UUID documentVersionId,
            List<UUID> expansionIds,
            int playerCount,
            int roundNumber,
            String phase,
            boolean activePlayerAssigned,
            int activePlayer,
            String status) {
        public SessionContextResult {
            expansionIds = List.copyOf(expansionIds);
        }
    }

    public record ConfirmedRulingResult(
            UUID sessionId,
            UUID rulingId,
            UUID documentVersionId,
            String shortVerdict,
            String explanation,
            List<RulingCitation> citations,
            List<String> exceptions,
            String confidence,
            boolean official,
            long version) {
        public ConfirmedRulingResult {
            citations = List.copyOf(citations);
            exceptions = List.copyOf(exceptions);
        }
    }

    public record RulingCitation(
            UUID chunkId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo) {}
}
