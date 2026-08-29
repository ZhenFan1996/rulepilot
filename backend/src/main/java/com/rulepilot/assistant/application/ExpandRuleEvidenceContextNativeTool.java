package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContext;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContextPage;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Expands already located evidence without turning neighboring prose into an automatic ruling. */
@Component
@Profile("!test")
public class ExpandRuleEvidenceContextNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "evidenceIds": {
                  "type": "array",
                  "description": "Existing active-rulebook evidence handles whose immediately neighboring chunks may contain a governing condition, continuation, or exception.",
                  "items": {"type": "string", "format": "uuid"},
                  "minItems": 1,
                  "uniqueItems": true
                },
                "radius": {"type": "integer", "description": "Canonical chunks on each side; use the smallest positive radius that reaches the missing boundary.", "minimum": 1},
                "cursor": {"description": "Opaque nextCursor from the preceding observation for these exact handles and radius, or null for the first page.", "type": ["string", "null"], "format": "uuid"}
              },
              "required": ["evidenceIds", "radius"],
              "additionalProperties": false
            }
            """;

    private final AssistantReadTools readTools;
    private final ObjectMapper objectMapper;
    private final NativeEvidenceCursorStore cursors;

    @Autowired
    public ExpandRuleEvidenceContextNativeTool(
            AssistantReadTools readTools, ObjectMapper objectMapper, NativeEvidenceCursorStore cursors) {
        this.readTools = readTools;
        this.objectMapper = objectMapper;
        this.cursors = cursors;
    }

    public ExpandRuleEvidenceContextNativeTool(AssistantReadTools readTools, ObjectMapper objectMapper) {
        this(readTools, objectMapper, new NativeEvidenceCursorStore());
    }

    @Override
    public String name() {
        return "expand_rule_evidence_context";
    }

    @Override
    public String description() {
        return "Use after search when an excerpt may omit an adjacent condition, list continuation, or exception. "
                + "Expand the relevant handles by the smallest useful radius; never use it first or to roam the active immutable "
                + "rulebook. Neighboring text is candidate context, not an automatic ruling. Read the resulting exact "
                + "pages before deciding, and stop once the missing boundary is visible.";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public String schemaVersion() {
        return "3";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ANSWER, Role.TEACHING);
    }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        Arguments arguments = parse(argumentsJson);
        if (arguments.evidenceIds() == null || arguments.evidenceIds().isEmpty()) {
            throw new IllegalArgumentException("evidenceIds must contain at least one active-rulebook handle");
        }
        if (arguments.radius() == null || arguments.radius() < 1) {
            throw new IllegalArgumentException("radius must be a positive canonical chunk distance");
        }
        LinkedHashSet<UUID> evidenceIds = new LinkedHashSet<>();
        for (String value : arguments.evidenceIds()) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("each evidenceId must be a non-blank UUID");
            }
            try {
                evidenceIds.add(UUID.fromString(value));
            } catch (IllegalArgumentException invalidId) {
                throw new IllegalArgumentException("each evidenceId must be a UUID", invalidId);
            }
        }
        if (evidenceIds.size() != arguments.evidenceIds().size()) {
            throw new IllegalArgumentException("rule evidence context arguments contain duplicates");
        }

        List<UUID> orderedEvidenceIds = evidenceIds.stream().sorted().toList();
        String fingerprint = NativeEvidenceRequestFingerprint.of(
                orderedEvidenceIds.stream().map(UUID::toString)
                        .collect(java.util.stream.Collectors.joining(",")) + "\n" + arguments.radius());
        NativeEvidenceCursorStore.Position position = cursors.open(
                scope, name(), fingerprint, arguments.cursor(), NativeEvidenceCursorStore.Position.initial());
        int candidateWindow = NativeEvidenceObservationBudget.candidateWindow(
                objectMapper, scope.maxObservationTokens());
        if (candidateWindow == 0) {
            return resultObservation(
                    List.of(), List.of(), true,
                    arguments.cursor() == null ? "" : arguments.cursor(),
                    evidenceIds.size(), 0, true);
        }
        int identityCount;
        List<UUID> selectedIds;
        if (position.identityCount() > 0) {
            identityCount = position.identityCount();
            selectedIds = position.resolvedIds();
        } else {
            identityCount = Math.min(
                    Math.min(candidateWindow, RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY),
                    orderedEvidenceIds.size() - position.primaryOffset());
            if (identityCount < 1) {
                cursors.close(scope, name(), fingerprint, arguments.cursor());
                return resultObservation(
                        List.of(), List.of(), false, "", evidenceIds.size(), 0, false);
            }
            selectedIds = orderedEvidenceIds.subList(
                    position.primaryOffset(), position.primaryOffset() + identityCount);
        }
        ToolObservation fixedEnvelope = resultObservation(
                List.of(), List.of(), true, NativeEvidenceObservationBudget.PROVISIONAL_CURSOR,
                evidenceIds.size(), identityCount, false);
        if (!NativeEvidenceObservationBudget.fits(
                objectMapper, fixedEnvelope, scope.maxObservationTokens())) {
            return resultObservation(
                    List.of(), List.of(), true,
                    arguments.cursor() == null ? "" : arguments.cursor(),
                    evidenceIds.size(), identityCount, true);
        }
        RuleEvidenceContextPage context = readTools.readRuleEvidenceContextPage(
                scope.documentVersionId(), Set.copyOf(selectedIds), arguments.radius(),
                position.secondaryOffset(), candidateWindow);
        if (context == null && position.secondaryOffset() == 0) {
            RuleEvidenceContext legacy = readTools.readRuleEvidenceContext(
                    scope.documentVersionId(), Set.copyOf(selectedIds), arguments.radius());
            if (legacy == null) {
                throw new IllegalStateException("assistant read tools returned no context window");
            }
            context = new RuleEvidenceContextPage(
                    legacy.anchors(), legacy.surroundingEvidence(), false);
        }
        if (context == null) throw new IllegalStateException("assistant read tools returned no context window");
        int retained = fittingEvidencePrefix(
                context, position, identityCount, orderedEvidenceIds.size(),
                evidenceIds.size(), scope.maxObservationTokens());
        int available = context.anchors().size() + context.surroundingEvidence().size();
        boolean budgetBlocked = available > 0 && retained == 0;
        int retainedAnchorCount = Math.min(retained, context.anchors().size());
        int retainedSurroundingCount = Math.max(0, retained - retainedAnchorCount);
        List<RuleEvidence> anchors = context.anchors().subList(0, retainedAnchorCount);
        List<RuleEvidence> surrounding = context.surroundingEvidence().subList(0, retainedSurroundingCount);
        boolean truncated = retained < available;
        boolean moreInBatch = budgetBlocked || truncated || context.hasMore();
        int nextAnchorOffset = moreInBatch
                ? position.primaryOffset()
                : position.primaryOffset() + identityCount;
        int nextContextOffset = moreInBatch
                ? Math.addExact(position.secondaryOffset(), budgetBlocked ? 0 : retained)
                : 0;
        boolean hasMore = moreInBatch || nextAnchorOffset < orderedEvidenceIds.size();
        String nextCursor = null;
        if (hasMore) {
            ToolObservation provisional = resultObservation(
                    anchors, surrounding, true, NativeEvidenceObservationBudget.PROVISIONAL_CURSOR,
                    evidenceIds.size(), identityCount, budgetBlocked);
            if (NativeEvidenceObservationBudget.fits(
                    objectMapper, provisional, scope.maxObservationTokens())) {
                nextCursor = budgetBlocked && arguments.cursor() != null
                        ? arguments.cursor()
                        : cursors.continueFrom(
                                scope, name(), fingerprint, arguments.cursor(),
                                new NativeEvidenceCursorStore.Position(
                                        nextAnchorOffset,
                                        nextContextOffset,
                                        moreInBatch ? identityCount : 0,
                                        moreInBatch ? selectedIds : List.of()));
            }
        }
        if (!hasMore) {
            cursors.close(scope, name(), fingerprint, arguments.cursor());
        }
        return resultObservation(
                anchors, surrounding, hasMore, nextCursor == null ? "" : nextCursor,
                evidenceIds.size(), identityCount, budgetBlocked);
    }

    private int fittingEvidencePrefix(
            RuleEvidenceContextPage context,
            NativeEvidenceCursorStore.Position position,
            int identityCount,
            int orderedAnchorCount,
            int requestedAnchorCount,
            int maxObservationTokens) {
        int available = context.anchors().size() + context.surroundingEvidence().size();
        int retained = 0;
        for (int count = 1; count <= available; count++) {
            int anchorCount = Math.min(count, context.anchors().size());
            int surroundingCount = Math.max(0, count - anchorCount);
            boolean moreInBatch = count < available || context.hasMore();
            int nextAnchorOffset = moreInBatch
                    ? position.primaryOffset()
                    : position.primaryOffset() + identityCount;
            boolean hasMore = moreInBatch || nextAnchorOffset < orderedAnchorCount;
            ToolObservation candidate = resultObservation(
                    context.anchors().subList(0, anchorCount),
                    context.surroundingEvidence().subList(0, surroundingCount),
                    hasMore,
                    hasMore ? NativeEvidenceObservationBudget.PROVISIONAL_CURSOR : "",
                    requestedAnchorCount,
                    identityCount,
                    false);
            if (NativeEvidenceObservationBudget.fits(objectMapper, candidate, maxObservationTokens)) {
                retained = count;
            }
        }
        return retained;
    }

    private ToolObservation resultObservation(
            List<RuleEvidence> anchorEvidence,
            List<RuleEvidence> surroundingEvidence,
            boolean hasMore,
            String nextCursor,
            int requestedAnchorCount,
            int anchorBatchCount,
            boolean budgetBlocked) {
        List<Map<String, Object>> anchors = anchorEvidence.stream().map(this::observation).toList();
        List<Map<String, Object>> surrounding = surroundingEvidence.stream().map(this::observation).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestedAnchorCount", requestedAnchorCount);
        data.put("anchorBatchCount", anchorBatchCount);
        data.put("returnedAnchorCount", anchors.size());
        data.put("anchors", anchors);
        data.put("surroundingEvidence", surrounding);
        data.put("hasMore", hasMore);
        data.put("nextCursor", nextCursor);
        data.put("contextApplicabilityAuthority", false);
        data.put("nextAction", "READ_EXACT_PAGES_AND_CHECK_APPLICABILITY");
        int evidenceCount = anchors.size() + surrounding.size();
        if (budgetBlocked) {
            return ToolObservation.partial("OBSERVATION_BUDGET_EXHAUSTED", data, 0);
        }
        if (anchors.isEmpty()) {
            return surrounding.isEmpty()
                    ? ToolObservation.partial("NO_CONTEXT_ANCHOR", data, 0)
                    : ToolObservation.partial("EVIDENCE_CONTEXT_PAGE", data, evidenceCount);
        }
        return !hasMore
                ? ToolObservation.success("EVIDENCE_CONTEXT_EXPANDED", data, evidenceCount)
                : ToolObservation.partial("EVIDENCE_CONTEXT_PARTIAL", data, evidenceCount);
    }

    private Arguments parse(String argumentsJson) {
        try {
            return objectMapper.readerFor(Arguments.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "rule evidence context arguments JSON could not be decoded", exception);
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

    private record Arguments(List<String> evidenceIds, Integer radius, String cursor) {}
}
