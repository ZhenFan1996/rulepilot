package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class TeachingSourcePageEvidenceRefinerTest {

    private final UUID versionId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void readsTheCanonicalPageEvenWhenSearchAlreadyHitEveryPlannedPage() {
        RuleEvidence searchHit = evidence(UUID.randomUUID(), 2, "Visible heading and first procedure.");
        RuleEvidence laterClause = evidence(UUID.randomUUID(), 2, "A later exception on the same page.");
        AssistantReadTools tools = tools(List.of(searchHit, laterClause));
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingPlan plan = plan(List.of(2));
        var deterministic = verified(2, searchHit);

        var result = refiner(scopes(), tools, invocations)
                .refine(plan, plan.sections().getFirst(), runId, deterministic);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.evidence()).containsExactly(searchHit, laterClause);
        assertThat(invocations.toolCalls).hasValue(1);
        verify(tools).readRuleEvidencePages(versionId, Set.of(2), false);
    }

    @Test
    void reusesACompleteExactVisualPageObservationWithoutReadingTheSamePageAgain() {
        UUID sourceId = UUID.randomUUID();
        RuleEvidence visualTranscript = new RuleEvidence(
                sourceId,
                versionId,
                "SETUP",
                "Setup",
                "[rule-group:prepare-board] Put the board in the middle.",
                2,
                2,
                List.of(new RulePageImage(2, "image/png", new byte[] {1}, 100, 80)),
                RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION);
        RuleEvidence canonicalPlaceholder = new RuleEvidence(
                sourceId,
                versionId,
                "SETUP",
                "Setup",
                "Image-only page",
                2,
                2,
                List.of(new RulePageImage(2, "image/png", new byte[] {1}, 100, 80)),
                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER);
        RuleEvidence laterClause = evidence(UUID.randomUUID(), 2, "A later exception on the same page.");
        var observation = new TeachingVisualEvidenceResolver.CanonicalPageObservation(
                runId, versionId, Set.of(2), List.of(canonicalPlaceholder, laterClause));
        var deterministic = new TeachingSectionEvidenceRetriever.Result(
                List.of(visualTranscript, laterClause),
                3,
                TeachingSectionEvidenceRetriever.State.VERIFIED,
                Optional.of(observation));
        AssistantReadTools tools = mock(AssistantReadTools.class);
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingPlan plan = plan(List.of(2));

        var result = refiner(scopes(), tools, invocations)
                .refine(plan, plan.sections().getFirst(), runId, deterministic);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.evidence()).containsExactly(visualTranscript, laterClause);
        assertThat(invocations.toolCalls).hasValue(0);
        verify(tools, never()).readRuleEvidencePages(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void reusesTheActualNonProgressiveVisualReadAcrossRetrievalAndRefinement() {
        UUID sourceId = UUID.randomUUID();
        String transcript = "[rule-group:prepare-board] Put the board in the middle.";
        RuleEvidence searchPlaceholder = new RuleEvidence(
                sourceId,
                versionId,
                "SETUP",
                "Setup",
                "Image-only page",
                2,
                2,
                List.of(),
                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER);
        RuleEvidence canonicalPlaceholder = new RuleEvidence(
                sourceId,
                versionId,
                "SETUP",
                "Setup",
                "Image-only page",
                2,
                2,
                List.of(new RulePageImage(2, "image/png", new byte[] {1}, 100, 80)),
                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER);
        RuleEvidence laterClause = evidence(UUID.randomUUID(), 2, "A later exception on the same page.");
        AssistantReadTools tools = mock(AssistantReadTools.class);
        when(tools.searchRuleEvidence(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(searchPlaceholder));
        when(tools.readRuleEvidencePages(versionId, Set.of(2), true))
                .thenReturn(List.of(canonicalPlaceholder, laterClause));
        VisualRulebookPageFacts visualFacts = mock(VisualRulebookPageFacts.class);
        when(visualFacts.find(versionId, Set.of(2))).thenReturn(List.of(new PageFact(
                2,
                "SETUP",
                transcript,
                List.of("setup"),
                List.of(),
                List.of(),
                true,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of("prepare-board"),
                true,
                List.of(new RuleGroupFact("prepare-board", "Prepare board", transcript)))));
        RecordingInvocations invocations = new RecordingInvocations();
        var resolver = new TeachingVisualEvidenceResolver(
                tools,
                invocations,
                visualFacts,
                VisualRulebookPageCatalogModel.unavailable());
        var retriever = new TeachingSectionEvidenceRetriever(
                tools, new PolicyEvidenceVerifier(), invocations, resolver);
        TeachingPlan plan = plan(List.of(2));

        var retrieved = retriever.retrieve(
                plan, plan.sections().getFirst(), runId, 3, true);
        var result = refiner(scopes(), tools, invocations)
                .refine(plan, plan.sections().getFirst(), runId, retrieved);

        assertThat(retrieved.canonicalPageObservation()).isPresent();
        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(invocations.toolCalls).hasValue(2);
        assertThat(invocations.operations).containsExactly(
                "searchRuleEvidence|1", "readRuleEvidencePages|1");
        assertThat(result.evidence()).hasSize(2);
        assertThat(result.evidence().getFirst()).satisfies(derived -> {
            assertThat(derived.chunkId()).isEqualTo(sourceId);
            assertThat(derived.excerpt()).isEqualTo(transcript);
            assertThat(derived.contentKind()).isEqualTo(RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION);
        });
        assertThat(result.evidence().get(1)).isEqualTo(laterClause);
        verify(tools).readRuleEvidencePages(versionId, Set.of(2), true);
        verify(tools, never()).readRuleEvidencePages(versionId, Set.of(2), false);
    }

    @Test
    void fallsBackToACanonicalReadWhenThePriorObservationCoversDifferentPages() {
        RuleEvidence pageTwo = evidence(UUID.randomUUID(), 2, "A page two clause.");
        RuleEvidence pageFive = evidence(UUID.randomUUID(), 5, "A page five clause.");
        var observation = new TeachingVisualEvidenceResolver.CanonicalPageObservation(
                runId, versionId, Set.of(2), List.of(pageTwo));
        var deterministic = new TeachingSectionEvidenceRetriever.Result(
                List.of(pageTwo),
                2,
                TeachingSectionEvidenceRetriever.State.VERIFIED,
                Optional.of(observation));
        AssistantReadTools tools = tools(List.of(pageTwo, pageFive));
        TeachingPlan plan = plan(List.of(2, 5));

        var result = refiner(scopes(), tools, new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, deterministic);

        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.evidence()).containsExactly(pageTwo, pageFive);
        verify(tools).readRuleEvidencePages(versionId, Set.of(2, 5), false);
    }

    @Test
    void keepsTheCompleteVisualTranscriptWhenTheCanonicalPageStillContainsTheImagePlaceholder() {
        UUID sourceId = UUID.randomUUID();
        RuleEvidence visualTranscript = new RuleEvidence(
                sourceId,
                versionId,
                "SETUP",
                "Setup",
                "[rule-group:prepare-board] Put the board in the middle, then give every player one marker.",
                2,
                2,
                List.of(new RulePageImage(2, "image/png", new byte[] {1, 2, 3}, 100, 80)),
                RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION);
        RuleEvidence canonicalPlaceholder = new RuleEvidence(
                sourceId,
                versionId,
                "SETUP",
                "Setup",
                "Image-only page",
                2,
                2,
                List.of(),
                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER);
        TeachingPlan plan = plan(List.of(2));

        var result = refiner(scopes(), tools(List.of(canonicalPlaceholder)), new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, verified(1, visualTranscript));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).containsExactly(visualTranscript);
    }

    @Test
    void stillRejectsDifferentTextForTheSameCanonicalEvidenceIdentity() {
        UUID sourceId = UUID.randomUUID();
        RuleEvidence first = evidence(sourceId, 2, "Place one marker beside the board.");
        RuleEvidence conflicting = evidence(sourceId, 2, "Place two markers beside the board.");
        TeachingPlan plan = plan(List.of(2));

        var result = refiner(scopes(), tools(List.of(conflicting)), new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, verified(1, first));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.INVALID);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void readsMissingValidatedPagesDirectlyAndMergesCanonicalEvidence() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        RuleEvidence later = evidence(UUID.randomUUID(), 5, "Deal each player the starting items.");
        AssistantReadTools tools = tools(List.of(later));
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingPlan plan = plan(List.of(2, 5));

        var result = refiner(scopes(), tools, invocations)
                .refine(plan, plan.sections().getFirst(), runId, verified(2, initial));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.evidence()).extracting(RuleEvidence::pageFrom).containsExactly(2, 5);
        assertThat(invocations.toolCalls).hasValue(1);
        assertThat(invocations.modelCalls).hasValue(0);
        assertThat(invocations.operations).containsExactly("readTeachingSourcePages|1");
        verify(tools).readRuleEvidencePages(versionId, Set.of(2, 5), false);
        verify(tools, never()).readRuleEvidenceIds(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anySet());
    }

    @Test
    void readsEveryValidatedPageAcrossMultipleBoundedBatches() {
        RuleEvidence firstPage = evidence(UUID.randomUUID(), 1, "First independently verified rule.");
        List<Set<Integer>> requestedBatches = new java.util.ArrayList<>();
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID documentVersionId,
                    Set<Integer> pageNumbers,
                    boolean includePageImages) {
                assertThat(documentVersionId).isEqualTo(versionId);
                assertThat(includePageImages).isFalse();
                assertThat(pageNumbers).hasSizeLessThanOrEqualTo(5);
                requestedBatches.add(Set.copyOf(pageNumbers));
                return pageNumbers.stream()
                        .map(page -> page == 1
                                ? firstPage
                                : evidence(UUID.randomUUID(), page, "Verified rule on page " + page + "."))
                        .toList();
            }
        };
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingPlan plan = plan(List.of(1, 2, 3, 4, 5, 6));

        var result = refiner(scopes(), tools, invocations)
                .refine(plan, plan.sections().getFirst(), runId, verified(2, firstPage));

        assertThat(requestedBatches).hasSize(2);
        assertThat(requestedBatches.stream().flatMap(Set::stream)).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
        assertThat(invocations.operations).containsExactly(
                "readTeachingSourcePages|1|1", "readTeachingSourcePages|1|2");
        assertThat(result.toolCalls()).isEqualTo(4);
        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).extracting(RuleEvidence::pageFrom).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void letsAnEmptySectionRecoverFromCanonicalPageEvidence() {
        RuleEvidence recovered = evidence(UUID.randomUUID(), 7, "The round ends after this procedure.");
        TeachingPlan plan = plan(List.of(7));
        var empty = new TeachingSectionEvidenceRetriever.Result(
                List.of(), 1, TeachingSectionEvidenceRetriever.State.EMPTY);

        var result = refiner(scopes(), tools(List.of(recovered)), new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, empty);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.evidence()).containsExactly(recovered);
    }

    @Test
    void keepsAnUnanchoredEmptyChapterInsufficient() {
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        AssistantReadTools tools = mock(AssistantReadTools.class);
        TeachingPlan plan = plan(List.of());
        var empty = new TeachingSectionEvidenceRetriever.Result(
                List.of(), 1, TeachingSectionEvidenceRetriever.State.EMPTY);

        var result = refiner(scopes, tools, new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, empty);

        assertThat(result).isSameAs(empty);
        verify(scopes, never()).create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preservesDeterministicEvidenceWhenOwnerScopeIsUnavailable() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        when(scopes.create("player", versionId, runId)).thenReturn(Optional.empty());
        AssistantReadTools tools = mock(AssistantReadTools.class);
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingPlan plan = plan(List.of(2, 5));
        var deterministic = verified(2, initial);

        var result = refiner(scopes, tools, invocations)
                .refine(plan, plan.sections().getFirst(), runId, deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(invocations.toolCalls).hasValue(0);
        verify(tools, never()).readRuleEvidencePages(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void keepsProgressivePageEvidenceOutOfTheRefinementPath() {
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        AssistantReadTools tools = mock(AssistantReadTools.class);
        var progressive = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Test game",
                "Teach one exact page.",
                List.of(new PlannedSection(
                        1,
                        "progressive-visual-page-rules-2",
                        "Turn",
                        "Explain only the rule visibly supported on page 2.",
                        true,
                        true,
                        List.of("turn"),
                        List.of("setup", "core_loop", "end", "scoring"),
                        List.of(2))),
                "player",
                Instant.now());
        var deterministic = verified(1, evidence(UUID.randomUUID(), 2, "Exact page transcription."));

        var result = refiner(scopes, tools, new RecordingInvocations())
                .refine(progressive, progressive.sections().getFirst(), runId, deterministic);

        assertThat(result).isSameAs(deterministic);
        verify(scopes, never()).create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preservesVerifiedEvidenceWhenThePageReadFails() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        AssistantReadTools tools = mock(AssistantReadTools.class);
        when(tools.readRuleEvidencePages(versionId, Set.of(2, 5), false))
                .thenThrow(new IllegalStateException("repository unavailable"));
        TeachingPlan plan = plan(List.of(2, 5));

        var result = refiner(scopes(), tools, new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, verified(2, initial));

        assertThat(result.evidence()).containsExactly(initial);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
    }

    @Test
    void retainsCompletedCanonicalBatchesWhenALaterBatchFails() {
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID documentVersionId,
                    Set<Integer> pageNumbers,
                    boolean includePageImages) {
                if (pageNumbers.contains(6)) throw new IllegalStateException("second batch unavailable");
                return pageNumbers.stream()
                        .map(page -> evidence(UUID.randomUUID(), page, "Verified page " + page + "."))
                        .toList();
            }
        };
        TeachingPlan plan = plan(List.of(1, 2, 3, 4, 5, 6));
        var empty = new TeachingSectionEvidenceRetriever.Result(
                List.of(), 0, TeachingSectionEvidenceRetriever.State.EMPTY);

        var result = refiner(scopes(), tools, new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, empty);

        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).extracting(RuleEvidence::pageFrom)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    void propagatesBudgetStopsInsteadOfMaskingCancellationAsOptionalFailure() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        RecordingInvocations invocations = new RecordingInvocations();
        invocations.failure = new AgentExecutionStoppedException(StopReason.TOOL_BUDGET);
        TeachingPlan plan = plan(List.of(2, 5));

        assertThatThrownBy(() -> refiner(scopes(), tools(List.of()), invocations)
                        .refine(plan, plan.sections().getFirst(), runId, verified(2, initial)))
                .isInstanceOf(AgentExecutionStoppedException.class);
    }

    @Test
    void rejectsCrossVersionPageEvidenceBeforeComposition() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        RuleEvidence escaped = new RuleEvidence(
                UUID.randomUUID(), UUID.randomUUID(), "SETUP", "Setup", "Wrong scope.", 5, 5);
        TeachingPlan plan = plan(List.of(2, 5));

        var result = refiner(scopes(), tools(List.of(escaped)), new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, verified(1, initial));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.INVALID);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void ignoresCanonicalEvidenceOutsideTheMissingValidatedPages() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        RuleEvidence unrelated = evidence(UUID.randomUUID(), 9, "An unrelated appendix entry.");
        TeachingPlan plan = plan(List.of(2, 5));

        var result = refiner(scopes(), tools(List.of(unrelated)), new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, verified(1, initial));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).containsExactly(initial);
        assertThat(result.toolCalls()).isEqualTo(2);
    }

    @Test
    void keepsAllBoundedPageEvidenceThenPresentsItInCanonicalPageOrder() {
        RuleEvidence pageTwoSearchHit = evidence(UUID.randomUUID(), 2, "Relevant hit on the first planned page.");
        RuleEvidence pageFiveSearchHit = evidence(UUID.randomUUID(), 5, "Relevant hit on the second planned page.");
        List<RuleEvidence> canonical = new java.util.ArrayList<>();
        canonical.add(pageTwoSearchHit);
        canonical.add(pageFiveSearchHit);
        for (int index = 1; index <= 5; index++) {
            canonical.add(evidence(UUID.randomUUID(), 2, "Opaque page two clause " + index));
        }
        for (int index = 1; index <= 5; index++) {
            canonical.add(evidence(UUID.randomUUID(), 5, "Opaque page five clause " + index));
        }
        TeachingPlan plan = plan(List.of(2, 5));

        var result = refiner(scopes(), tools(canonical), new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, verified(2, pageTwoSearchHit, pageFiveSearchHit));

        assertThat(result.evidence()).hasSize(12);
        assertThat(result.evidence()).extracting(RuleEvidence::pageFrom)
                .containsExactly(2, 2, 2, 2, 2, 2, 5, 5, 5, 5, 5, 5);
        assertThat(result.evidence().getFirst()).isEqualTo(pageTwoSearchHit);
        assertThat(result.evidence().get(6)).isEqualTo(pageFiveSearchHit);
        assertThat(result.evidence().stream().filter(source -> source.pageFrom() == 2)).hasSize(6);
        assertThat(result.evidence().stream().filter(source -> source.pageFrom() == 5)).hasSize(6);
    }

    @Test
    void prioritizesEveryAgentPlannedSourceAnchorBeforePageDiversityTruncation() {
        RuleEvidence searchHit = evidence(UUID.randomUUID(), 2, "A useful nearby clause without the planned anchor.");
        List<RuleEvidence> canonical = new java.util.ArrayList<>();
        canonical.add(searchHit);
        for (int index = 1; index <= 5; index++) {
            canonical.add(evidence(UUID.randomUUID(), 2, "Nearby clause " + index + "."));
        }
        RuleEvidence plannedAnchor = evidence(
                UUID.randomUUID(), 2, "R-omega is the independently planned conditional relation.");
        canonical.add(plannedAnchor);
        TeachingPlan plan = planWithUnit("R-omega", List.of(2));

        var result = refiner(scopes(), tools(canonical), new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, verified(1, searchHit));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).contains(plannedAnchor);
    }

    @Test
    void presentsTheAgentOwnedSourcePageBeforeEarlierIncidentalContextWithoutDroppingEither() {
        RuleEvidence earlierContext = evidence(
                UUID.randomUUID(), 2, "A cross-reference points forward to the independently planned relation.");
        RuleEvidence plannedAnchor = evidence(
                UUID.randomUUID(), 5, "R-omega is the independently planned relation.");
        RuleEvidence samePageContinuation = evidence(
                UUID.randomUUID(), 5, "The ordered relation ends with this terminal state.");
        TeachingPlan plan = planWithUnit("R-omega", List.of(2, 5));

        var result = refiner(
                        scopes(),
                        tools(List.of(earlierContext, plannedAnchor, samePageContinuation)),
                        new RecordingInvocations())
                .refine(
                        plan,
                        plan.sections().getFirst(),
                        runId,
                        verified(2, plannedAnchor, earlierContext));

        assertThat(result.evidence())
                .containsExactly(plannedAnchor, samePageContinuation, earlierContext);
    }

    @Test
    void marksTheChapterInvalidBeforeCompositionWhenItsCanonicalSourcePageIsAbsent() {
        RuleEvidence unrelated = evidence(UUID.randomUUID(), 3, "A different relation is present.");
        TeachingPlan plan = planWithUnit("R-missing", List.of(2));

        var result = refiner(scopes(), tools(List.of(unrelated)), new RecordingInvocations())
                .refine(plan, plan.sections().getFirst(), runId, verified(1, unrelated));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.INVALID);
        assertThat(result.evidence()).isEmpty();
    }

    private TeachingSourcePageEvidenceRefiner refiner(
            NativeToolScopes scopes, AssistantReadTools tools, RecordingInvocations invocations) {
        return new TeachingSourcePageEvidenceRefiner(
                scopes, tools, new PolicyEvidenceVerifier(), invocations);
    }

    private TeachingPlan plan(List<Integer> sourcePages) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Test game",
                "Teach one grounded section.",
                List.of(new PlannedSection(
                        1,
                        "setup",
                        "Setup",
                        "Make the table ready and account for every validated source page.",
                        true,
                        false,
                        List.of("setup starting items"),
                        List.of("setup"),
                        sourcePages)),
                "player",
                Instant.now());
    }

    private TeachingPlan planWithUnit(String sourceIdentifier, List<Integer> sourcePages) {
        var slot = new com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft(
                "opaque-source",
                com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole.SUPPORTING_RULE,
                sourceIdentifier,
                sourcePages,
                "setup",
                "agent-owned-unit",
                com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability.SOURCED);
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Test game",
                "Teach one grounded section.",
                List.of(new PlannedSection(
                        1,
                        "setup",
                        "Setup",
                        "Teach the Agent-owned relation without a fixed chapter template.",
                        true,
                        false,
                        TeachingUnitContract.encodeUnits(List.of(slot)),
                        List.of(TeachingSourceCoverageContract.CONTRACT_VERSION_TAG),
                        sourcePages)),
                "player",
                Instant.now());
    }

    private TeachingSectionEvidenceRetriever.Result verified(int toolCalls, RuleEvidence... evidence) {
        return new TeachingSectionEvidenceRetriever.Result(
                List.of(evidence), toolCalls, TeachingSectionEvidenceRetriever.State.VERIFIED);
    }

    private NativeToolScopes scopes() {
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        when(scopes.create("player", versionId, runId)).thenReturn(Optional.of(
                new ToolScope("player", versionId, runId, Instant.now().plusSeconds(30))));
        return scopes;
    }

    private AssistantReadTools tools(List<RuleEvidence> evidence) {
        AssistantReadTools tools = mock(AssistantReadTools.class);
        when(tools.readRuleEvidencePages(
                        org.mockito.ArgumentMatchers.eq(versionId),
                        org.mockito.ArgumentMatchers.anySet(),
                        org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(evidence);
        return tools;
    }

    private RuleEvidence evidence(UUID id, int page, String excerpt) {
        return new RuleEvidence(id, versionId, "SETUP", "Setup", excerpt, page, page);
    }

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final List<String> operations = new java.util.ArrayList<>();
        private RuntimeException failure;

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            if (type == ActivityType.MODEL) modelCalls.incrementAndGet();
            if (type == ActivityType.TOOL) toolCalls.incrementAndGet();
            operations.add(operation);
            if (failure != null) throw failure;
            return invocation.get();
        }
    }
}
