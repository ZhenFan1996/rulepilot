package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidencePage;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SourceAvailability;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SearchRuleEvidenceNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string", "description": "One unresolved rule obligation using the player's distinctive wording plus the relevant active-rulebook terms; do not bundle unrelated questions.", "minLength": 1},
                "limit": {"type": "integer", "description": "The candidate count needed to evaluate this obligation before the run deadline; request only evidence you can inspect.", "minimum": 1},
                "sectionTypes": {
                  "type": "array",
                  "description": "Optional application-known section type filters. Use an empty array when the plan supplied no reliable section scope.",
                  "items": {"type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_]{0,39}$"},
                  "uniqueItems": true
                },
                "currentSectionType": {
                  "description": "Optional current lesson section for a local follow-up; null when there is no section-bound context.",
                  "type": ["string", "null"],
                  "pattern": "^[A-Za-z][A-Za-z0-9_]{0,39}$"
                },
                "includeAdjacentContext": {"type": "boolean", "description": "Request bounded neighboring chunks only when a condition, exception, or list continuation may cross a chunk boundary."},
                "cursor": {"description": "Opaque nextCursor from the preceding observation for this exact search, or null for the first page.", "type": ["string", "null"], "format": "uuid"}
              },
              "required": ["query", "limit"],
              "additionalProperties": true
            }
            """;

    private final AssistantReadTools readTools;
    private final ObjectMapper objectMapper;
    private final NativeEvidenceCursorStore cursors;

    @Autowired
    public SearchRuleEvidenceNativeTool(
            AssistantReadTools readTools, ObjectMapper objectMapper, NativeEvidenceCursorStore cursors) {
        this.readTools = readTools;
        this.objectMapper = objectMapper;
        this.cursors = cursors;
    }

    public SearchRuleEvidenceNativeTool(AssistantReadTools readTools, ObjectMapper objectMapper) {
        this(readTools, objectMapper, new NativeEvidenceCursorStore());
    }

    @Override
    public String name() {
        return "search_rule_evidence";
    }

    @Override
    public String description() {
        return "Use when supplied evidence misses one accepted obligation. Search the active immutable rulebook for "
                + "one condition, exception, list item, example, or advice need per call; do not retry a failed query "
                + "with superficial synonyms. Results are candidate excerpts and locators, not a ruling or proof of "
                + "applicability. Read useful exact pages before deciding. Stop when the obligation is covered or no "
                + "independent query remains.";
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
        SearchArguments arguments = parse(argumentsJson);
        if (arguments.query() == null || arguments.query().isBlank()) {
            throw new IllegalArgumentException("query must be a non-blank string");
        }
        if (arguments.limit() == null || arguments.limit() < 1) {
            throw new IllegalArgumentException("limit must be a positive requested candidate count");
        }
        if (arguments.sectionTypes().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("sectionTypes must not contain null identities");
        }
        Set<String> sectionTypes = new LinkedHashSet<>(arguments.sectionTypes());
        if (sectionTypes.size() != arguments.sectionTypes().size()) {
            throw new IllegalArgumentException("search section filters contain duplicates");
        }
        if (arguments.includeAdjacentContext()
                && sectionTypes.size() > RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY) {
            return ToolObservation.partial("DB_PARAMETER_RANGE", Map.of(
                    "evidence", List.of(),
                    "hasMore", false,
                    "nextCursor", "",
                    "allowedRange", Map.of(
                            "sectionTypes", Map.of(
                                    "minimum", 0,
                                    "maximum", RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY))), 0);
        }
        String fingerprint = NativeEvidenceRequestFingerprint.of(String.join("\n",
                arguments.query().strip(),
                Integer.toString(arguments.limit()),
                sectionTypes.stream().sorted().collect(java.util.stream.Collectors.joining(",")),
                arguments.currentSectionType() == null ? "" : arguments.currentSectionType().strip(),
                arguments.includeAdjacentContext().toString()));
        NativeEvidenceCursorStore.Position position = cursors.open(
                scope, name(), fingerprint, arguments.cursor(), NativeEvidenceCursorStore.Position.initial());
        int remainingRequested = Math.max(0, arguments.limit() - position.primaryOffset());
        int candidateWindow = NativeEvidenceObservationBudget.candidateWindow(
                objectMapper, scope.maxObservationTokens());
        if (remainingRequested == 0) {
            cursors.close(scope, name(), fingerprint, arguments.cursor());
            return resultObservation(
                    List.of(), false, "", arguments.limit(), position.primaryOffset(), false,
                    SourceAvailability.COMPLETE);
        }
        if (candidateWindow == 0) {
            return resultObservation(
                    List.of(), true, arguments.cursor() == null ? "" : arguments.cursor(),
                    arguments.limit(), position.primaryOffset(), true, SourceAvailability.COMPLETE);
        }
        // Adjacent context is one atomic anchor group: advancing its anchor cursor while dropping one neighbor would
        // make that neighbor unreachable. Direct searches can safely pack a prefix of their one-to-one candidates.
        int anchorWindow = arguments.includeAdjacentContext()
                ? Math.max(1, candidateWindow / 3)
                : Math.min(candidateWindow, remainingRequested);
        int pageSize = arguments.includeAdjacentContext()
                ? 3
                : anchorWindow;
        RuleEvidencePage page = readTools.searchRuleEvidencePage(new SearchRuleEvidence(
                scope.documentVersionId(),
                arguments.query(),
                arguments.limit(),
                Set.copyOf(sectionTypes),
                arguments.currentSectionType(),
                arguments.includeAdjacentContext(),
                false), position.primaryOffset(), pageSize, Set.copyOf(position.resolvedIds()));
        if (page == null && position.primaryOffset() == 0) {
            List<RuleEvidence> legacyEvidence = readTools.searchRuleEvidence(new SearchRuleEvidence(
                    scope.documentVersionId(), arguments.query(), anchorWindow,
                    Set.copyOf(sectionTypes), arguments.currentSectionType(),
                    arguments.includeAdjacentContext(), false));
            page = new RuleEvidencePage(
                    legacyEvidence,
                    arguments.limit() > anchorWindow && legacyEvidence.size() >= anchorWindow,
                    Math.min(legacyEvidence.size(), anchorWindow));
        }
        if (page == null) throw new IllegalStateException("assistant read tools returned no search page");
        List<RuleEvidence> evidence = page.evidence();
        int retained = fittingPrefix(
                evidence, arguments.includeAdjacentContext(), page, position,
                arguments.limit(), scope.maxObservationTokens());
        boolean budgetBlocked = !evidence.isEmpty() && retained == 0;
        List<RuleEvidence> retainedEvidence = evidence.subList(0, retained);
        int nextOffset = budgetBlocked || retained < evidence.size()
                ? Math.addExact(position.primaryOffset(), retained)
                : Math.addExact(position.primaryOffset(), page.consumedIdentities());
        boolean hasMore = budgetBlocked
                || retained < evidence.size()
                || (page.hasMore() && nextOffset < arguments.limit());
        List<java.util.UUID> seenIds = new java.util.ArrayList<>(position.resolvedIds());
        retainedEvidence.stream()
                .map(RuleEvidence::chunkId)
                .filter(id -> !seenIds.contains(id))
                .forEach(seenIds::add);
        String nextCursor = null;
        if (hasMore) {
            ToolObservation provisional = resultObservation(
                    retainedEvidence, true, NativeEvidenceObservationBudget.PROVISIONAL_CURSOR,
                    arguments.limit(), nextOffset, budgetBlocked, page.sourceAvailability());
            if (NativeEvidenceObservationBudget.fits(
                    objectMapper, provisional, scope.maxObservationTokens())) {
                nextCursor = budgetBlocked && arguments.cursor() != null
                        ? arguments.cursor()
                        : cursors.continueFrom(
                                scope, name(), fingerprint, arguments.cursor(),
                                new NativeEvidenceCursorStore.Position(nextOffset, 0, 0, seenIds));
            }
        }
        if (!hasMore) {
            cursors.close(scope, name(), fingerprint, arguments.cursor());
        }
        return resultObservation(
                retainedEvidence, hasMore, nextCursor == null ? "" : nextCursor,
                arguments.limit(), nextOffset, budgetBlocked, page.sourceAvailability());
    }

    private int fittingPrefix(
            List<RuleEvidence> evidence,
            boolean atomicAdjacentGroup,
            RuleEvidencePage page,
            NativeEvidenceCursorStore.Position position,
            int requestedLimit,
            int maxObservationTokens) {
        if (evidence.isEmpty()) return 0;
        if (atomicAdjacentGroup) {
            int nextOffset = Math.addExact(position.primaryOffset(), page.consumedIdentities());
            boolean hasMore = page.hasMore() && nextOffset < requestedLimit;
            ToolObservation candidate = resultObservation(
                    evidence, hasMore,
                    hasMore ? NativeEvidenceObservationBudget.PROVISIONAL_CURSOR : "",
                    requestedLimit, nextOffset, false, page.sourceAvailability());
            return NativeEvidenceObservationBudget.fits(objectMapper, candidate, maxObservationTokens)
                    ? evidence.size()
                    : 0;
        }
        int retained = 0;
        for (int count = 1; count <= evidence.size(); count++) {
            int nextOffset = count < evidence.size()
                    ? Math.addExact(position.primaryOffset(), count)
                    : Math.addExact(position.primaryOffset(), page.consumedIdentities());
            boolean hasMore = count < evidence.size()
                    || (page.hasMore() && nextOffset < requestedLimit);
            ToolObservation candidate = resultObservation(
                    evidence.subList(0, count), hasMore,
                    hasMore ? NativeEvidenceObservationBudget.PROVISIONAL_CURSOR : "",
                    requestedLimit, nextOffset, false, page.sourceAvailability());
            if (NativeEvidenceObservationBudget.fits(objectMapper, candidate, maxObservationTokens)) {
                retained = count;
            }
        }
        return retained;
    }

    private ToolObservation resultObservation(
            List<RuleEvidence> evidence,
            boolean hasMore,
            String nextCursor,
            int requestedLimit,
            int returnedThrough,
            boolean budgetBlocked,
            SourceAvailability sourceAvailability) {
        List<Map<String, Object>> observations = evidence.stream().map(this::observation).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evidence", observations);
        data.put("hasMore", hasMore);
        data.put("nextCursor", nextCursor);
        data.put("requestedLimit", requestedLimit);
        data.put("returnedThrough", returnedThrough);
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
                ? ToolObservation.partial(hasMore ? "SEARCH_PAGE_EMPTY" : "NO_EVIDENCE", data, 0)
                : hasMore
                        ? ToolObservation.partial("EVIDENCE_PAGE_FOUND", data, observations.size())
                        : ToolObservation.success("EVIDENCE_FOUND", data, observations.size());
    }

    private SearchArguments parse(String argumentsJson) {
        try {
            return objectMapper.readerFor(SearchArguments.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("search arguments JSON could not be decoded", exception);
        }
    }

    private Map<String, Object> observation(RuleEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", evidence.chunkId().toString());
        value.put("sectionType", complete(evidence.sectionType()));
        value.put("heading", complete(evidence.heading()));
        value.put("excerpt", complete(evidence.excerpt()));
        value.put("pageFrom", evidence.pageFrom());
        value.put("pageTo", evidence.pageTo());
        return Map.copyOf(value);
    }

    private String complete(String value) {
        return value == null ? "" : value.strip();
    }

    private record SearchArguments(
            String query,
            Integer limit,
            List<String> sectionTypes,
            String currentSectionType,
            Boolean includeAdjacentContext,
            String cursor) {
        private SearchArguments {
            sectionTypes = sectionTypes == null ? List.of() : List.copyOf(sectionTypes);
            includeAdjacentContext = Boolean.TRUE.equals(includeAdjacentContext);
        }
    }
}
