package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContextPage;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidencePage;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeEvidencePagingTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void searchPacksEveryCandidateThatFitsItsMeasuredObservationEnvelope() throws Exception {
        UUID versionId = UUID.randomUUID();
        List<Integer> offsets = new ArrayList<>();
        List<Integer> limits = new ArrayList<>();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                throw new AssertionError("native paging must not call the unbounded search API");
            }

            @Override
            public RuleEvidencePage searchRuleEvidencePage(SearchRuleEvidence request, int offset, int pageSize) {
                offsets.add(offset);
                limits.add(pageSize);
                int available = Math.max(0, Math.min(pageSize, 5 - offset));
                List<RuleEvidence> evidence = java.util.stream.IntStream.range(0, available)
                        .mapToObj(index -> evidence(versionId, offset + index + 1))
                        .toList();
                return new RuleEvidencePage(evidence, offset + available < 5, available);
            }
        };
        var tool = new SearchRuleEvidenceNativeTool(reads, json, new NativeEvidenceCursorStore());
        ToolScope scope = scope(versionId, UUID.randomUUID(), 1_600);
        String cursor = null;
        int observed = 0;
        ToolObservation result;
        do {
            result = tool.execute(searchArguments(Integer.MAX_VALUE, cursor), scope);
            observed += result.evidenceCount();
            cursor = (String) result.data().get("nextCursor");
        } while ((boolean) result.data().get("hasMore"));

        assertThat(observed).isEqualTo(5);
        assertThat(offsets).containsExactly(0, 2, 4);
        assertThat(limits).containsExactly(2, 2, 2);
        assertThat(result.code()).isEqualTo("EVIDENCE_FOUND");
        assertThat(observationTokens(result)).isLessThanOrEqualTo(1_600);
    }

    @Test
    void continuationStopsBeforeQueryWhenTheRemainingObservationEnvelopeCannotHoldEvidence() throws Exception {
        UUID versionId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                throw new AssertionError("unbounded search is forbidden");
            }

            @Override
            public RuleEvidencePage searchRuleEvidencePage(SearchRuleEvidence request, int offset, int pageSize) {
                calls.incrementAndGet();
                return new RuleEvidencePage(List.of(evidence(versionId, offset + 1)), true, 1);
            }
        };
        var tool = new SearchRuleEvidenceNativeTool(reads, json, new NativeEvidenceCursorStore());
        UUID runId = UUID.randomUUID();
        ToolObservation first = tool.execute(searchArguments(20, null), scope(versionId, runId, 1_600));

        ToolObservation stopped = tool.execute(
                searchArguments(20, (String) first.data().get("nextCursor")),
                scope(versionId, runId, 0));

        assertThat(stopped.code()).isEqualTo("OBSERVATION_BUDGET_EXHAUSTED");
        assertThat(stopped.data()).containsEntry("hasMore", true);
        assertThat(calls).hasValue(1);
    }

    @Test
    void escapeHeavySearchKeepsTheFittingPrefixAndContinuesBeforeTheUnfittingCandidate() throws Exception {
        UUID versionId = UUID.randomUUID();
        RuleEvidence firstEvidence = evidence(versionId, 1);
        RuleEvidence escapedEvidence = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "Quoted \\\"heading\\\"",
                "line=\\\"value\\\"\\\\path\\n".repeat(180), 2, 2);
        List<RuleEvidence> available = List.of(firstEvidence, escapedEvidence);
        List<Integer> offsets = new ArrayList<>();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                throw new AssertionError("unbounded search is forbidden");
            }

            @Override
            public RuleEvidencePage searchRuleEvidencePage(SearchRuleEvidence request, int offset, int pageSize) {
                offsets.add(offset);
                List<RuleEvidence> page = available.subList(offset, Math.min(available.size(), offset + pageSize));
                return new RuleEvidencePage(page, offset + page.size() < available.size(), page.size());
            }
        };
        Map<String, Object> firstData = new java.util.LinkedHashMap<>();
        firstData.put("evidence", List.of(evidenceObservation(firstEvidence)));
        firstData.put("hasMore", true);
        firstData.put("nextCursor", NativeEvidenceObservationBudget.PROVISIONAL_CURSOR);
        firstData.put("requestedLimit", 2);
        firstData.put("returnedThrough", 1);
        ToolObservation fittingPrefix = ToolObservation.partial("EVIDENCE_PAGE_FOUND", firstData, 1);
        int envelope = observationTokens(fittingPrefix);
        Map<String, Object> allData = new java.util.LinkedHashMap<>();
        allData.put("evidence", available.stream().map(this::evidenceObservation).toList());
        allData.put("hasMore", false);
        allData.put("nextCursor", "");
        allData.put("requestedLimit", 2);
        allData.put("returnedThrough", 2);
        assertThat(observationTokens(ToolObservation.success("EVIDENCE_FOUND", allData, 2)))
                .isGreaterThan(envelope);

        UUID runId = UUID.randomUUID();
        var tool = new SearchRuleEvidenceNativeTool(reads, json, new NativeEvidenceCursorStore());
        ToolObservation first = tool.execute(searchArguments(2, null), scope(versionId, runId, envelope));

        assertThat(first.evidenceCount()).isEqualTo(1);
        assertThat(first.data()).containsEntry("hasMore", true).containsEntry("returnedThrough", 1);
        assertThat(observationTokens(first)).isLessThanOrEqualTo(envelope);
        String cursor = (String) first.data().get("nextCursor");
        assertThat(cursor).isNotBlank();

        ToolObservation blocked = tool.execute(
                searchArguments(2, cursor), scope(versionId, runId, envelope));

        assertThat(blocked.code()).isEqualTo("OBSERVATION_BUDGET_EXHAUSTED");
        assertThat(blocked.evidenceCount()).isZero();
        assertThat(blocked.data()).containsEntry("hasMore", true).containsEntry("nextCursor", cursor);
        assertThat(observationTokens(blocked)).isLessThanOrEqualTo(envelope);
        assertThat(offsets).containsExactly(0, 1);
    }

    @Test
    void cursorCannotCrossRunEvenWithTheSameDocumentAndRequest() throws Exception {
        UUID versionId = UUID.randomUUID();
        NativeEvidenceCursorStore cursors = new NativeEvidenceCursorStore();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public RuleEvidencePage searchRuleEvidencePage(SearchRuleEvidence request, int offset, int pageSize) {
                return new RuleEvidencePage(List.of(evidence(versionId, 1)), true, 1);
            }
        };
        var tool = new SearchRuleEvidenceNativeTool(reads, json, cursors);
        ToolObservation first = tool.execute(
                searchArguments(20, null), scope(versionId, UUID.randomUUID(), 1_600));
        String cursor = (String) first.data().get("nextCursor");

        assertThatThrownBy(() -> tool.execute(
                        searchArguments(20, cursor), scope(versionId, UUID.randomUUID(), 1_600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void continuationCursorSurvivesFailureAndInvalidHandlesUntilASuccessfulPageRotatesIt() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<Integer> offsets = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean failFirstContinuation =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                throw new AssertionError("native paging must not call the unbounded search API");
            }

            @Override
            public RuleEvidencePage searchRuleEvidencePage(
                    SearchRuleEvidence request, int offset, int pageSize, Set<UUID> excludedEvidenceIds) {
                offsets.add(offset);
                if (offset == 1 && failFirstContinuation.getAndSet(false)) {
                    throw new IllegalStateException("transient database failure");
                }
                return new RuleEvidencePage(
                        List.of(evidence(versionId, offset + 1)), offset < 2, 1);
            }
        };
        var tool = new SearchRuleEvidenceNativeTool(reads, json, new NativeEvidenceCursorStore());
        ToolScope scope = scope(versionId, runId, 1_600);

        ToolObservation first = tool.execute(searchArguments(3, null), scope);
        String firstCursor = (String) first.data().get("nextCursor");

        assertThatThrownBy(() -> tool.execute(searchArguments(3, firstCursor), scope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transient database failure");

        ToolObservation second = tool.execute(searchArguments(3, firstCursor), scope);
        String secondCursor = (String) second.data().get("nextCursor");

        assertThatThrownBy(() -> tool.execute(searchArguments(3, firstCursor), scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");

        ToolObservation terminal = tool.execute(searchArguments(3, secondCursor), scope);
        assertThat(terminal.code()).isEqualTo("EVIDENCE_FOUND");
        assertThat(terminal.data()).containsEntry("hasMore", false).containsEntry("nextCursor", "");
        assertThat(offsets).containsExactly(0, 1, 1, 2);

        assertThatThrownBy(() -> tool.execute(searchArguments(3, secondCursor), scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void exactPageSetKeepsEachPageAsAStableContinuationUnit() throws Exception {
        UUID versionId = UUID.randomUUID();
        List<Set<Integer>> batches = new ArrayList<>();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public RuleEvidencePage readRuleEvidencePagesPage(
                    UUID documentVersionId, Set<Integer> pageNumbers, boolean images, int offset, int pageSize) {
                batches.add(Set.copyOf(pageNumbers));
                return new RuleEvidencePage(List.of(), false, 0);
            }
        };
        var tool = new ReadRulePagesNativeTool(reads, json, new NativeEvidenceCursorStore());
        UUID runId = UUID.randomUUID();
        List<Integer> pages = java.util.stream.IntStream.rangeClosed(1, 5).boxed().toList();
        String cursor = null;
        ToolObservation result;
        do {
            result = tool.execute(pageArguments(pages, cursor), scope(versionId, runId, 1_600));
            cursor = (String) result.data().get("nextCursor");
        } while ((boolean) result.data().get("hasMore"));

        assertThat(batches).containsExactly(Set.of(1, 2), Set.of(3, 4), Set.of(5));

        batches.clear();
        tool.execute(pageArguments(List.of(9), null), scope(versionId, UUID.randomUUID(), 1_600));
        assertThat(batches).containsExactly(Set.of(9));
    }

    @Test
    void extremeRadiusIsPassedOnlyToALimitedContextQuery() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        List<Integer> requestedLimits = new ArrayList<>();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public RuleEvidenceContextPage readRuleEvidenceContextPage(
                    UUID documentVersionId, Set<UUID> ids, int radius, int offset, int pageSize) {
                assertThat(radius).isEqualTo(Integer.MAX_VALUE);
                requestedLimits.add(pageSize);
                return new RuleEvidenceContextPage(
                        List.of(evidence(versionId, 1)), List.of(evidence(versionId, 2)), true);
            }
        };
        var tool = new ExpandRuleEvidenceContextNativeTool(reads, json, new NativeEvidenceCursorStore());
        String arguments = json.writeValueAsString(Map.of(
                "evidenceIds", List.of(anchorId.toString()), "radius", Integer.MAX_VALUE));

        ToolObservation result = tool.execute(arguments, scope(versionId, UUID.randomUUID(), 1_600));

        assertThat(result.evidenceCount()).isEqualTo(2);
        assertThat(result.data()).containsEntry("hasMore", true);
        assertThat(requestedLimits).containsExactly(2);
        assertThat(observationTokens(result)).isLessThanOrEqualTo(1_600);
    }

    @Test
    void exactPageReadReturnsAContinuationBeforeAnEscapeHeavyChunkThatDoesNotFit() throws Exception {
        UUID versionId = UUID.randomUUID();
        RuleEvidence firstEvidence = evidence(versionId, 1);
        RuleEvidence escapedEvidence = escapeHeavyEvidence(versionId, 1);
        List<Integer> offsets = new ArrayList<>();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public RuleEvidencePage readRuleEvidencePagesPage(
                    UUID documentVersionId, Set<Integer> pageNumbers, boolean images, int offset, int pageSize) {
                offsets.add(offset);
                List<RuleEvidence> all = List.of(firstEvidence, escapedEvidence);
                List<RuleEvidence> page = all.subList(offset, Math.min(all.size(), offset + pageSize));
                return new RuleEvidencePage(page, offset + page.size() < all.size(), page.size());
            }
        };
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("requestedPageCount", 1);
        data.put("pageBatch", List.of(1));
        data.put("evidence", List.of(evidenceObservation(firstEvidence)));
        data.put("hasMore", true);
        data.put("nextCursor", NativeEvidenceObservationBudget.PROVISIONAL_CURSOR);
        int envelope = observationTokens(ToolObservation.partial("PAGE_EVIDENCE_PAGE_FOUND", data, 1));
        UUID runId = UUID.randomUUID();
        var tool = new ReadRulePagesNativeTool(reads, json, new NativeEvidenceCursorStore());

        ToolObservation first = tool.execute(
                pageArguments(List.of(1), null), scope(versionId, runId, envelope));

        assertThat(first.evidenceCount()).isEqualTo(1);
        assertThat(first.data()).containsEntry("hasMore", true);
        assertThat(observationTokens(first)).isLessThanOrEqualTo(envelope);
        String cursor = (String) first.data().get("nextCursor");

        ToolObservation blocked = tool.execute(
                pageArguments(List.of(1), cursor), scope(versionId, runId, envelope));

        assertThat(blocked.code()).isEqualTo("OBSERVATION_BUDGET_EXHAUSTED");
        assertThat(blocked.data()).containsEntry("nextCursor", cursor);
        assertThat(observationTokens(blocked)).isLessThanOrEqualTo(envelope);
        assertThat(offsets).containsExactly(0, 1);
    }

    @Test
    void contextExpansionPreservesItsFittingAnchorBeforeAnUnfittingNeighbor() throws Exception {
        UUID versionId = UUID.randomUUID();
        RuleEvidence anchor = evidence(versionId, 1);
        RuleEvidence escapedNeighbor = escapeHeavyEvidence(versionId, 2);
        List<Integer> offsets = new ArrayList<>();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public RuleEvidenceContextPage readRuleEvidenceContextPage(
                    UUID documentVersionId, Set<UUID> ids, int radius, int offset, int pageSize) {
                offsets.add(offset);
                return offset == 0
                        ? new RuleEvidenceContextPage(List.of(anchor), List.of(escapedNeighbor), false)
                        : new RuleEvidenceContextPage(List.of(), List.of(escapedNeighbor), false);
            }
        };
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("requestedAnchorCount", 1);
        data.put("anchorBatchCount", 1);
        data.put("returnedAnchorCount", 1);
        data.put("anchors", List.of(evidenceObservation(anchor)));
        data.put("surroundingEvidence", List.of());
        data.put("hasMore", true);
        data.put("nextCursor", NativeEvidenceObservationBudget.PROVISIONAL_CURSOR);
        data.put("contextApplicabilityAuthority", false);
        data.put("nextAction", "READ_EXACT_PAGES_AND_CHECK_APPLICABILITY");
        int envelope = observationTokens(ToolObservation.partial("EVIDENCE_CONTEXT_PARTIAL", data, 1));
        UUID runId = UUID.randomUUID();
        var tool = new ExpandRuleEvidenceContextNativeTool(reads, json, new NativeEvidenceCursorStore());
        String firstArguments = contextArguments(anchor.chunkId(), null);

        ToolObservation first = tool.execute(firstArguments, scope(versionId, runId, envelope));

        assertThat(first.evidenceCount()).isEqualTo(1);
        assertThat(first.data()).containsEntry("hasMore", true);
        assertThat(observationTokens(first)).isLessThanOrEqualTo(envelope);
        String cursor = (String) first.data().get("nextCursor");

        ToolObservation blocked = tool.execute(
                contextArguments(anchor.chunkId(), cursor), scope(versionId, runId, envelope));

        assertThat(blocked.code()).isEqualTo("OBSERVATION_BUDGET_EXHAUSTED");
        assertThat(blocked.data()).containsEntry("nextCursor", cursor);
        assertThat(observationTokens(blocked)).isLessThanOrEqualTo(envelope);
        assertThat(offsets).containsExactly(0, 1);
    }

    @Test
    void relationshipObservationIncludesItsCompleteCanonicalEnvelope() throws Exception {
        UUID versionId = UUID.randomUUID();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                throw new AssertionError("unbounded search is forbidden");
            }

            @Override
            public RuleEvidencePage searchRuleEvidencePage(
                    SearchRuleEvidence request, int offset, int pageSize, Set<UUID> excludedEvidenceIds) {
                return new RuleEvidencePage(List.of(evidence(versionId, 1), evidence(versionId, 2)), false, 1);
            }
        };
        var tool = new SearchRuleRelationshipsNativeTool(reads, json, new NativeEvidenceCursorStore());

        ToolObservation result = tool.execute(
                relationshipArguments(1, null), scope(versionId, UUID.randomUUID(), 1_600));

        assertThat(result.evidenceCount()).isEqualTo(2);
        assertThat(result.data()).containsEntry("hasMore", false).containsEntry("nextCursor", "");
        assertThat(observationTokens(result)).isLessThanOrEqualTo(1_600);
    }

    @Test
    void relationshipContinuationStopsBeforeAnEscapeHeavyNextAnchorWithoutRotatingItsCursor() throws Exception {
        UUID versionId = UUID.randomUUID();
        RuleEvidence firstEvidence = evidence(versionId, 1);
        RuleEvidence escapedEvidence = escapeHeavyEvidence(versionId, 2);
        List<Integer> offsets = new ArrayList<>();
        AssistantReadTools reads = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                throw new AssertionError("unbounded search is forbidden");
            }

            @Override
            public RuleEvidencePage searchRuleEvidencePage(
                    SearchRuleEvidence request, int offset, int pageSize, Set<UUID> excludedEvidenceIds) {
                offsets.add(offset);
                return new RuleEvidencePage(
                        List.of(offset == 0 ? firstEvidence : escapedEvidence), offset == 0, 1);
            }
        };
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("evidence", List.of(relationshipEvidenceObservation(firstEvidence)));
        data.put("hasMore", true);
        data.put("nextCursor", NativeEvidenceObservationBudget.PROVISIONAL_CURSOR);
        data.put("relationshipClassificationAuthority", false);
        data.put("nextAction", "READ_EXACT_PAGES_AND_COMPARE_APPLICABILITY");
        int envelope = observationTokens(ToolObservation.partial("RELATIONSHIP_CANDIDATE_PAGE", data, 1));
        UUID runId = UUID.randomUUID();
        var tool = new SearchRuleRelationshipsNativeTool(reads, json, new NativeEvidenceCursorStore());

        ToolObservation first = tool.execute(
                relationshipArguments(2, null), scope(versionId, runId, envelope));

        assertThat(first.evidenceCount()).isEqualTo(1);
        assertThat(first.data()).containsEntry("hasMore", true);
        assertThat(observationTokens(first)).isLessThanOrEqualTo(envelope);
        String cursor = (String) first.data().get("nextCursor");

        ToolObservation blocked = tool.execute(
                relationshipArguments(2, cursor), scope(versionId, runId, envelope));

        assertThat(blocked.code()).isEqualTo("OBSERVATION_BUDGET_EXHAUSTED");
        assertThat(blocked.data()).containsEntry("nextCursor", cursor);
        assertThat(observationTokens(blocked)).isLessThanOrEqualTo(envelope);
        assertThat(offsets).containsExactly(0, 1);
    }

    private String searchArguments(int limit, String cursor) throws Exception {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("query", "turn order");
        arguments.put("limit", limit);
        arguments.put("sectionTypes", List.of());
        arguments.put("includeAdjacentContext", false);
        if (cursor != null) arguments.put("cursor", cursor);
        return json.writeValueAsString(arguments);
    }

    private String pageArguments(List<Integer> pages, String cursor) throws Exception {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("pageNumbers", pages);
        if (cursor != null) arguments.put("cursor", cursor);
        return json.writeValueAsString(arguments);
    }

    private String contextArguments(UUID evidenceId, String cursor) throws Exception {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("evidenceIds", List.of(evidenceId.toString()));
        arguments.put("radius", 1);
        if (cursor != null) arguments.put("cursor", cursor);
        return json.writeValueAsString(arguments);
    }

    private String relationshipArguments(int limit, String cursor) throws Exception {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("topic", "general rule and exception");
        arguments.put("limit", limit);
        if (cursor != null) arguments.put("cursor", cursor);
        return json.writeValueAsString(arguments);
    }

    private ToolScope scope(UUID versionId, UUID runId, int observationTokens) {
        return new ToolScope("player", versionId, runId, Instant.now().plusSeconds(30), observationTokens);
    }

    private int observationTokens(ToolObservation observation) {
        return NativeEvidenceObservationBudget.serializedTokens(
                json, observation, "0".repeat(64));
    }

    private Map<String, Object> evidenceObservation(RuleEvidence evidence) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("evidenceId", evidence.chunkId().toString());
        value.put("sectionType", evidence.sectionType().strip());
        value.put("heading", evidence.heading().strip());
        value.put("excerpt", evidence.excerpt().strip());
        value.put("pageFrom", evidence.pageFrom());
        value.put("pageTo", evidence.pageTo());
        return Map.copyOf(value);
    }

    private Map<String, Object> relationshipEvidenceObservation(RuleEvidence evidence) {
        Map<String, Object> value = new java.util.LinkedHashMap<>(evidenceObservation(evidence));
        value.put("classificationAuthority", false);
        value.put("evidenceMechanicalAuthority", true);
        return Map.copyOf(value);
    }

    private RuleEvidence evidence(UUID versionId, int page) {
        return new RuleEvidence(
                UUID.nameUUIDFromBytes((versionId + ":" + page).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                versionId, "RULE", "Rule " + page, "Canonical evidence " + page, page, page);
    }

    private RuleEvidence escapeHeavyEvidence(UUID versionId, int page) {
        return new RuleEvidence(
                UUID.nameUUIDFromBytes((versionId + ":escaped:" + page)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                versionId,
                "RULE",
                "Quoted \\\"heading\\\" 玩家顺序 🎲",
                ("line=\\\"value\\\"\\\\path\\n"
                                + "按照规则逐项结算，不要跳过条件。🎲🧭")
                        .repeat(180),
                page,
                page);
    }
}
