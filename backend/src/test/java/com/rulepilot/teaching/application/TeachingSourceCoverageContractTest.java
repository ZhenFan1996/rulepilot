package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.rulepilot.teaching.VisualRuleGroupTestFacts.facts;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckType;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingSourceCoverageContractTest {

    @Test
    void completeOpaqueTextContractKeepsEveryActionAndNecessaryExceptionAsARequiredSourcedSlot() {
        List<PageInput> pages = List.of(
                new PageInput(1, "Z-01 gives every participant the visible starting state."),
                new PageInput(2, "T-alpha advances the cycle. A-lambda and A-mu are separate legal choices. "
                        + "E-9 changes the A-mu procedure in its stated condition."),
                new PageInput(3, "F-omega ends the session. S-pi determines the final result."));
        OutlineDraft outline = completeOutline();

        TeachingSourceCoverageContract.validateAgainstSources(new OutlineRequest(pages), outline);
        TeachingPlan plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", outline);

        assertThat(plan.sections()).allSatisfy(section -> assertThat(section.coverageTags())
                .contains(TeachingSourceCoverageContract.CONTRACT_VERSION_TAG));
        assertThat(plan.sections()).flatExtracting(TeachingPlan.PlannedSection::coverageTags)
                .contains(
                        TeachingSourceCoverageContract.roleTag(SourceCoverageRole.LEGAL_ACTION),
                        TeachingSourceCoverageContract.roleTag(SourceCoverageRole.NECESSARY_EXCEPTION));
        assertThat(new TeachingLessonAssemblyPolicy().status(plan, supportedSections(plan)))
                .isEqualTo(LessonStatus.COMPLETE);
        assertThat(new LessonQualityEvaluator().evaluate(plan, lesson(plan, supportedSections(plan))).checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.PASS);
                    assertThat(check.summary()).contains("6 / 6");
                });
    }

    @Test
    void aLocalizedUnreadVisualPageKeepsTheCitedLessonReadableWithoutClaimingWholeDocumentCoverage() {
        OutlineDraft complete = completeOutline();
        List<TopicDraft> partialCatalogTopics = complete.topics().stream()
                .map(topic -> new TopicDraft(
                        topic.key(),
                        topic.title(),
                        topic.objective(),
                        topic.required(),
                        topic.visualEvidenceRecommended(),
                        topic.retrievalQueries(),
                        java.util.stream.Stream.concat(
                                        topic.coverageTags().stream(),
                                        java.util.stream.Stream.of(
                                                TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG))
                                .distinct()
                                .toList(),
                        topic.sourcePageNumbers()))
                .toList();
        OutlineDraft partialCatalog = new OutlineDraft(
                complete.gameTitle(),
                complete.premise(),
                partialCatalogTopics,
                complete.sourceCoverageSlots(),
                true,
                complete.wholeGameUnderstanding());
        TeachingPlan plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", partialCatalog);
        List<LessonSection> sections = supportedSections(plan);

        assertThat(new TeachingLessonAssemblyPolicy().status(plan, sections))
                .isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(new LessonQualityEvaluator().evaluate(plan, lesson(plan, sections)).checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.NOT_EVALUATED);
                    assertThat(check.detail()).contains(
                            "仍有页面未获得可验证的视觉证据",
                            "不能声称覆盖了整本规则书");
                });
    }

    @Test
    void fourBroadCoverageTagsCannotHideOneOmittedVisualRuleGroup() {
        PageInput visualLedger = new PageInput(
                7,
                "[Visual page catalog; verify against page image]\n"
                        + "Visible facts: S-0: fact.\nT-0: fact.\nA-1: fact.\nA-2: fact.\n"
                        + "F-0: fact.\nP-0: fact.\nE-0: fact.",
                List.of(),
                List.of("S-0", "T-0", "A-1", "A-2", "F-0", "P-0", "E-0"),
                true,
                facts("S-0", "T-0", "A-1", "A-2", "F-0", "P-0", "E-0"));
        TopicDraft everything = topic(
                "all-in-one",
                List.of("S-0", "T-0", "A-1", "F-0", "P-0", "E-0"),
                List.of("setup", "core_loop", "legal_action", "end", "scoring", "necessary_exception"),
                List.of(7));
        OutlineDraft omittedAction = new OutlineDraft(
                "Opaque game",
                "Opaque premise",
                List.of(everything),
                List.of(
                        slot("setup", SourceCoverageRole.SETUP, "S-0", 7, "all-in-one"),
                        slot("flow", SourceCoverageRole.CORE_LOOP, "T-0", 7, "all-in-one"),
                        slot("action-one", SourceCoverageRole.LEGAL_ACTION, "A-1", 7, "all-in-one"),
                        slot("ending", SourceCoverageRole.ENDING, "F-0", 7, "all-in-one"),
                        slot("scoring", SourceCoverageRole.SCORING, "P-0", 7, "all-in-one"),
                        slot("exception", SourceCoverageRole.NECESSARY_EXCEPTION, "E-0", 7, "all-in-one")),
                true);

        assertThatThrownBy(() -> TeachingSourceCoverageContract.validateAgainstSources(
                        new OutlineRequest(List.of(visualLedger)), omittedAction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omitted source rule group")
                .hasMessageContaining("A-2");
    }

    @Test
    void duplicatedSourceSlotOwnershipIsRejectedBeforeChaptersCanContradictEachOther() {
        List<PageInput> pages = List.of(
                new PageInput(1, "S-0 establishes the start."),
                new PageInput(2, "T-0 advances play. A-1 is a legal choice."),
                new PageInput(3, "F-0 ends play and P-0 resolves the result."));
        List<TopicDraft> topics = List.of(
                topic("start", List.of("S-0"), List.of("setup"), List.of(1)),
                topic("flow", List.of("T-0", "A-1"), List.of("core_loop", "legal_action"), List.of(2)),
                topic("duplicate-action", List.of("A-1"), List.of("legal_action"), List.of(2)),
                topic("finish", List.of("F-0", "P-0"), List.of("end", "scoring"), List.of(3)));
        OutlineDraft duplicateOwner = new OutlineDraft(
                "Opaque game",
                "Opaque premise",
                topics,
                List.of(
                        slot("setup", SourceCoverageRole.SETUP, "S-0", 1, "start"),
                        slot("flow", SourceCoverageRole.CORE_LOOP, "T-0", 2, "flow"),
                        slot("action-primary", SourceCoverageRole.LEGAL_ACTION, "A-1", 2, "flow"),
                        slot("action-duplicate", SourceCoverageRole.LEGAL_ACTION, "A-1", 2, "duplicate-action"),
                        slot("ending", SourceCoverageRole.ENDING, "F-0", 3, "finish"),
                        slot("scoring", SourceCoverageRole.SCORING, "P-0", 3, "finish")),
                true);

        assertThatThrownBy(() -> TeachingSourceCoverageContract.validateAgainstSources(
                        new OutlineRequest(pages), duplicateOwner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple chapter owners");
    }

    @Test
    void chapterOrderIsOwnedByThePlanningAgentInsteadOfAFixedLifecycleGate() {
        List<PageInput> pages = List.of(
                new PageInput(1, "S-0 establishes the start."),
                new PageInput(2, "T-0 advances play."),
                new PageInput(3, "F-0 ends play. P-0 resolves the result."),
                new PageInput(4, "A-1 is a legal choice during ordinary play."));
        List<TopicDraft> topics = List.of(
                topic("start", List.of("S-0"), List.of("setup"), List.of(1)),
                topic("flow", List.of("T-0"), List.of("core_loop"), List.of(2)),
                topic("finish", List.of("F-0"), List.of("end"), List.of(3)),
                topic("late-action", List.of("A-1"), List.of("legal_action"), List.of(4)),
                topic("result", List.of("P-0"), List.of("scoring"), List.of(3)));
        OutlineDraft reordered = new OutlineDraft(
                "Opaque game",
                "Opaque premise",
                topics,
                List.of(
                        slot("setup", SourceCoverageRole.SETUP, "S-0", 1, "start"),
                        slot("flow", SourceCoverageRole.CORE_LOOP, "T-0", 2, "flow"),
                        slot("ending", SourceCoverageRole.ENDING, "F-0", 3, "finish"),
                        slot("late-action", SourceCoverageRole.LEGAL_ACTION, "A-1", 4, "late-action"),
                        slot("scoring", SourceCoverageRole.SCORING, "P-0", 3, "result")),
                true);

        TeachingSourceCoverageContract.validateAgainstSources(new OutlineRequest(pages), reordered);

        assertThat(new TeachingPlanFactory().create(UUID.randomUUID(), "player", reordered).sections())
                .extracting(TeachingPlan.PlannedSection::topicKey)
                .containsExactly("start", "flow", "finish", "late-action", "result");
    }

    @Test
    void descriptiveChapterTagsDoNotOverrideTheAgentsSourceUnitOwnership() {
        PageInput page = new PageInput(1, "R-kappa explains the relation selected for this teaching unit.");
        TopicDraft topic = topic("relation", List.of("R-kappa"), List.of("core_loop"), List.of(1));
        OutlineDraft outline = new OutlineDraft(
                "Opaque game",
                "Opaque premise",
                List.of(topic),
                List.of(slot(
                        "relation-source",
                        SourceCoverageRole.SUPPORTING_RULE,
                        "R-kappa",
                        1,
                        "relation")),
                true);

        TeachingSourceCoverageContract.validateAgainstSources(new OutlineRequest(List.of(page)), outline);

        assertThat(new TeachingPlanFactory().create(UUID.randomUUID(), "player", outline).sections())
                .singleElement()
                .satisfies(section -> assertThat(TeachingUnitContract.decodeUnits(section.retrievalQueries()))
                        .singleElement()
                        .satisfies(unit -> assertThat(unit.sourceIdentifiers()).containsExactly("R-kappa")));
    }

    @Test
    void derivesCanonicalRetrievalFromOwnedSlotsInsteadOfRejectingRedundantModelHints() {
        PageInput page = new PageInput(1, "R-kappa explains the complete source-owned relation.");
        TopicDraft topic = topic(
                "relation",
                List.of("broad model search hint", "duplicated presentation phrase"),
                List.of("core_loop"),
                List.of(1));
        OutlineDraft outline = new OutlineDraft(
                "Opaque game",
                "Opaque premise",
                List.of(topic),
                List.of(slot(
                        "relation-source",
                        SourceCoverageRole.SUPPORTING_RULE,
                        "R-kappa",
                        1,
                        "relation")),
                true);

        TeachingSourceCoverageContract.validateAgainstSources(new OutlineRequest(List.of(page)), outline);
        TeachingPlan plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", outline);

        assertThat(plan.sections()).singleElement().satisfies(section ->
                assertThat(TeachingUnitContract.sourceIdentifiers(section.retrievalQueries()))
                        .containsExactly("R-kappa"));
    }

    @Test
    void aValidatedButUnsourcedCoreSlotCanPublishARecoveryPlanButNeverACompleteLesson() {
        PageInput referringPage = new PageInput(
                1,
                "T-0 is visible here; the separate Start Leaflet owns the initial procedure.",
                List.of(new com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency(
                        "Start Leaflet", List.of("setup"))));
        List<TopicDraft> topics = List.of(
                new TopicDraft(
                        "missing-start",
                        "Missing start",
                        "Name the unavailable source without inventing it.",
                        true,
                        false,
                        List.of("Start Leaflet"),
                        List.of("source_dependency", "missing_setup_source"),
                        List.of(1)),
                topic("flow", List.of("T-0"), List.of("core_loop"), List.of(1)),
                topic("ending", List.of("F-0"), List.of("end"), List.of(2)),
                topic("scoring", List.of("P-0"), List.of("scoring"), List.of(2)));
        OutlineDraft outline = new OutlineDraft(
                "Opaque game",
                "Opaque premise",
                topics,
                List.of(
                        new SourceCoverageSlotDraft(
                                "missing-setup",
                                SourceCoverageRole.SETUP,
                                "Start Leaflet",
                                List.of(1),
                                "missing-start",
                                SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE),
                        slot("flow", SourceCoverageRole.CORE_LOOP, "T-0", 1, "flow"),
                        slot("ending", SourceCoverageRole.ENDING, "F-0", 2, "ending"),
                        slot("scoring", SourceCoverageRole.SCORING, "P-0", 2, "scoring")),
                true);
        OutlineRequest request = new OutlineRequest(List.of(
                referringPage,
                new PageInput(2, "F-0 ends play. P-0 resolves the result.")));

        TeachingSourceCoverageContract.validateAgainstSources(request, outline);
        TeachingPlan plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", outline);

        assertThat(new TeachingLessonAssemblyPolicy().status(plan, supportedSections(plan)))
                .isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(new LessonQualityEvaluator().evaluate(plan, lesson(plan, supportedSections(plan))).checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.FAIL);
                    assertThat(check.detail()).contains("没有可用来源");
                });
    }

    @Test
    void aMixedOwnerKeepsItsSourcedProcedureWhileOnlyItsDelegatedVariantIsMarkedUnavailable() {
        String delegation = "Variant tables require the separate Start Leaflet for their additional procedure.";
        PageInput setupPage = new PageInput(
                1,
                "S-0 establishes the supplied start. " + delegation,
                List.of(new com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency(
                        "Start Leaflet", List.of("setup"))));
        TopicDraft setup = topic(
                "start",
                List.of("玩家准备"),
                List.of("source_coverage", "setup"),
                List.of(1));
        TopicDraft flow = topic("flow", List.of("T-0"), List.of("source_coverage", "core_loop"), List.of(2));
        OutlineDraft outline = new OutlineDraft(
                "Opaque game",
                "Opaque premise",
                List.of(setup, flow),
                List.of(
                        slot("supplied-start", SourceCoverageRole.SETUP, "S-0", 1, "start"),
                        new SourceCoverageSlotDraft(
                                "delegated-variant",
                                SourceCoverageRole.SETUP,
                                delegation,
                                List.of(1),
                                "start",
                                SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE),
                        slot("flow", SourceCoverageRole.CORE_LOOP, "T-0", 2, "flow")),
                true);

        TeachingSourceCoverageContract.validateAgainstSources(
                new OutlineRequest(List.of(setupPage, new PageInput(2, "T-0 advances play."))), outline);
        TeachingPlan plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", outline);

        assertThat(TeachingUnitContract.sourceIdentifiers(plan.sections().getFirst().retrievalQueries()))
                .containsExactly("S-0", delegation);
        assertThat(plan.sections().getFirst().coverageTags())
                .contains(TeachingSourceCoverageContract.UNSOURCED_TAG);
        assertThat(plan.sections().get(1).coverageTags())
                .doesNotContain(TeachingSourceCoverageContract.UNSOURCED_TAG);
    }

    @Test
    void anUnresolvedActionInventoryRemainsIncompleteEvenWhenEveryDraftChapterIsSupported() {
        OutlineDraft complete = completeOutline();
        List<SourceCoverageSlotDraft> unresolvedSlots = complete.sourceCoverageSlots().stream()
                .map(slot -> slot.slotId().equals("action-b")
                        ? new SourceCoverageSlotDraft(
                                slot.slotId(),
                                slot.role(),
                                slot.sourceIdentifier(),
                                List.of(),
                                slot.ownerTopicKey(),
                                SourceCoverageAvailability.UNRESOLVED)
                        : slot)
                .toList();
        OutlineDraft unresolved = new OutlineDraft(
                complete.gameTitle(),
                complete.premise(),
                complete.topics(),
                unresolvedSlots,
                false);

        TeachingPlan plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", unresolved);

        assertThat(new TeachingLessonAssemblyPolicy().status(plan, supportedSections(plan)))
                .isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(new LessonQualityEvaluator().evaluate(plan, lesson(plan, supportedSections(plan))).checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> assertThat(check.status()).isEqualTo(CheckStatus.FAIL));
    }

    @Test
    void postPublicationAssemblyUsesTheSupportedReceiptInsteadOfMatchingSourceIdentifiersInTranslatedProse() {
        OutlineDraft outline = completeOutline();
        TeachingPlan plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", outline);
        List<LessonSection> sections = supportedSections(plan).stream()
                .map(section -> section.topicKey().equals("choices")
                        ? new LessonSection(
                                section.position(),
                                section.topicKey(),
                                section.coverageTags(),
                                section.title(),
                                section.required(),
                                EvidenceStatus.SUPPORTED,
                                section.visualKind(),
                                section.visualCaption(),
                                List.of(new LessonStep(
                                        1,
                                        "执行两个已规划选择",
                                        TeachingMove.DO,
                                        "按照本章前置验证通过的两个选择执行；发布层不再用英文锚点匹配中文正文。",
                                        section.steps().getFirst().sourcePages(),
                                        List.of(UUID.randomUUID()))))
                        : section)
                .toList();

        assertThat(new TeachingLessonAssemblyPolicy().status(plan, sections))
                .isEqualTo(LessonStatus.COMPLETE);
        assertThat(new LessonQualityEvaluator().evaluate(plan, lesson(plan, sections)).checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.PASS);
                    assertThat(check.summary()).contains("6 / 6");
                });
    }

    @Test
    void citedDraftOwnersCompleteStructuralCoverageWhileWaitingForSemanticReview() {
        OutlineDraft outline = completeOutline();
        TeachingPlan plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", outline);
        List<LessonSection> citedDrafts = supportedSections(plan).stream()
                .map(section -> new LessonSection(
                        section.position(),
                        section.topicKey(),
                        section.coverageTags(),
                        section.title(),
                        section.required(),
                        EvidenceStatus.CITED_DRAFT,
                        section.visualKind(),
                        section.visualCaption(),
                        section.visualSourcePages(),
                        section.visualSourceChunkIds(),
                        section.steps()))
                .toList();

        TeachingSourceCoverageContract.Assessment assessment =
                TeachingSourceCoverageContract.assess(plan, citedDrafts);

        assertThat(assessment.applicable()).isTrue();
        assertThat(assessment.complete()).isTrue();
        assertThat(new TeachingLessonAssemblyPolicy().status(plan, citedDrafts))
                .isEqualTo(LessonStatus.DRAFT_READY);
    }

    private OutlineDraft completeOutline() {
        List<TopicDraft> topics = List.of(
                topic("start", List.of("Z-01"), List.of("setup"), List.of(1)),
                topic("flow", List.of("T-alpha"), List.of("core_loop"), List.of(2)),
                topic("choices", List.of("A-lambda", "A-mu"), List.of("legal_action"), List.of(2)),
                topic("exception", List.of("E-9"), List.of("necessary_exception"), List.of(2)),
                topic("finish", List.of("F-omega"), List.of("end"), List.of(3)),
                topic("result", List.of("S-pi"), List.of("scoring"), List.of(3)));
        return new OutlineDraft(
                "Opaque game",
                "Opaque premise",
                topics,
                List.of(
                        slot("setup", SourceCoverageRole.SETUP, "Z-01", 1, "start"),
                        slot("flow", SourceCoverageRole.CORE_LOOP, "T-alpha", 2, "flow"),
                        slot("action-a", SourceCoverageRole.LEGAL_ACTION, "A-lambda", 2, "choices"),
                        slot("action-b", SourceCoverageRole.LEGAL_ACTION, "A-mu", 2, "choices"),
                        slot("exception", SourceCoverageRole.NECESSARY_EXCEPTION, "E-9", 2, "exception"),
                        slot("ending", SourceCoverageRole.ENDING, "F-omega", 3, "finish"),
                        slot("scoring", SourceCoverageRole.SCORING, "S-pi", 3, "result")),
                true);
    }

    private TopicDraft topic(String key, List<String> queries, List<String> tags, List<Integer> pages) {
        return new TopicDraft(
                key,
                "Topic " + key,
                "Teach only the source-bound relation for " + key + ".",
                true,
                false,
                queries,
                tags,
                pages);
    }

    private SourceCoverageSlotDraft slot(
            String id,
            SourceCoverageRole role,
            String sourceIdentifier,
            int page,
            String ownerTopicKey) {
        return new SourceCoverageSlotDraft(
                id,
                role,
                sourceIdentifier,
                List.of(page),
                ownerTopicKey,
                SourceCoverageAvailability.SOURCED);
    }

    private List<LessonSection> supportedSections(TeachingPlan plan) {
        return plan.sections().stream()
                .map(section -> new LessonSection(
                        section.position(),
                        section.topicKey(),
                        section.coverageTags(),
                        section.title(),
                        section.required(),
                        EvidenceStatus.SUPPORTED,
                        VisualKind.REFERENCE_CARD,
                        "Source-bound aid.",
                        java.util.stream.IntStream.range(
                                        0,
                                        TeachingUnitContract.sourceIdentifiers(section.retrievalQueries()).size())
                                .mapToObj(index -> new LessonStep(
                                        index + 1,
                                        TeachingUnitContract.sourceIdentifiers(section.retrievalQueries()).get(index),
                                        TeachingMove.DO,
                                        "Apply the cited source relation for "
                                                + TeachingUnitContract.sourceIdentifiers(section.retrievalQueries()).get(index) + ".",
                                        section.sourcePageNumbers(),
                                        List.of(UUID.randomUUID())))
                                .toList()))
                .toList();
    }

    private IllustratedLesson lesson(TeachingPlan plan, List<LessonSection> sections) {
        return new IllustratedLesson(
                UUID.randomUUID(),
                plan.id(),
                new TeachingLessonAssemblyPolicy().status(plan, sections),
                sections,
                "source-contract-test",
                Instant.parse("2026-08-16T00:00:00Z"));
    }
}
