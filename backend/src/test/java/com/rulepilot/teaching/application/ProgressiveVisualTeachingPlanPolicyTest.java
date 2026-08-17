package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupCoverage;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageSketch;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressiveVisualTeachingPlanPolicyTest {

    @Test
    void explicitPageInventoryBuildsSeparateRequiredSlotsForEveryLegalActionAndNecessaryException() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        pageWithRoles(1, "S-0", List.of("S-0"), List.of("setup"),
                                List.of(coverage("S-0", SourceCoverageRole.SETUP))),
                        pageWithRoles(
                                2,
                                "T-0",
                                List.of("T-0", "A-1", "A-2", "E-0"),
                                List.of("core_loop", "source_coverage"),
                                List.of(
                                        coverage("T-0", SourceCoverageRole.CORE_LOOP),
                                        coverage("A-1", SourceCoverageRole.LEGAL_ACTION),
                                        coverage("A-2", SourceCoverageRole.LEGAL_ACTION),
                                        coverage("E-0", SourceCoverageRole.NECESSARY_EXCEPTION))),
                        pageWithRoles(3, "F-0", List.of("F-0"), List.of("end"),
                                List.of(coverage("F-0", SourceCoverageRole.ENDING))),
                        pageWithRoles(4, "P-0", List.of("P-0"), List.of("scoring"),
                                List.of(coverage("P-0", SourceCoverageRole.SCORING)))),
                facts(1, "S-0", "S-0：每位玩家按照页面上清楚可见的完整关系完成全部开局准备。", "S-0"));

        var outline = ProgressiveVisualTeachingPlanPolicy.outline("Opaque game", pages(4), start);

        assertThat(outline.sourceCoverageInventoryComplete()).isTrue();
        assertThat(outline.sourceCoverageSlots())
                .filteredOn(slot -> slot.role() == SourceCoverageRole.LEGAL_ACTION)
                .extracting(slot -> slot.sourceIdentifier())
                .containsExactly("A-1", "A-2");
        assertThat(outline.sourceCoverageSlots())
                .filteredOn(slot -> slot.role() == SourceCoverageRole.NECESSARY_EXCEPTION)
                .extracting(slot -> slot.sourceIdentifier())
                .containsExactly("E-0");
        assertThat(outline.topics()).flatExtracting(topic -> topic.coverageTags())
                .contains("legal_action", "necessary_exception");
    }

    @Test
    void modelSelectedGameplayPageCanStartBeforeOrAfterAnyFixedChapterTaxonomy() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        pageWithRoles(1, "S-0", List.of("S-0"), List.of("setup"),
                                List.of(coverage("S-0", SourceCoverageRole.SETUP))),
                        pageWithRoles(2, "T-0", List.of("T-0", "A-1"), List.of("core_loop"),
                                List.of(
                                        coverage("T-0", SourceCoverageRole.CORE_LOOP),
                                        coverage("A-1", SourceCoverageRole.LEGAL_ACTION))),
                        pageWithRoles(3, "F-0", List.of("F-0"), List.of("end"),
                                List.of(coverage("F-0", SourceCoverageRole.ENDING))),
                        pageWithRoles(4, "P-0", List.of("P-0"), List.of("scoring"),
                                List.of(coverage("P-0", SourceCoverageRole.SCORING)))),
                facts(2, "T-0; A-1", "T-0：可见循环推进；A-1：玩家执行可见行动。", "T-0"));

        assertThat(ProgressiveVisualTeachingPlanPolicy.outline("Opaque game", pages(4), start).topics())
                .first()
                .extracting(topic -> topic.sourcePageNumbers().getFirst())
                .isEqualTo(2);
    }

    @Test
    void startsWithTheModelSelectedEarlyJourneyPageAndKeepsEveryGameplayObligation() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.NON_GAMEPLAY, "", List.of(), List.of()),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "Setup", List.of("market"), List.of("setup")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "Turn", List.of("take cards"), List.of("core_loop")),
                        page(4, TeachingPageRole.GAMEPLAY_RULES, "Refill", List.of("refill"), List.of("source_coverage")),
                        page(5, TeachingPageRole.UNCERTAIN, "", List.of(), List.of()),
                        page(6, TeachingPageRole.GAMEPLAY_RULES, "End", List.of("end"), List.of("end")),
                        page(7, TeachingPageRole.GAMEPLAY_RULES, "Scoring", List.of("score"), List.of("scoring"))),
                facts(3, "Take cards", "当前玩家选择可见卡牌并执行本回合行动。", "take cards"));

        var outline = ProgressiveVisualTeachingPlanPolicy.outline(
                "Example game", pages(7), start);

        assertThat(outline.topics()).extracting(topic -> topic.sourcePageNumbers().getFirst())
                .containsExactly(3, 2, 4, 6, 7, 5);
        assertThat(outline.topics().getFirst()).satisfies(topic -> {
            assertThat(topic.title()).isEqualTo("Turn");
            assertThat(topic.retrievalQueries()).contains("take cards");
            assertThat(topic.sourcePageNumbers()).containsExactly(3);
        });
        assertThat(outline.topics()).flatExtracting(topic -> topic.coverageTags())
                .contains("setup", "core_loop", "end", "scoring");
        assertThat(outline.topics().getLast().required()).isFalse();
        assertThat(outline.topics()).noneSatisfy(topic -> assertThat(topic.sourcePageNumbers()).contains(1));
    }

    @Test
    void doesNotOverrideTheModelsSelectedFirstPageUsingFixedEarlyJourneyTags() {
        var lateStart = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.GAMEPLAY_RULES, "Setup", List.of("table layout"), List.of("setup")),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "Turn", List.of("take action"), List.of("core_loop")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "Finish", List.of("end", "score"),
                                List.of("end", "scoring"))),
                facts(3, "Finish; Score", "结束条件成立后，所有玩家分别计算最终得分并比较胜负。", "score"));

        assertThat(ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(3), lateStart).topics())
                .first()
                .extracting(topic -> topic.sourcePageNumbers().getFirst())
                .isEqualTo(3);
    }

    @Test
    void pageVocabularyCannotOverrideTheStructuredRoleOrSelectedBinding() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.NON_GAMEPLAY, "FINAL SCORE WINNER", List.of(), List.of()),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "QXZ", List.of("alpha"), List.of("setup")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "Beta", List.of("beta"), List.of("core_loop")),
                        page(4, TeachingPageRole.GAMEPLAY_RULES, "Gamma", List.of("gamma"), List.of("end", "scoring"))),
                facts(3, "Beta", "当前玩家先执行可见动作，再结束本回合。", "beta"));

        var outline = ProgressiveVisualTeachingPlanPolicy.outline("Opaque game", pages(4), start);

        assertThat(outline.topics()).extracting(topic -> topic.sourcePageNumbers().getFirst())
                .containsExactly(3, 2, 4);
        assertThat(outline.topics()).noneSatisfy(topic -> assertThat(topic.sourcePageNumbers()).contains(1));
    }

    @Test
    void rejectsUnknownPageBindingsButDoesNotRequireAFixedFourRoleChecklist() {
        var missingPage = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.GAMEPLAY_RULES, "A", List.of("a"), List.of("setup", "core_loop")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "C", List.of("c"), List.of("end", "scoring"))),
                facts(1, "A", "每位玩家拿取一个组件并放在自己面前。", "a"));
        assertThatThrownBy(() -> ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(3), missingPage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every supplied page exactly");

        var missingScoring = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.GAMEPLAY_RULES, "A", List.of("a"), List.of("setup")),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "B", List.of("b"), List.of("core_loop")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "C", List.of("c"), List.of("end"))),
                facts(2, "B", "当前玩家必须执行一个可见动作，然后结束回合。", "b"));
        assertThat(ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(3), missingScoring).topics())
                .hasSize(3);
    }

    @Test
    void preservesAnExplicitMissingSetupGuideWithoutPretendingItContainsSetupRules() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        new TeachingPageSketch(
                                1,
                                TeachingPageRole.GAMEPLAY_RULES,
                                "Playing the game",
                                List.of("play a personality card", "resolve the action"),
                                List.of("core_loop"),
                                true,
                                List.of(new SourceDependency("Quick Start Guide", List.of("setup")))),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "End and score", List.of("game end", "final score"),
                                List.of("end", "scoring"))),
                facts(1, "play a personality card", "当前玩家打出一张人格卡，再执行该卡对应的行动。", "personality card"));

        var outline = ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(2), start);

        assertThat(outline.topics()).hasSize(3);
        assertThat(outline.topics()).flatExtracting(topic -> topic.coverageTags())
                .doesNotContain("setup")
                .contains("source_dependency", "missing_setup_source");
        assertThat(outline.topics())
                .filteredOn(topic -> topic.coverageTags().contains("source_dependency"))
                .singleElement()
                .satisfies(topic -> {
                    assertThat(topic.retrievalQueries()).containsExactly("Quick Start Guide");
                    assertThat(topic.sourcePageNumbers()).containsExactly(1);
                    assertThat(topic.objective()).contains("只证明当前规则书要求另行查看", "不要补写缺失资料中的规则");
                    assertThat(topic.required()).isTrue();
                });
    }

    @Test
    void multipleExternalSourcesForOneMissingRoleKeepUniqueContractSlots() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        new TeachingPageSketch(
                                1,
                                TeachingPageRole.GAMEPLAY_RULES,
                                "T-0",
                                List.of("T-0", "A-1"),
                                List.of("core_loop"),
                                true,
                                List.of(
                                        new SourceDependency("Start Leaflet", List.of("setup")),
                                        new SourceDependency("Initial State Sheet", List.of("setup"))),
                                List.of(
                                        coverage("T-0", SourceCoverageRole.CORE_LOOP),
                                        coverage("A-1", SourceCoverageRole.LEGAL_ACTION))),
                        pageWithRoles(
                                2,
                                "F-0",
                                List.of("F-0", "P-0"),
                                List.of("end", "scoring"),
                                List.of(
                                        coverage("F-0", SourceCoverageRole.ENDING),
                                        coverage("P-0", SourceCoverageRole.SCORING)))),
                facts(1, "T-0; A-1", "T-0：可见循环推进；A-1：玩家执行可见行动。", "T-0"));

        var outline = ProgressiveVisualTeachingPlanPolicy.outline("Opaque game", pages(2), start);

        assertThatCode(() -> new TeachingPlanFactory().create(
                        java.util.UUID.randomUUID(), "player", outline))
                .doesNotThrowAnyException();
        assertThat(outline.sourceCoverageSlots())
                .filteredOn(slot -> slot.availability()
                        == com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE)
                .extracting(slot -> slot.slotId())
                .doesNotHaveDuplicates();
    }

    @Test
    void aGenericExternalReferenceDoesNotTriggerAFixedCoreObligationFailure() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        new TeachingPageSketch(
                                1,
                                TeachingPageRole.GAMEPLAY_RULES,
                                "Turn",
                                List.of("take action"),
                                List.of("core_loop"),
                                true,
                                List.of(new SourceDependency("Reference Sheet", List.of()))),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "End and score", List.of("end", "score"),
                                List.of("end", "scoring"))),
                facts(1, "take action", "当前玩家执行一个可见行动，然后结束本回合。", "take action"));

        assertThat(ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(2), start).topics())
                .hasSize(3);
    }

    @Test
    void preservesAUsableProgressivePlanWhenInventoryCompletenessIsNotDeclared() {
        var incompleteInventory = new ProgressiveTeachingStartDraft(
                List.of(
                        new TeachingPageSketch(
                                1,
                                TeachingPageRole.GAMEPLAY_RULES,
                                "All actions",
                                List.of("move", "build", "trade", "copy"),
                                List.of("setup", "core_loop"),
                                false),
                        new TeachingPageSketch(
                                2,
                                TeachingPageRole.GAMEPLAY_RULES,
                                "End and score",
                                List.of("end", "score"),
                                List.of("end", "scoring"),
                                true)),
                facts(1, "move; build; trade; copy", "当前玩家选择并完整执行一个可见行动。", "move"));

        var outline = ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(2), incompleteInventory);

        assertThat(outline.topics()).hasSize(2);
        assertThat(outline.sourceCoverageInventoryComplete()).isFalse();
    }

    @Test
    void rejectsASelectedNonGameplayPageButDoesNotScoreFactProseByLengthOrKeywordCount() {
        assertThatThrownBy(() -> new ProgressiveTeachingStartDraft(
                        List.of(page(1, TeachingPageRole.NON_GAMEPLAY, "Cover", List.of(), List.of())),
                        facts(1, "Cover", "这里只显示游戏名称和出版社标志。", "cover")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-gameplay");

        var emptyFacts = new ProgressiveTeachingStartDraft(
                List.of(page(1, TeachingPageRole.GAMEPLAY_RULES, "A", List.of("a"),
                        List.of("setup", "core_loop", "end", "scoring"))),
                new PageSummary(1, null, null, null));
        assertThat(ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(1), emptyFacts).topics())
                .hasSize(1);
    }

    @Test
    void keepsDefaultKeywordsAndAnyNumberOfFactLinesWithoutLocalQualityScoring() {
        List<TeachingPageSketch> sketches = List.of(page(
                1,
                TeachingPageRole.GAMEPLAY_RULES,
                "Turn",
                List.of("take"),
                List.of("setup", "core_loop", "end", "scoring")));
        var defaultKeyword = new ProgressiveTeachingStartDraft(
                sketches,
                new PageSummary(1, "TAKE", "当前玩家必须拿取一个可见组件，然后结束当前回合。", null));
        assertThat(ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(1), defaultKeyword).topics())
                .hasSize(1);

        var completeEightFacts = new ProgressiveTeachingStartDraft(
                sketches,
                new PageSummary(
                        1,
                        "A; B; C; D; E; F; G; H",
                        "执行规则组A。\n执行规则组B。\n执行规则组C。\n执行规则组D。\n"
                                + "执行规则组E。\n执行规则组F。\n执行规则组G。\n执行规则组H。",
                        List.of("A", "B")));
        assertThat(ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(1), completeEightFacts).topics())
                .hasSize(1);

        var tooManyFacts = new ProgressiveTeachingStartDraft(
                sketches,
                new PageSummary(
                        1,
                        "TAKE; REFILL",
                        "执行规则组1。\n执行规则组2。\n执行规则组3。\n执行规则组4。\n执行规则组5。\n"
                                + "执行规则组6。\n执行规则组7。\n执行规则组8。\n执行规则组9。",
                        List.of("TAKE", "REFILL")));
        assertThat(ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(1), tooManyFacts).topics())
                .hasSize(1);
    }

    private TeachingPageSketch page(
            int number,
            TeachingPageRole role,
            String heading,
            List<String> terms,
            List<String> tags) {
        return new TeachingPageSketch(number, role, heading, terms, tags);
    }

    private TeachingPageSketch pageWithRoles(
            int number,
            String heading,
            List<String> terms,
            List<String> tags,
            List<RuleGroupCoverage> coverage) {
        return new TeachingPageSketch(
                number,
                TeachingPageRole.GAMEPLAY_RULES,
                heading,
                terms,
                tags,
                true,
                List.of(),
                coverage);
    }

    private RuleGroupCoverage coverage(String identifier, SourceCoverageRole role) {
        return new RuleGroupCoverage(identifier, role);
    }

    private PageSummary facts(int number, String terms, String summary, String keyword) {
        return new PageSummary(number, terms, summary, List.of(keyword, "rule"));
    }

    private List<PageView> pages(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(page -> new PageView(page, "", 0))
                .toList();
    }
}
