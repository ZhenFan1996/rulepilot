package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDependencyDraft;
import com.rulepilot.teaching.TeachingOutlineModel.WholeGameUnderstandingDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TeachingPlanServiceTest {

    @Test
    void sizesVisualPreparationForEveryPageAttemptAndEveryPageOwnedPlannerStage() {
        assertThat(TeachingPlanService.preparationWorkload(true, 20))
                .isEqualTo(new com.rulepilot.assistant.AssistantRuns.WorkloadDemand(0, 128));
    }

    @Test
    void keepsTheLargestAcceptedDocumentWithinTheCollapsedPageOwnedCallGraph() {
        assertThat(TeachingPlanService.preparationWorkload(true, 500))
                .isEqualTo(new com.rulepilot.assistant.AssistantRuns.WorkloadDemand(0, 3_008));
    }

    @Test
    void keepsExtractedTextPreparationSmallBecauseItDoesNotRunTheVisualShardGraph() {
        assertThat(TeachingPlanService.preparationWorkload(false, 20))
                .isEqualTo(new com.rulepilot.assistant.AssistantRuns.WorkloadDemand(0, 16));
    }

    @Test
    void routesAnyTextPageThatWouldBeSampledThroughTheDurablePagePlanner() {
        List<PageView> pages = List.of(
                new PageView(1, "setup", 5),
                new PageView(2, "x".repeat(TeachingPageCatalogText.MAX_CHARACTERS + 1),
                        TeachingPageCatalogText.MAX_CHARACTERS + 1));

        assertThat(TeachingPlanService.requiresCanonicalPagePlanning(pages)).isTrue();
        assertThat(TeachingPlanService.preparationWorkload(true, pages.size()))
                .isEqualTo(new com.rulepilot.assistant.AssistantRuns.WorkloadDemand(0, 20));
    }

    @Test
    void routesDenseMultiPageTextThroughTheHierarchicalPlannerWithoutANameBasedSpecialCase() {
        List<PageView> pages = IntStream.rangeClosed(1, 6)
                .mapToObj(page -> new PageView(page, "r".repeat(5_500), 5_500))
                .toList();

        assertThat(TeachingPlanService.requiresCanonicalPagePlanning(pages)).isTrue();
        assertThat(TeachingPlanService.requiresCanonicalPagePlanning(List.of(
                        new PageView(1, "A short complete rules leaflet.", 31),
                        new PageView(2, "One final scoring rule.", 23))))
                .isFalse();
    }

    @Test
    void isolatesOnlyWorkloadsBeyondTheOrdinaryPreparationCallGraph() {
        assertThat(TeachingPlanService.requiresExtendedPreparationLane(
                        new com.rulepilot.assistant.AssistantRuns.WorkloadDemand(0, 16)))
                .isFalse();
        assertThat(TeachingPlanService.requiresExtendedPreparationLane(
                        new com.rulepilot.assistant.AssistantRuns.WorkloadDemand(0, 17)))
                .isTrue();
    }

    @Test
    void rejectsAnEmptyPreparationInsteadOfStartingWithAnArbitraryFixedBudget() {
        assertThatThrownBy(() -> TeachingPlanService.preparationWorkload(true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page count");
    }

    @Test
    void rejectsAVisualPreparationWhoseBoundedCallGraphCannotFitTheRunCounter() {
        assertThatThrownBy(() -> TeachingPlanService.preparationWorkload(true, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workload is too large");
    }

    @Test
    void refreshesVisualFactsBeforeAStoredPlanIsReused() {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        List<PageView> visualPages = List.of(
                new PageView(1, "", 0),
                new PageView(2, "", 0));
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Example Rules")));
        when(documents.pages(documentVersionId)).thenReturn(visualPages);
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                mock(CatalogEditionLookup.class),
                visualCataloger,
                mock(com.rulepilot.teaching.TeachingOutlineModel.class),
                mock(AuditedAgentInvocations.class),
                new TeachingPlanFactory(),
                mock(TeachingPlanRepository.class),
                mock(TeachingPlanPublication.class));

        service.refreshVisualEvidence(documentVersionId, "alice", assistantRunId);

        verify(visualCataloger).catalogVisualPages(
                documentVersionId, visualPages, "Example Rules", "alice", assistantRunId);
    }

    @Test
    void leavesTextRulebookEvidenceUntouchedDuringAPlanRetry() {
        UUID documentVersionId = UUID.randomUUID();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Example Rules")));
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                new PageView(1, "Take one action.", 16)));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                mock(CatalogEditionLookup.class),
                visualCataloger,
                mock(com.rulepilot.teaching.TeachingOutlineModel.class),
                mock(AuditedAgentInvocations.class),
                new TeachingPlanFactory(),
                mock(TeachingPlanRepository.class),
                mock(TeachingPlanPublication.class));

        service.refreshVisualEvidence(documentVersionId, "alice", UUID.randomUUID());

        verifyNoInteractions(visualCataloger);
    }

    @Test
    void requiresAWholeGameSemanticOutlineBeforePublishingAVisualPlan() {
        UUID documentVersionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        List<PageView> visualPages = IntStream.rangeClosed(1, 4)
                .mapToObj(page -> new PageView(page, "", 0))
                .toList();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        com.rulepilot.teaching.TeachingOutlineModel outlines =
                mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        AuditedAgentInvocations invocations = mock(AuditedAgentInvocations.class);
        TeachingPlanRepository repository = mock(TeachingPlanRepository.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, editionId, "READY", "alice", "example_rules_en.pdf")));
        when(catalog.findEdition(editionId)).thenReturn(Optional.of(new CatalogEditionLookup.EditionReference(
                editionId, UUID.randomUUID(), "Example Game", "First edition", "en", Set.of())));
        when(documents.pages(documentVersionId)).thenReturn(visualPages);
        List<PageInput> catalogPages = IntStream.rangeClosed(1, 4)
                .mapToObj(page -> completeVisualPage(
                        page,
                        "SOURCE GROUP " + page,
                        "A page-owned relation is directly visible.",
                        List.of()))
                .toList();
        when(visualCataloger.catalogVisualPages(
                        documentVersionId, visualPages, "example_rules_en.pdf", "alice", null))
                .thenReturn(catalogPages);
        when(outlines.organize(any())).thenReturn(sourceBoundOutline(
                "Example Game",
                "Understand the complete visible source before teaching its connected flow.",
                List.of(new TopicDraft(
                        "whole-flow",
                        "完整流程",
                        "根据整份可见证据组织一章相互关联的讲解。",
                        true,
                        true,
                        catalogPages.stream()
                                .flatMap(page -> page.sourceRuleGroupIdentifiers().stream())
                                .toList(),
                        List.of("setup", "core_loop", "end", "scoring", "source_coverage"),
                        List.of(1, 2, 3, 4)))));
        when(publication.publish(any(TeachingPlan.class), eq("Example Game")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                catalog,
                visualCataloger,
                outlines,
                invocations,
                new TeachingPlanFactory(),
                repository,
                publication);

        TeachingPlan plan = service.create(documentVersionId, null, "alice", null);

        assertThat(plan.gameTitle()).isEqualTo("Example Game");
        assertThat(plan.wholeGameContext().evidenceBound()).isTrue();
        assertThat(plan.sections()).singleElement().satisfies(section ->
                assertThat(section.sourcePageNumbers()).containsExactly(1, 2, 3, 4));
        assertThat(plan.sections()).allSatisfy(section -> assertThat(section.coverageTags())
                .contains(
                        TeachingSourceCoverageContract.CONTRACT_VERSION_TAG,
                        TeachingWholeGameUnderstandingPolicy.CONTRACT_TAG));
        verify(publication).publish(any(TeachingPlan.class), eq("Example Game"));
        verify(outlines).organize(any());
    }

    @Test
    void keepsACustomLearningGoalOnTheLegacySemanticOutlinePath() {
        UUID documentVersionId = UUID.randomUUID();
        List<PageView> visualPages = List.of(new PageView(1, "", 0));
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        com.rulepilot.teaching.TeachingOutlineModel outlines =
                mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        AuditedAgentInvocations invocations = mock(AuditedAgentInvocations.class);
        TeachingPlanRepository repository = mock(TeachingPlanRepository.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        String learningGoal = "先讲清楚容易混淆的行动。";
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Example Game")));
        when(documents.pages(documentVersionId)).thenReturn(visualPages);
        when(visualCataloger.catalogVisualPages(
                        documentVersionId, visualPages, "Example Game", "alice", null))
                .thenReturn(List.of(visualPage(
                        1,
                        "VISIBLE TERM",
                        "A complete page-scoped factual observation supports the requested lesson.")));
        when(outlines.organize(any())).thenReturn(sourceBoundOutline(
                "Example Game",
                "Follow the player's requested teaching emphasis.",
                List.of(new TopicDraft(
                        "custom-flow",
                        "行动辨析",
                        "按照用户目标解释本页可验证的行动。",
                        true,
                        true,
                        List.of(
                                "VISIBLE TERM",
                                "A complete page-scoped factual observation supports the requested lesson."),
                        List.of("setup", "core_loop", "end", "scoring", "source_coverage"),
                        List.of(1)))));
        when(publication.publish(any(TeachingPlan.class), eq("Example Game")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                catalog,
                visualCataloger,
                outlines,
                invocations,
                new TeachingPlanFactory(),
                repository,
                publication);

        TeachingPlan plan = service.create(documentVersionId, learningGoal, "alice", null);

        assertThat(plan.learningGoal()).isEqualTo(learningGoal);
        ArgumentCaptor<OutlineRequest> request = ArgumentCaptor.forClass(OutlineRequest.class);
        verify(outlines).organize(request.capture());
        assertThat(request.getValue().pageImages()).isEmpty();
    }

    @Test
    void invalidSourceDependencyCannotBeReplacedByALowerQualityFallback() {
        UUID documentVersionId = UUID.randomUUID();
        List<PageView> visualPages = List.of(new PageView(1, "", 0));
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        AuditedAgentInvocations invocations = mock(AuditedAgentInvocations.class);
        TeachingPlanRepository repository = mock(TeachingPlanRepository.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Example Game")));
        when(documents.pages(documentVersionId)).thenReturn(visualPages);
        when(visualCataloger.catalogVisualPages(
                        documentVersionId, visualPages, "Example Game", "alice", null))
                .thenReturn(List.of(new PageInput(
                        1,
                        "[Visual page catalog; verify against page image]\n"
                                + "Printed terms: PLAY A CARD; First Session Booklet\n"
                                + "Visible facts: 当前玩家打出一张牌并执行行动。\nKeywords: action",
                        List.of(new SourceDependency("First Session Booklet", List.of("setup"))))));
        com.rulepilot.teaching.TeachingOutlineModel outlines = new com.rulepilot.teaching.TeachingOutlineModel() {
            @Override
            public OutlineDraft organize(OutlineRequest request) {
                return outline(List.of(topic(
                        "overconfident",
                        List.of("setup", "core_loop", "end", "scoring", "source_coverage"),
                        List.of(1))));
            }
        };
        when(publication.publish(any(TeachingPlan.class), eq("Example Game")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                catalog,
                visualCataloger,
                outlines,
                invocations,
                new TeachingPlanFactory(),
                repository,
                publication);

        assertThatThrownBy(() -> service.create(documentVersionId, "Teach this safely", "alice", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source-bound teaching outline")
                .hasMessageContaining("retry preparation");
        verifyNoInteractions(publication);
    }

    @Test
    void completeSourceLedgerStillCannotPublishWithoutSemanticWholeGameUnderstanding() {
        UUID documentVersionId = UUID.randomUUID();
        List<PageView> visualPages = List.of(new PageView(1, "", 0));
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        AuditedAgentInvocations invocations = mock(AuditedAgentInvocations.class);
        TeachingPlanRepository repository = mock(TeachingPlanRepository.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Example Game")));
        when(documents.pages(documentVersionId)).thenReturn(visualPages);
        when(visualCataloger.catalogVisualPages(
                        documentVersionId, visualPages, "Example Game", "alice", null))
                .thenReturn(List.of(visualPage(
                        1,
                        "GROUP A; GROUP B; GROUP C; GROUP D; GROUP E",
                        "Five independent source relations are visible on this page.")));
        com.rulepilot.teaching.TeachingOutlineModel outlines = new com.rulepilot.teaching.TeachingOutlineModel() {
            @Override
            public OutlineDraft organize(OutlineRequest request) {
                return new OutlineDraft(
                        "Example Game",
                        "An incomplete semantic grouping.",
                        List.of(new TopicDraft(
                                "shortened",
                                "Shortened",
                                "The page is owned, but its final group is missing.",
                                true,
                                true,
                                List.of("GROUP A", "GROUP B", "GROUP C", "GROUP D"),
                                List.of("setup", "core_loop", "end", "scoring", "source_coverage"),
                                List.of(1))));
            }
        };
        when(publication.publish(any(TeachingPlan.class), eq("Example Game")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                catalog,
                visualCataloger,
                outlines,
                invocations,
                new TeachingPlanFactory(),
                repository,
                publication);

        assertThatThrownBy(() -> service.create(
                        documentVersionId, "Teach every visible group", "alice", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whole-game understanding");
        verifyNoInteractions(publication);
    }

    @Test
    void modelSchemaFailureDoesNotEnterASourceLedgerFallbackThatCannotSatisfyTheSemanticContract() {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        List<PageView> visualPages = IntStream.rangeClosed(1, 24)
                .mapToObj(page -> new PageView(page, "", 0))
                .toList();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        AuditedAgentInvocations invocations = new ImmediateAuditedAgentInvocations();
        TeachingPlanRepository repository = mock(TeachingPlanRepository.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Opaque Rulebook")));
        when(documents.pages(documentVersionId)).thenReturn(visualPages);
        List<PageInput> completeLedger = IntStream.rangeClosed(1, 24)
                .mapToObj(page -> completeVisualPage(
                        page,
                        "OPAQUE GROUP " + page,
                        "A page-owned rule relation numbered " + page + " is directly visible.",
                        List.of()))
                .toList();
        when(visualCataloger.catalogVisualPages(
                        documentVersionId, visualPages, "Opaque Rulebook", "alice", assistantRunId))
                .thenReturn(completeLedger);
        com.rulepilot.teaching.TeachingOutlineModel outlines = mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        when(outlines.organize(any(), any())).thenThrow(new OutlineGenerationException(
                "teaching outline generation returned no valid outline",
                new IllegalArgumentException("teaching outline topic is invalid")));
        when(publication.publish(any(TeachingPlan.class), eq("Opaque Rulebook")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                catalog,
                visualCataloger,
                outlines,
                invocations,
                new TeachingPlanFactory(),
                repository,
                publication);

        assertThatThrownBy(() -> service.create(
                        documentVersionId, "Teach the complete source without guessing", "alice", assistantRunId))
                .isInstanceOf(OutlineGenerationException.class)
                .hasRootCauseMessage("teaching outline topic is invalid");
        verifyNoInteractions(publication);
    }

    @Test
    void modelSchemaFailureCannotUseTheVisualFallbackForAnIncompleteLedger() {
        UUID documentVersionId = UUID.randomUUID();
        List<PageView> visualPages = List.of(new PageView(1, "", 0));
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        com.rulepilot.teaching.TeachingOutlineModel outlines = mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Opaque Rulebook")));
        when(documents.pages(documentVersionId)).thenReturn(visualPages);
        when(visualCataloger.catalogVisualPages(
                        documentVersionId, visualPages, "Opaque Rulebook", "alice", null))
                .thenReturn(List.of(new PageInput(
                        1,
                        TeachingOutlineRevisionPolicy.VISUAL_CATALOG_PREFIX
                                + "\nPrinted terms: OPAQUE GROUP"
                                + "\nVisible facts: A page-local observation exists without its exact identifier."
                                + "\nKeywords: opaque",
                        List.of(),
                        List.of("OPAQUE GROUP"),
                        false)));
        when(outlines.organize(any())).thenThrow(new OutlineGenerationException(
                "teaching outline generation returned no valid outline",
                new IllegalArgumentException("teaching outline topic is invalid")));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                requested -> Optional.empty(),
                visualCataloger,
                outlines,
                mock(AuditedAgentInvocations.class),
                new TeachingPlanFactory(),
                mock(TeachingPlanRepository.class),
                publication);

        assertThatThrownBy(() -> service.create(
                        documentVersionId, "Teach this source", "alice", null))
                .isInstanceOf(OutlineGenerationException.class)
                .hasRootCauseMessage("teaching outline topic is invalid");
        verifyNoInteractions(publication);
    }

    @Test
    void modelSchemaFailureCannotUseTheVisualFallbackForATextRulebook() {
        UUID documentVersionId = UUID.randomUUID();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        com.rulepilot.teaching.TeachingOutlineModel outlines = mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Opaque Rulebook")));
        when(documents.pages(documentVersionId)).thenReturn(List.of(page(
                1, "A complete extracted source paragraph with enough text to teach from directly.")));
        when(outlines.organize(any())).thenThrow(new OutlineGenerationException(
                "teaching outline generation returned no valid outline",
                new IllegalArgumentException("teaching outline topic is invalid")));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                requested -> Optional.empty(),
                mock(VisualRulebookCataloger.class),
                outlines,
                mock(AuditedAgentInvocations.class),
                new TeachingPlanFactory(),
                mock(TeachingPlanRepository.class),
                publication);

        assertThatThrownBy(() -> service.create(documentVersionId, null, "alice", null))
                .isInstanceOf(OutlineGenerationException.class)
                .hasRootCauseMessage("teaching outline topic is invalid");
        verifyNoInteractions(publication);
    }

    @Test
    void executionCancellationCannotUseTheCompleteVisualLedgerFallback() {
        UUID documentVersionId = UUID.randomUUID();
        List<PageView> visualPages = List.of(new PageView(1, "", 0));
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        com.rulepilot.teaching.TeachingOutlineModel outlines = mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Opaque Rulebook")));
        when(documents.pages(documentVersionId)).thenReturn(visualPages);
        when(visualCataloger.catalogVisualPages(
                        documentVersionId, visualPages, "Opaque Rulebook", "alice", null))
                .thenReturn(List.of(completeVisualPage(
                        1,
                        "OPAQUE GROUP",
                        "A complete page-owned relation is directly visible.",
                        List.of())));
        when(outlines.organize(any())).thenThrow(new AgentExecutionStoppedException(StopReason.CANCELLED));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                requested -> Optional.empty(),
                visualCataloger,
                outlines,
                mock(AuditedAgentInvocations.class),
                new TeachingPlanFactory(),
                mock(TeachingPlanRepository.class),
                publication);

        assertThatThrownBy(() -> service.create(
                        documentVersionId, "Teach the complete source", "alice", null))
                .isInstanceOfSatisfying(AgentExecutionStoppedException.class,
                        stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.CANCELLED));
        verifyNoInteractions(publication);
    }

    @Test
    void ownershipRefinementBudgetStopCannotPublishTheRetainedOutline() {
        UUID documentVersionId = UUID.randomUUID();
        UUID assistantRunId = UUID.randomUUID();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        com.rulepilot.teaching.TeachingOutlineModel outlines =
                mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        OutlineDraft overlapping = sourceBoundOutline(
                "Example Game",
                "Teach the complete game in dependency order.",
                List.of(
                        topic("overview", List.of("setup", "core_loop", "end", "scoring"), List.of(1)),
                        topic("early-owner", List.of("setup", "core_loop"), List.of(2)),
                        topic("late-owner", List.of("end", "scoring"), List.of(3))));
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Example Game")));
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                page(1, "Overview source content with enough opaque text for structural admission."),
                page(2, "Early source content with enough opaque text for structural admission."),
                page(3, "Late source content with enough opaque text for structural admission.")));
        when(outlines.organize(
                        any(),
                        any(com.rulepilot.teaching.TeachingOutlineModel.ModelCallExecutor.class)))
                .thenReturn(overlapping);
        when(outlines.refineChapterOwnership(
                        any(),
                        any(),
                        any(),
                        any(com.rulepilot.teaching.TeachingOutlineModel.ModelCallExecutor.class)))
                .thenThrow(new AgentExecutionStoppedException(StopReason.TOKEN_BUDGET));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                mock(CatalogEditionLookup.class),
                mock(VisualRulebookCataloger.class),
                outlines,
                mock(AuditedAgentInvocations.class),
                new TeachingPlanFactory(),
                mock(TeachingPlanRepository.class),
                publication);

        assertThatThrownBy(() -> service.create(
                        documentVersionId, "Teach every rule", "alice", assistantRunId))
                .isInstanceOfSatisfying(
                        AgentExecutionStoppedException.class,
                        stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.TOKEN_BUDGET));
        verifyNoInteractions(publication);
    }

    @Test
    void planIdentityUsesTheBoundCatalogGameRatherThanThePdfFilename() {
        UUID editionId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        CatalogEditionLookup catalog = requested -> Optional.of(new CatalogEditionLookup.EditionReference(
                editionId, gameId, "花砖物语", "中文版", "zh-CN", Set.of()));
        VersionScope scope = new VersionScope(
                UUID.randomUUID(), editionId, "READY", "alice", "azul_rules_cn_final.pdf");

        assertThat(TeachingPlanService.playerGameTitle(scope, catalog)).isEqualTo("花砖物语");
        assertThat(TeachingPlanService.withGameTitle(
                        TeachingPlanService.boundCatalogGameTitle(scope, catalog).orElseThrow(),
                        outline(List.of(topic("setup", List.of("setup"), List.of(1))))).gameTitle())
                .isEqualTo("花砖物语");
    }

    @Test
    void unboundDocumentKeepsItsPlayerVisibleTitle() {
        CatalogEditionLookup catalog = ignored -> Optional.empty();
        VersionScope scope = new VersionScope(
                UUID.randomUUID(), null, "READY", "alice", "A home-made prototype");

        assertThat(TeachingPlanService.playerGameTitle(scope, catalog)).isEqualTo("A home-made prototype");
        assertThat(TeachingPlanService.boundCatalogGameTitle(scope, catalog)).isEmpty();
    }

    @Test
    void unboundPlanUsesTheSourceConfirmedGameIdentityInsteadOfTheUploadLabel() {
        OutlineDraft inferred = TeachingPlanService.withGameTitle(
                "Harbor Nova", outline(List.of(topic("setup", List.of("setup"), List.of(1)))));

        OutlineDraft selected = TeachingPlanService.preferDocumentTitle(
                "Harbor Nova rulebook EN v4 12pages",
                inferred,
                List.of(
                        new PageInput(1, "Harbor Nova - Original Demonstration Rules"),
                        new PageInput(2, "SETUP")));

        assertThat(selected.gameTitle()).isEqualTo("Harbor Nova");
        assertThat(selected.topics()).isEqualTo(inferred.topics());

        OutlineDraft headingShapedInference = TeachingPlanService.withGameTitle(
                "Harbor Nova - Original Demonstration Rules",
                outline(List.of(topic("setup", List.of("setup"), List.of(1)))));
        OutlineDraft triangulated = TeachingPlanService.preferDocumentTitle(
                "Harbor Nova rulebook EN v4 12pages",
                headingShapedInference,
                List.of(new PageInput(
                        1,
                        "Harbor Nova - Original Demonstration Rules\nHARBOR NOVA\nOBJECTIVE")));

        assertThat(triangulated.gameTitle()).isEqualTo("Harbor Nova");
        assertThat(triangulated.topics()).isEqualTo(headingShapedInference.topics());
    }

    @Test
    void pageLengthNeverTriggersASecondOutlineModelCall() {
        assertThat(TeachingPlanService.requiresModelSourcePageCoverageRevision(true)).isFalse();
        assertThat(TeachingPlanService.requiresModelSourcePageCoverageRevision(false)).isFalse();
    }

    @Test
    void keepsAValidSourceBoundOutlineWithoutAPageLengthDrivenRewrite() {
        UUID documentVersionId = UUID.randomUUID();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        com.rulepilot.teaching.TeachingOutlineModel outlines =
                mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        AuditedAgentInvocations invocations = mock(AuditedAgentInvocations.class);
        TeachingPlanRepository repository = mock(TeachingPlanRepository.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        OutlineDraft complete = sourceBoundOutline(
                "Example Game",
                "Teach the complete game in dependency order.",
                List.of(new TopicDraft(
                        "complete",
                        "完整讲解",
                        "覆盖所有核心学习义务。",
                        true,
                        false,
                        List.of("SOURCE TERM"),
                        List.of("setup", "core_loop", "end", "scoring"),
                        List.of(1))));
        OutlineDraft invalidRewrite = new OutlineDraft(
                "Example Game",
                "Incomplete rewrite",
                List.of(topic("partial", List.of("source_coverage"), List.of(2))));
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Example Game")));
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                page(1, "SOURCE TERM. A complete first source page with enough opaque text for structural admission."),
                page(2, "A complete second source page with enough opaque text for structural admission.")));
        when(outlines.organize(any())).thenReturn(complete);
        when(outlines.refineChapterOwnership(any(), any(), any())).thenReturn(invalidRewrite);
        when(publication.publish(any(TeachingPlan.class), eq("Example Game")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                catalog,
                visualCataloger,
                outlines,
                invocations,
                new TeachingPlanFactory(),
                repository,
                publication);

        TeachingPlan plan = service.create(documentVersionId, null, "alice", null);

        assertThat(plan.sections()).singleElement().satisfies(section -> {
            assertThat(section.topicKey()).isEqualTo("complete");
            assertThat(section.coverageTags()).contains("setup", "core_loop", "end", "scoring");
        });
        verify(outlines, never()).refineChapterOwnership(any(), any(), any());
    }

    @Test
    void rejectsAnIncompleteTextOutlineInsteadOfPublishingTheGenericFourChapterFallback() {
        UUID documentVersionId = UUID.randomUUID();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        VisualRulebookCataloger visualCataloger = mock(VisualRulebookCataloger.class);
        com.rulepilot.teaching.TeachingOutlineModel outlines =
                mock(com.rulepilot.teaching.TeachingOutlineModel.class);
        TeachingPlanPublication publication = mock(TeachingPlanPublication.class);
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(new VersionScope(
                documentVersionId, null, "READY", "alice", "Example Game")));
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                page(1, "A complete source page with enough opaque text for structural admission.")));
        when(outlines.organize(any())).thenReturn(new OutlineDraft(
                "Example Game",
                "Incomplete outline",
                List.of(topic("partial", List.of("core_loop"), List.of(1)))));
        TeachingPlanService service = new TeachingPlanService(
                documents,
                scopes,
                catalog,
                visualCataloger,
                outlines,
                mock(AuditedAgentInvocations.class),
                new TeachingPlanFactory(),
                mock(TeachingPlanRepository.class),
                publication);

        assertThatThrownBy(() -> service.create(documentVersionId, null, "alice", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whole-game understanding")
                .hasMessageContaining("retry preparation");
        verifyNoInteractions(publication);
    }

    @Test
    void slowVisualCatalogWorkIsCancelledAtTheApplicationTimeout() throws InterruptedException {
        FutureTask<VisualRulebookPageCatalogModel.CatalogDraft> slow = new FutureTask<>(() -> {
            Thread.sleep(5_000);
            return new VisualRulebookPageCatalogModel.CatalogDraft(List.of());
        });
        Thread worker = new Thread(slow, "slow-visual-catalog-test");
        worker.start();

        assertThatThrownBy(() -> VisualRulebookCataloger.awaitCatalog(slow, Duration.ofMillis(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
        worker.join(250);
        assertThat(slow.isCancelled()).isTrue();
        assertThat(worker.isAlive()).isFalse();
    }

    @Test
    void visualInterpretationUsesExplicitPlannerBindingsInOrderAndHonorsTheBudget() {
        OutlineDraft outline = outline(List.of(
                topic("text", List.of("core_loop"), List.of(1)),
                visualTopic("visual-a", List.of("setup"), List.of(5, 6, 7)),
                visualTopic("visual-b", List.of("end", "scoring"), List.of(7, 8, 9)),
                topic("tail", List.of("source_coverage"), List.of(10))));
        List<PageView> pages = IntStream.rangeClosed(1, 10)
                .mapToObj(page -> page(page, "opaque page " + page))
                .toList();

        assertThat(VisualOutlineEvidencePolicy.selectedVisualPageNumbers(outline, pages))
                .containsExactly(5, 6, 7, 8);
    }

    @Test
    void sparseCoverageSamplingUsesOwnershipAndDensityNotPageVocabulary() {
        OutlineDraft outline = outline(List.of(topic("owned", List.of("core_loop"), List.of(2))));
        List<PageView> pages = List.of(
                page(1, "cover setup winner credits"),
                page(2, "owned"),
                page(3, "x".repeat(400)),
                page(4, "arbitrary sparse ledger"),
                page(5, "another arbitrary sparse ledger"),
                page(6, "final sparse ledger"));

        assertThat(VisualOutlineEvidencePolicy.unownedSparseVisualCoveragePageNumbers(outline, pages, 3))
                .containsExactly(1, 5, 6);
    }

    @Test
    void chapterOwnershipIsNotRewrittenByJavaKeywordComparisons() {
        OutlineDraft outline = outline(List.of(
                detailedTopic("alpha", "回合流程与终局", "Contains setup, winner, cleanup, and scoring words."),
                detailedTopic("beta", "下一章", "Contains the same words in another language.")));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline)).isEmpty();
    }

    @Test
    void requestsSemanticOwnershipReviewWhenOneTopicStructurallyClaimsFourLaterDimensions() {
        OutlineDraft outline = outline(List.of(
                topic("overview", List.of("axis_one", "axis_two", "axis_three", "axis_four"), List.of(1)),
                topic("procedure-a", List.of("axis_one", "axis_two"), List.of(3)),
                topic("procedure-b", List.of("axis_three", "axis_four"), List.of(8))));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("topic=overview", "axis_one", "axis_three", "procedure-a", "procedure-b")
                        .contains("dependency order", "one primary teaching home"));
    }

    @Test
    void appliesTheSameOwnershipInvariantToDifferentOpaqueTopicShapes() {
        OutlineDraft outline = outline(List.of(
                topic("hub", List.of("red", "green", "blue", "violet"), List.of(9)),
                topic("left", List.of("red", "green"), List.of(2)),
                topic("right", List.of("blue", "violet"), List.of(4)),
                topic("unrelated", List.of("amber"), List.of(6))));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("topic=hub", "red", "green", "blue", "violet"));
    }

    @Test
    void doesNotRequestOwnershipReviewForOneSharedDimensionOrAnUncontestedBroadTopic() {
        OutlineDraft oneSharedDimension = outline(List.of(
                topic("first", List.of("shared"), List.of(1)),
                topic("second", List.of("shared"), List.of(2))));
        OutlineDraft uncontested = outline(List.of(
                topic("only", List.of("one", "two", "three", "four"), List.of(1))));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(oneSharedDimension)).isEmpty();
        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(uncontested)).isEmpty();
    }

    @Test
    void doesNotTreatThreeOrthogonalMetadataTagsAsBroadChapterOwnership() {
        OutlineDraft outline = outline(List.of(
                topic("reference", List.of("goal", "flow", "visual"), List.of(1)),
                topic("goal-owner", List.of("goal"), List.of(2)),
                topic("flow-owner", List.of("flow"), List.of(3)),
                topic("visual-owner", List.of("visual"), List.of(4))));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline)).isEmpty();
    }

    @Test
    void sourceCoverageRevisionListsOnlyUnboundStructurallySubstantivePages() {
        OutlineDraft outline = outline(List.of(topic("owned", List.of("core_loop"), List.of(1))));
        List<PageInput> pages = List.of(
                new PageInput(1, "A".repeat(80)),
                new PageInput(2, "Opaque source content ".repeat(4)),
                new PageInput(3, "short"));

        assertThat(TeachingOutlineRevisionPolicy.sourcePageCoverageRevisionFeedback(outline, pages))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("Page 2:", "Opaque source content")
                        .doesNotContain("Page 1:", "Page 3:"));
    }

    @Test
    void visualSourceDependencyValidationRejectsOmissionAndInventedResponsibility() {
        var pages = List.of(new PageInput(
                1,
                "[Visual page catalog; verify against page image]\nPrinted terms: PLAY\n"
                        + "Visible facts: A directly visible play rule with enough detail.\nKeywords: play",
                List.of(new SourceDependency("First Session Booklet", List.of("setup")))));
        OutlineDraft omitted = outline(List.of(topic(
                "rules", List.of("setup", "core_loop", "end", "scoring"), List.of(1))));

        assertThatThrownBy(() -> VisualOutlineEvidencePolicy.validateVisualSourceDependencies(omitted, pages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("First Session Booklet");

        OutlineDraft invented = outline(List.of(
                topic("rules", List.of("core_loop", "end", "scoring"), List.of(1)),
                new TopicDraft(
                        "missing-source",
                        "Missing source",
                        "Name only the unavailable source.",
                        true,
                        false,
                        List.of("First Session Booklet"),
                        List.of("source_dependency", "missing_setup_source", "missing_scoring_source"),
                        List.of(1))));
        assertThatThrownBy(() -> VisualOutlineEvidencePolicy.validateVisualSourceDependencies(invented, pages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invented");

        OutlineDraft exact = outline(List.of(
                topic("rules", List.of("core_loop", "end", "scoring"), List.of(1)),
                new TopicDraft(
                        "missing-source",
                        "Missing source",
                        "Name only the unavailable source.",
                        true,
                        false,
                        List.of("First Session Booklet"),
                        List.of("source_dependency", "missing_setup_source"),
                        List.of(1))));
        VisualOutlineEvidencePolicy.validateVisualSourceDependencies(exact, pages);
    }

    @Test
    void visualPlanningUsesTheActualSixteenChapterResourceBudgetInsteadOfATenChapterFastPath() {
        List<TopicDraft> eleven = new ArrayList<>();
        IntStream.rangeClosed(1, 11)
                .forEach(index -> eleven.add(topic("topic-" + index, List.of("source_coverage"), List.of(index))));
        OutlineDraft expanded = outline(eleven);

        new TeachingPlanFactory().validate(expanded);

        List<TopicDraft> tenRulesAndOneDependency = new ArrayList<>(eleven.subList(0, 10));
        tenRulesAndOneDependency.add(topic(
                "missing-source", List.of("source_dependency", "missing_setup_source"), List.of(1)));
        OutlineDraft boundedWithDependency = outline(tenRulesAndOneDependency);
        new TeachingPlanFactory().validate(boundedWithDependency);
    }

    private OutlineDraft outline(List<TopicDraft> topics) {
        return new OutlineDraft("Game", "Premise", topics);
    }

    private OutlineDraft sourceBoundOutline(
            String gameTitle,
            String premise,
            List<TopicDraft> topics) {
        List<SourceCoverageSlotDraft> slots = new ArrayList<>();
        List<GlobalConceptDraft> concepts = new ArrayList<>();
        List<TopicDependencyDraft> dependencies = new ArrayList<>();
        int slotNumber = 1;
        for (int topicIndex = 0; topicIndex < topics.size(); topicIndex++) {
            TopicDraft topic = topics.get(topicIndex);
            String teachingUnitId = "test-unit-" + (topicIndex + 1);
            for (String identifier : topic.retrievalQueries()) {
                slots.add(new SourceCoverageSlotDraft(
                        "test-slot-" + slotNumber++,
                        SourceCoverageRole.SUPPORTING_RULE,
                        identifier,
                        topic.sourcePageNumbers(),
                        topic.key(),
                        teachingUnitId,
                        SourceCoverageAvailability.SOURCED));
            }
            concepts.add(new GlobalConceptDraft(
                    "test-concept-" + (topicIndex + 1),
                    topic.title(),
                    topic.objective(),
                    topic.retrievalQueries(),
                    topic.sourcePageNumbers(),
                    List.of(topic.key()),
                    topicIndex == 0 ? List.of() : List.of("test-concept-" + topicIndex)));
            if (topicIndex > 0) {
                dependencies.add(new TopicDependencyDraft(
                        topics.get(topicIndex - 1).key(),
                        topic.key(),
                        "The Agent placed this chapter after its preceding concept."));
            }
        }
        return new OutlineDraft(
                gameTitle,
                premise,
                topics,
                slots,
                true,
                new WholeGameUnderstandingDraft(premise, concepts, dependencies));
    }

    private TopicDraft topic(String key, List<String> tags, List<Integer> pages) {
        return new TopicDraft(key, key, "Teach " + key, true, false, List.of(key), tags, pages);
    }

    private TopicDraft visualTopic(String key, List<String> tags, List<Integer> pages) {
        return new TopicDraft(key, key, "Teach " + key, true, true, List.of(key), tags, pages);
    }

    private TopicDraft detailedTopic(String key, String title, String objective) {
        return new TopicDraft(
                key, title, objective, true, false, List.of("opaque query"), List.of("core_loop"), List.of(1));
    }

    private PageInput visualPage(int number, String terms, String facts) {
        return new PageInput(
                number,
                TeachingOutlineRevisionPolicy.VISUAL_CATALOG_PREFIX
                        + "\nPrinted terms: " + terms
                        + "\nVisible facts: " + facts
                        + "\nKeywords: opaque");
    }

    private PageInput completeVisualPage(
            int number, String identifier, String fact, List<SourceDependency> dependencies) {
        return new PageInput(
                number,
                TeachingOutlineRevisionPolicy.VISUAL_CATALOG_PREFIX
                        + "\nPrinted terms: " + identifier
                        + "\nVisible facts: " + identifier + ": " + fact
                        + "\nKeywords: opaque",
                dependencies,
                List.of(identifier),
                true,
                List.of(new RuleGroupFact(identifier, identifier, fact)));
    }

    private PageView page(int number, String text) {
        return new PageView(number, text, text.length());
    }
}
