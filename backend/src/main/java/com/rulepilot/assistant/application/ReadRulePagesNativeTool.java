package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidencePage;
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
public class ReadRulePagesNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "pageNumbers": {
                  "type": "array",
                  "description": "Exact active-rulebook pages selected from a prior citation or a search observation; never guessed page numbers.",
                  "items": {"type": "integer", "minimum": 1},
                  "minItems": 1,
                  "uniqueItems": true
                },
                "cursor": {"description": "Opaque nextCursor from the preceding observation for this exact page set, or null for the first page.", "type": ["string", "null"], "format": "uuid"}
              },
              "required": ["pageNumbers"],
              "additionalProperties": false
            }
            """;

    private final AssistantReadTools readTools;
    private final ObjectMapper objectMapper;
    private final NativeEvidenceCursorStore cursors;

    @Autowired
    public ReadRulePagesNativeTool(
            AssistantReadTools readTools, ObjectMapper objectMapper, NativeEvidenceCursorStore cursors) {
        this.readTools = readTools;
        this.objectMapper = objectMapper;
        this.cursors = cursors;
    }

    public ReadRulePagesNativeTool(AssistantReadTools readTools, ObjectMapper objectMapper) {
        this(readTools, objectMapper, new NativeEvidenceCursorStore());
    }

    @Override
    public String name() {
        return "read_rule_pages";
    }

    @Override
    public String description() {
        return "Use after a grounded citation or search has identified exact pages. Read canonical text from those "
                + "pages of the active immutable rulebook; never invent pages or use this as broad search. The "
                + "passages are authoritative source text, but their subject, conditions, exceptions, and current "
                + "applicability still require checking. For a prior-turn reference, read the supplied pages once. "
                + "A large exact set is returned in typed pages; follow nextCursor only while more of that same set "
                + "is needed. Stop after the useful exact pages are read.";
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
        ReadArguments arguments = parse(argumentsJson);
        if (arguments.pageNumbers() == null || arguments.pageNumbers().isEmpty()) {
            throw new IllegalArgumentException("pageNumbers must contain at least one exact active-rulebook page");
        }
        if (arguments.pageNumbers().stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("every pageNumber must be a positive integer");
        }
        Set<Integer> pages = new LinkedHashSet<>(arguments.pageNumbers());
        if (pages.size() != arguments.pageNumbers().size()) {
            throw new IllegalArgumentException("page read arguments contain duplicates");
        }
        List<Integer> orderedPages = pages.stream().sorted().toList();
        String fingerprint = NativeEvidenceRequestFingerprint.of(orderedPages.stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        NativeEvidenceCursorStore.Position position = cursors.open(
                scope, name(), fingerprint, arguments.cursor(), NativeEvidenceCursorStore.Position.initial());
        int candidateWindow = NativeEvidenceObservationBudget.candidateWindow(
                objectMapper, scope.maxObservationTokens());
        if (candidateWindow == 0) {
            return resultObservation(
                    List.of(), List.of(), true,
                    arguments.cursor() == null ? "" : arguments.cursor(), orderedPages.size(), true);
        }
        int remainingPages = orderedPages.size() - position.primaryOffset();
        int pageIdentityCount = position.identityCount() > 0
                ? Math.min(position.identityCount(), remainingPages)
                : Math.min(
                        Math.min(candidateWindow, RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY),
                        remainingPages);
        if (pageIdentityCount < 1) {
            if (remainingPages < 1) {
                cursors.close(scope, name(), fingerprint, arguments.cursor());
                return resultObservation(
                        List.of(), List.of(), false, "", orderedPages.size(), false);
            }
            return resultObservation(
                    List.of(), List.of(), true,
                    arguments.cursor() == null ? "" : arguments.cursor(), orderedPages.size(), true);
        }
        List<Integer> pageBatch = orderedPages.subList(
                position.primaryOffset(), position.primaryOffset() + pageIdentityCount);
        ToolObservation fixedEnvelope = resultObservation(
                List.of(), pageBatch, true, NativeEvidenceObservationBudget.PROVISIONAL_CURSOR,
                orderedPages.size(), false);
        if (!NativeEvidenceObservationBudget.fits(
                objectMapper, fixedEnvelope, scope.maxObservationTokens())) {
            return resultObservation(
                    List.of(), List.of(), true,
                    arguments.cursor() == null ? "" : arguments.cursor(), orderedPages.size(), true);
        }
        Set<Integer> queryPages = new LinkedHashSet<>(pageBatch);
        RuleEvidencePage page = readTools.readRuleEvidencePagesPage(
                scope.documentVersionId(), queryPages, false, position.secondaryOffset(), candidateWindow);
        if (page == null && position.secondaryOffset() == 0) {
            List<RuleEvidence> legacyEvidence = readTools.readRuleEvidencePages(
                    scope.documentVersionId(), queryPages, false);
            page = new RuleEvidencePage(legacyEvidence, false);
        }
        if (page == null) throw new IllegalStateException("assistant read tools returned no exact-page window");
        List<RuleEvidence> evidence = page.evidence();
        int retained = fittingEvidencePrefix(
                evidence, page, pageBatch, position, pageIdentityCount,
                orderedPages.size(), scope.maxObservationTokens());
        boolean budgetBlocked = !evidence.isEmpty() && retained == 0;
        List<RuleEvidence> retainedEvidence = evidence.subList(0, retained);
        boolean truncated = retained < evidence.size();
        boolean moreInBatch = budgetBlocked || truncated || page.hasMore();
        int nextPageOffset = moreInBatch
                ? position.primaryOffset()
                : position.primaryOffset() + pageIdentityCount;
        int consumedRows = budgetBlocked
                ? 0
                : truncated ? retained : page.consumedIdentities();
        int nextRowOffset = moreInBatch
                ? Math.addExact(position.secondaryOffset(), consumedRows)
                : 0;
        boolean hasMore = moreInBatch || nextPageOffset < orderedPages.size();
        String nextCursor = null;
        if (hasMore) {
            ToolObservation provisional = resultObservation(
                    retainedEvidence, pageBatch, true, NativeEvidenceObservationBudget.PROVISIONAL_CURSOR,
                    orderedPages.size(), budgetBlocked);
            if (NativeEvidenceObservationBudget.fits(
                    objectMapper, provisional, scope.maxObservationTokens())) {
                nextCursor = budgetBlocked && arguments.cursor() != null
                        ? arguments.cursor()
                        : cursors.continueFrom(
                                scope, name(), fingerprint, arguments.cursor(),
                                new NativeEvidenceCursorStore.Position(
                                        nextPageOffset, nextRowOffset,
                                        moreInBatch ? pageIdentityCount : 0, List.of()));
            }
        }
        if (!hasMore) {
            cursors.close(scope, name(), fingerprint, arguments.cursor());
        }
        return resultObservation(
                retainedEvidence, pageBatch, hasMore, nextCursor == null ? "" : nextCursor,
                orderedPages.size(), budgetBlocked);
    }

    private int fittingEvidencePrefix(
            List<RuleEvidence> evidence,
            RuleEvidencePage page,
            List<Integer> pageBatch,
            NativeEvidenceCursorStore.Position position,
            int pageIdentityCount,
            int requestedPageCount,
            int maxObservationTokens) {
        int retained = 0;
        for (int count = 1; count <= evidence.size(); count++) {
            boolean truncated = count < evidence.size();
            boolean moreInBatch = truncated || page.hasMore();
            int nextPageOffset = moreInBatch
                    ? position.primaryOffset()
                    : position.primaryOffset() + pageIdentityCount;
            boolean hasMore = moreInBatch || nextPageOffset < requestedPageCount;
            ToolObservation candidate = resultObservation(
                    evidence.subList(0, count), pageBatch, hasMore,
                    hasMore ? NativeEvidenceObservationBudget.PROVISIONAL_CURSOR : "",
                    requestedPageCount, false);
            if (NativeEvidenceObservationBudget.fits(objectMapper, candidate, maxObservationTokens)) {
                retained = count;
            }
        }
        return retained;
    }

    private ToolObservation resultObservation(
            List<RuleEvidence> evidence,
            List<Integer> pageBatch,
            boolean hasMore,
            String nextCursor,
            int requestedPageCount,
            boolean budgetBlocked) {
        List<Map<String, Object>> observations = evidence.stream().map(this::observation).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestedPageCount", requestedPageCount);
        data.put("pageBatch", List.copyOf(pageBatch));
        data.put("evidence", observations);
        data.put("hasMore", hasMore);
        data.put("nextCursor", nextCursor);
        if (budgetBlocked) {
            return ToolObservation.partial("OBSERVATION_BUDGET_EXHAUSTED", data, 0);
        }
        return observations.isEmpty()
                ? ToolObservation.partial("NO_PAGE_EVIDENCE", data, 0)
                : hasMore
                        ? ToolObservation.partial("PAGE_EVIDENCE_PAGE_FOUND", data, observations.size())
                        : ToolObservation.success("PAGE_EVIDENCE_FOUND", data, observations.size());
    }

    private ReadArguments parse(String argumentsJson) {
        try {
            return objectMapper.readerFor(ReadArguments.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("page read arguments JSON could not be decoded", exception);
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

    private record ReadArguments(List<Integer> pageNumbers, String cursor) {}
}
