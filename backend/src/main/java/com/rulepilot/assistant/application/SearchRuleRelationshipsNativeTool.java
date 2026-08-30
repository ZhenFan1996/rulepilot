package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidencePage;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SourceAvailability;
import com.rulepilot.assistant.NativeAgentTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Retrieves canonical passages for a model-selected relationship topic without classifying their meaning. */
@Component
@Profile("!test")
public class SearchRuleRelationshipsNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "topic": {"type": "string", "description": "One concrete relationship between the current general rule and its possible exception, replacement, precedence, or condition, using active-document terms.", "minLength": 1},
                "limit": {"type": "integer", "description": "The candidate count needed to compare both sides before the run deadline; request only evidence you can inspect.", "minimum": 1},
                "cursor": {"description": "Opaque nextCursor from the preceding observation for this exact relationship search, or null for the first page.", "type": ["string", "null"], "format": "uuid"}
              },
              "required": ["topic", "limit"],
              "additionalProperties": true
            }
            """;

    private final AssistantReadTools readTools;
    private final ObjectMapper objectMapper;
    private final NativeEvidenceCursorStore cursors;

    @Autowired
    public SearchRuleRelationshipsNativeTool(
            AssistantReadTools readTools, ObjectMapper objectMapper, NativeEvidenceCursorStore cursors) {
        this.readTools = readTools;
        this.objectMapper = objectMapper;
        this.cursors = cursors;
    }

    public SearchRuleRelationshipsNativeTool(AssistantReadTools readTools, ObjectMapper objectMapper) {
        this(readTools, objectMapper, new NativeEvidenceCursorStore());
    }

    @Override
    public String name() {
        return "search_rule_relationships";
    }

    @Override
    public String description() {
        return "Use when a plan needs the relationship between a general rule and a possible exception, replacement, "
                + "conflict, precedence, or condition. Search one topic in the active immutable rulebook; do not use "
                + "it for a direct-rule lookup. Passages are authoritative source text, but cue labels do not decide "
                + "which rule wins or applies. Read exact pages and compare scope. Stop when both sides are covered or "
                + "no independent candidate remains.";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public String schemaVersion() {
        return "4";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ANSWER, Role.TEACHING);
    }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        Arguments arguments = parse(argumentsJson);
        if (arguments.topic() == null || arguments.topic().isBlank()) {
            throw new IllegalArgumentException("topic must be a non-blank rule relationship");
        }
        if (arguments.topic().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("topic must not contain control characters");
        }
        if (arguments.limit() == null || arguments.limit() < 1) {
            throw new IllegalArgumentException("limit must be a positive requested candidate count");
        }
        String fingerprint = NativeEvidenceRequestFingerprint.of(
                arguments.topic().strip() + "\n" + arguments.limit());
        NativeEvidenceCursorStore.Position position = cursors.open(
                scope, name(), fingerprint, arguments.cursor(), NativeEvidenceCursorStore.Position.initial());
        int remainingRequested = Math.max(0, arguments.limit() - position.primaryOffset());
        int candidateWindow = NativeEvidenceObservationBudget.candidateWindow(
                objectMapper, scope.maxObservationTokens());
        if (remainingRequested == 0) {
            cursors.close(scope, name(), fingerprint, arguments.cursor());
            return resultObservation(List.of(), false, "", false, SourceAvailability.COMPLETE);
        }
        if (candidateWindow == 0) {
            return resultObservation(
                    List.of(), true, arguments.cursor() == null ? "" : arguments.cursor(), true,
                    SourceAvailability.COMPLETE);
        }
        int anchorWindow = Math.max(1, candidateWindow / 3);
        // One relationship anchor and its adjacent context form an atomic group. Advancing the anchor while
        // publishing only part of that group would make the omitted governing text unreachable.
        int pageSize = 3;
        SearchRuleEvidence request = new SearchRuleEvidence(
                scope.documentVersionId(), arguments.topic().strip(), arguments.limit(),
                Set.of(), null, true, false);
        RuleEvidencePage page = readTools.searchRuleEvidencePage(
                request, position.primaryOffset(), pageSize, Set.copyOf(position.resolvedIds()));
        if (page == null && position.primaryOffset() == 0) {
            List<RuleEvidence> legacy = readTools.searchRuleEvidence(new SearchRuleEvidence(
                    scope.documentVersionId(), arguments.topic().strip(),
                    Math.min(arguments.limit(), anchorWindow), Set.of(), null, true, false));
            page = new RuleEvidencePage(
                    legacy,
                    arguments.limit() > anchorWindow && legacy.size() >= anchorWindow,
                    Math.min(legacy.size(), anchorWindow));
        }
        if (page == null) throw new IllegalStateException("assistant read tools returned no relationship page");
        List<RuleEvidence> results = page.evidence();
        LinkedHashMap<UUID, RuleEvidence> canonical = new LinkedHashMap<>();
        for (RuleEvidence evidence : results) {
            if (!scope.documentVersionId().equals(evidence.documentVersionId())) {
                throw new IllegalStateException("relationship search escaped document scope");
            }
            canonical.putIfAbsent(evidence.chunkId(), evidence);
        }
        int nextOffset = Math.addExact(position.primaryOffset(), page.consumedIdentities());
        boolean sourceHasMore = page.hasMore() && nextOffset < arguments.limit();
        List<RuleEvidence> group = List.copyOf(canonical.values());
        ToolObservation candidate = resultObservation(
                group, sourceHasMore,
                sourceHasMore ? NativeEvidenceObservationBudget.PROVISIONAL_CURSOR : "", false,
                page.sourceAvailability());
        boolean budgetBlocked = !group.isEmpty()
                && !NativeEvidenceObservationBudget.fits(
                        objectMapper, candidate, scope.maxObservationTokens());
        List<RuleEvidence> retained = budgetBlocked ? List.of() : group;
        if (budgetBlocked) nextOffset = position.primaryOffset();
        boolean hasMore = budgetBlocked || sourceHasMore;
        List<UUID> seen = new java.util.ArrayList<>(position.resolvedIds());
        retained.stream().map(RuleEvidence::chunkId).filter(id -> !seen.contains(id)).forEach(seen::add);
        String nextCursor = null;
        if (hasMore) {
            ToolObservation provisional = resultObservation(
                    retained, true, NativeEvidenceObservationBudget.PROVISIONAL_CURSOR, budgetBlocked,
                    page.sourceAvailability());
            if (NativeEvidenceObservationBudget.fits(
                    objectMapper, provisional, scope.maxObservationTokens())) {
                nextCursor = budgetBlocked && arguments.cursor() != null
                        ? arguments.cursor()
                        : cursors.continueFrom(
                                scope, name(), fingerprint, arguments.cursor(),
                                new NativeEvidenceCursorStore.Position(nextOffset, 0, 0, seen));
            }
        }
        if (!hasMore) {
            cursors.close(scope, name(), fingerprint, arguments.cursor());
        }
        return resultObservation(
                retained, hasMore, nextCursor == null ? "" : nextCursor, budgetBlocked,
                page.sourceAvailability());
    }

    private ToolObservation resultObservation(
            List<RuleEvidence> evidence,
            boolean hasMore,
            String nextCursor,
            boolean budgetBlocked,
            SourceAvailability sourceAvailability) {
        List<Map<String, Object>> observations = evidence.stream().map(this::observation).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evidence", observations);
        data.put("hasMore", hasMore);
        data.put("nextCursor", nextCursor);
        data.put("relationshipClassificationAuthority", false);
        data.put("nextAction", "READ_EXACT_PAGES_AND_COMPARE_APPLICABILITY");
        if (sourceAvailability == SourceAvailability.PARTIAL) {
            data.put("sourceAvailability", sourceAvailability.name());
        }
        if (budgetBlocked) {
            return ToolObservation.partial("OBSERVATION_BUDGET_EXHAUSTED", data, 0);
        }
        if (sourceAvailability == SourceAvailability.PARTIAL) {
            return ToolObservation.partial("RETRIEVAL_SOURCE_PARTIAL", data, observations.size());
        }
        return observations.isEmpty()
                ? ToolObservation.partial(
                        hasMore ? "RELATIONSHIP_PAGE_EMPTY" : "NO_RELATIONSHIP_CANDIDATES", data, 0)
                : hasMore
                        ? ToolObservation.partial("RELATIONSHIP_CANDIDATE_PAGE", data, observations.size())
                        : ToolObservation.success("RELATIONSHIP_CANDIDATES_FOUND", data, observations.size());
    }

    private Map<String, Object> observation(RuleEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", evidence.chunkId().toString());
        value.put("sectionType", complete(evidence.sectionType()));
        value.put("heading", complete(evidence.heading()));
        value.put("excerpt", complete(evidence.excerpt()));
        value.put("pageFrom", evidence.pageFrom());
        value.put("pageTo", evidence.pageTo());
        value.put("classificationAuthority", false);
        value.put("evidenceMechanicalAuthority", true);
        return Map.copyOf(value);
    }

    private Arguments parse(String argumentsJson) {
        try {
            return objectMapper.readerFor(Arguments.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "rule relationship search arguments JSON could not be decoded", exception);
        }
    }

    private String complete(String value) {
        return value == null ? "" : value.strip();
    }

    private record Arguments(String topic, Integer limit, String cursor) {}
}
