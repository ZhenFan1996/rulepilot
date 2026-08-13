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
import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageSketch;
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
    void publishesACompleteProgressiveVisualPlanWithoutWaitingForTheLegacyOutlineModel() {
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
        when(visualCataloger.progressiveTeachingStart(
                        documentVersionId, visualPages, "example_rules_en.pdf", "alice", null))
                .thenReturn(Optional.of(progressiveStart()));
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
        assertThat(plan.sections()).extracting(section -> section.sourcePageNumbers().getFirst())
                .containsExactly(3, 2, 4);
        assertThat(plan.sections()).allMatch(section ->
                section.topicKey().startsWith("progressive-visual-page-"));
        verify(publication).publish(any(TeachingPlan.class), eq("Example Game"));
        verifyNoInteractions(outlines);
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
        when(outlines.organize(any())).thenReturn(new OutlineDraft(
                "Example Game",
                "Follow the player's requested teaching emphasis.",
                List.of(new TopicDraft(
                        "custom-flow",
                        "行动辨析",
                        "按照用户目标解释本页可验证的行动。",
                        true,
                        true,
                        List.of("VISIBLE TERM"),
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
        verify(visualCataloger, never()).progressiveTeachingStart(any(), any(), any(), any(), any());
        ArgumentCaptor<OutlineRequest> request = ArgumentCaptor.forClass(OutlineRequest.class);
        verify(outlines).organize(request.capture());
        assertThat(request.getValue().pageImages()).isEmpty();
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
    void visualOnlyCoverageIsStructuralWhileTextCoverageGetsOneModelRevisionOpportunity() {
        assertThat(TeachingPlanService.requiresModelSourcePageCoverageRevision(true)).isFalse();
        assertThat(TeachingPlanService.requiresModelSourcePageCoverageRevision(false)).isTrue();
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
    void visualCoverageAdmissionDoesNotClassifyCoversOrEndingsByWords() {
        List<PageInput> pages = List.of(
                visualPage(1, "COVER", "A non-trivial factual ledger describing visible identity artwork."),
                visualPage(2, "QXZ", "A non-trivial factual ledger describing an unfamiliar operation."));
        OutlineDraft onlySecond = outline(List.of(topic("opaque", List.of("core_loop"), List.of(2))));

        assertThatThrownBy(() -> VisualOutlineEvidencePolicy.validateVisualRulebookCoverage(onlySecond, pages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[1]");

        OutlineDraft both = outline(List.of(topic("opaque", List.of("core_loop"), List.of(1, 2))));
        VisualOutlineEvidencePolicy.validateVisualRulebookCoverage(both, pages);
    }

    @Test
    void visualCoreValidationUsesStructuredTagsAndKnownPageBindingsOnly() {
        List<PageInput> pages = List.of(
                visualPage(1, "A", "A complete first page factual observation ledger."),
                visualPage(2, "B", "A complete second page factual observation ledger."));
        OutlineDraft complete = outline(List.of(
                topic("s", List.of("setup"), List.of(1)),
                topic("c", List.of("core_loop"), List.of(1)),
                topic("e", List.of("end"), List.of(2)),
                topic("p", List.of("scoring"), List.of(2))));

        VisualOutlineEvidencePolicy.validateVisualCoreTopicBindings(complete, pages);

        OutlineDraft missingScoring = outline(complete.topics().subList(0, 3));
        assertThatThrownBy(() -> VisualOutlineEvidencePolicy.validateVisualCoreTopicBindings(missingScoring, pages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scoring");
    }

    @Test
    void coverageAugmentationPreservesModelTopicsAndEveryMissingPage() {
        OutlineDraft model = outline(List.of(new TopicDraft(
                "actions",
                "Model chapter",
                "Model objective",
                true,
                true,
                List.of("model query"),
                List.of("core_loop"),
                List.of(1, 2, 3, 4, 5))));
        OutlineDraft source = outline(List.of(new TopicDraft(
                "actions",
                "Source chapter",
                "Source objective",
                true,
                true,
                List.of("source query"),
                List.of("core_loop"),
                List.of(5, 6, 7, 8, 9))));

        OutlineDraft augmented = VisualOutlineEvidencePolicy.augmentVisualCoverage(model, source);

        assertThat(augmented.topics()).hasSize(2);
        assertThat(augmented.topics().getFirst().key()).isEqualTo("actions");
        assertThat(augmented.topics().getFirst().sourcePageNumbers()).containsExactly(1, 2, 3, 4, 5);
        assertThat(augmented.topics().get(1).sourcePageNumbers()).containsExactly(6, 7, 8, 9);
        assertThat(augmented.topics()).flatExtracting(TopicDraft::sourcePageNumbers)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void coverageAugmentationMergesIntoAvailableCapacityAndUnionsStructuralMetadata() {
        OutlineDraft model = outline(List.of(new TopicDraft(
                "actions", "Model", "Model objective", true, true,
                List.of("model query"), List.of("core_loop"), List.of(1, 2, 3))));
        OutlineDraft source = outline(List.of(new TopicDraft(
                "actions", "Source", "Source objective", true, false,
                List.of("source query"), List.of("actions"), List.of(3, 4, 5))));

        OutlineDraft augmented = VisualOutlineEvidencePolicy.augmentVisualCoverage(model, source);

        assertThat(augmented.topics()).singleElement().satisfies(topic -> {
            assertThat(topic.sourcePageNumbers()).containsExactly(1, 2, 3, 4, 5);
            assertThat(topic.retrievalQueries()).containsExactly("model query", "source query");
            assertThat(topic.coverageTags()).containsExactly("core_loop", "actions");
        });
    }

    @Test
    void oversizedVisualOutlineFallsBackToTheCompactSourceBaseline() {
        List<TopicDraft> eleven = new ArrayList<>();
        IntStream.rangeClosed(1, 11)
                .forEach(index -> eleven.add(topic("topic-" + index, List.of("source_coverage"), List.of(index))));
        OutlineDraft expanded = outline(eleven);
        OutlineDraft baseline = outline(List.of(topic("baseline", List.of("core_loop"), List.of(1))));

        assertThatThrownBy(() -> VisualOutlineEvidencePolicy.validateVisualFastBaseline(expanded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ten-section");
        assertThat(VisualOutlineEvidencePolicy.keepFastVisualBaseline(expanded, baseline)).isSameAs(baseline);
    }

    private OutlineDraft outline(List<TopicDraft> topics) {
        return new OutlineDraft("Game", "Premise", topics);
    }

    private ProgressiveTeachingStartDraft progressiveStart() {
        return new ProgressiveTeachingStartDraft(
                List.of(
                        new TeachingPageSketch(
                                1, TeachingPageRole.NON_GAMEPLAY, "Example Game", List.of(), List.of()),
                        new TeachingPageSketch(
                                2, TeachingPageRole.GAMEPLAY_RULES, "Setup", List.of("market"), List.of("setup")),
                        new TeachingPageSketch(
                                3, TeachingPageRole.GAMEPLAY_RULES, "Turn", List.of("take cards"), List.of("core_loop")),
                        new TeachingPageSketch(
                                4,
                                TeachingPageRole.GAMEPLAY_RULES,
                                "Game end and scoring",
                                List.of("game end", "score"),
                                List.of("end", "scoring"))),
                new PageSummary(
                        3,
                        "TAKE CARDS",
                        "当前玩家从市场拿取可见卡牌，然后按照页面所示顺序结束本回合。",
                        List.of("TAKE CARDS", "market")));
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

    private PageView page(int number, String text) {
        return new PageView(number, text, text.length());
    }
}
