package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FakeTeachingOutlineModelTest {

    private final FakeTeachingOutlineModel model = new FakeTeachingOutlineModel();

    @Test
    void textFallbackKeepsCoreLearningObligationsWithoutClassifyingPagesByKeywords() {
        var outline = model.organize(new OutlineRequest(List.of(
                new PageInput(1, "Alba\nPlayers receive pieces before play."),
                new PageInput(2, "A completely unfamiliar source-language paragraph."))));

        assertThat(outline.gameTitle()).isEqualTo("Imported rulebook");
        assertThat(outline.topics()).hasSize(4);
        assertThat(outline.topics()).flatExtracting(topic -> topic.coverageTags())
                .containsExactlyInAnyOrder("setup", "core_loop", "end", "scoring");
        assertThat(outline.topics()).allSatisfy(topic -> {
            assertThat(topic.retrievalQueries()).isNotEmpty();
            assertThat(topic.sourcePageNumbers()).isEmpty();
        });
    }

    @Test
    void textFallbackPreservesAVisuallyConfirmedExternalSourceDependency() {
        var page = new PageInput(
                1,
                "Extracted rules text with a separately verified page observation.",
                List.of(new SourceDependency("First Session Booklet", List.of("setup"))));

        var outline = model.organize(new OutlineRequest(List.of(page)));

        assertThat(outline.topics()).hasSize(4);
        assertThat(outline.topics()).flatExtracting(topic -> topic.coverageTags())
                .contains("core_loop", "end", "scoring", "source_dependency", "missing_setup_source")
                .doesNotContain("setup");
        assertThat(outline.topics().getLast()).satisfies(dependency -> {
            assertThat(dependency.retrievalQueries()).containsExactly("First Session Booklet");
            assertThat(dependency.sourcePageNumbers()).containsExactly(1);
        });
    }

    @Test
    void visualFallbackPreservesEveryAdmittedPageInBoundedDocumentOrderWithoutSemanticRouting() {
        var outline = model.organize(new OutlineRequest(IntStream.rangeClosed(1, 12)
                .mapToObj(page -> visualPage(
                        page,
                        "Opaque printed term " + page,
                        "A sufficiently detailed page-local observation number " + page))
                .toList()));

        assertThat(outline.topics()).hasSize(3);
        assertThat(outline.topics()).extracting(topic -> topic.sourcePageNumbers())
                .containsExactly(
                        List.of(1, 2, 3, 4),
                        List.of(5, 6, 7, 8),
                        List.of(9, 10, 11, 12));
        assertThat(outline.topics())
                .allSatisfy(topic -> assertThat(topic.retrievalQueries()).hasSizeLessThanOrEqualTo(8));
        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 12).boxed().toList());
        assertThat(outline.topics().getFirst().coverageTags())
                .containsExactly("setup", "core_loop", "end", "scoring", "source_coverage");
        assertThat(outline.topics().subList(1, outline.topics().size()))
                .allSatisfy(topic -> assertThat(topic.coverageTags()).containsExactly("source_coverage"));
    }

    @Test
    void visualFallbackKeepsEightRuleGroupsPerPageBoundToTheirExactSourcePage() {
        var outline = model.organize(new OutlineRequest(List.of(
                visualPage(1, "A1; A2; A3; A4; A5; A6; A7; A8", "Page one has eight independent rules."),
                visualPage(2, "B1; B2; B3; B4; B5; B6; B7; B8", "Page two has eight independent rules."))));

        assertThat(outline.topics()).hasSize(2);
        assertThat(outline.topics().getFirst()).satisfies(topic -> {
            assertThat(topic.retrievalQueries()).containsExactly("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8");
            assertThat(topic.sourcePageNumbers()).containsExactly(1);
        });
        assertThat(outline.topics().get(1)).satisfies(topic -> {
            assertThat(topic.retrievalQueries()).containsExactly("B1", "B2", "B3", "B4", "B5", "B6", "B7", "B8");
            assertThat(topic.sourcePageNumbers()).containsExactly(2);
        });
    }

    @Test
    void visualFallbackSplitsMoreThanEightIdentifiersWithoutChangingTheirSourcePage() {
        var outline = model.organize(new OutlineRequest(List.of(visualPage(
                4,
                "R1; R2; R3; R4; R5; R6; R7; R8; R9; R10; R11; R12",
                "The page contains twelve independently labeled rule groups."))));

        assertThat(outline.topics()).hasSize(2);
        assertThat(outline.topics().getFirst().retrievalQueries())
                .containsExactly("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8");
        assertThat(outline.topics().get(1).retrievalQueries())
                .containsExactly("R9", "R10", "R11", "R12");
        assertThat(outline.topics())
                .allSatisfy(topic -> assertThat(topic.sourcePageNumbers()).containsExactly(4));
    }

    @Test
    void pageVocabularyCannotChangeVisualFallbackOwnership() {
        List<PageInput> first = List.of(
                visualPage(1, "FAQ; FINAL SCORE", "Brand art and an unfamiliar observation ledger."),
                visualPage(2, "SETUP; WINNER", "Another unfamiliar observation ledger with enough detail."));
        List<PageInput> second = List.of(
                visualPage(1, "XQZ; ALPHA", "Brand art and an unfamiliar observation ledger."),
                visualPage(2, "OMEGA; BETA", "Another unfamiliar observation ledger with enough detail."));

        assertThat(model.organize(new OutlineRequest(first)).topics().stream()
                        .map(topic -> topic.sourcePageNumbers())
                        .toList())
                .isEqualTo(model.organize(new OutlineRequest(second)).topics().stream()
                        .map(topic -> topic.sourcePageNumbers())
                        .toList());
    }

    @Test
    void admitsAVisualPageFromItsFactualLedgerRatherThanARecognizedRuleTerm() {
        var outline = model.organize(new OutlineRequest(List.of(
                visualPage(1, "Q", "Symbols 3, 8, and 13 appear beside three bounded regions."),
                visualPage(2, "R", "x"))));

        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers()).containsExactly(1);
        assertThat(outline.topics().getFirst().retrievalQueries())
                .anySatisfy(query -> assertThat(query).contains("Symbols 3, 8, and 13"));
    }

    @Test
    void visualFallbackKeepsEveryBoundedVisibleFactLineAvailableToSourceCoverage() {
        var page = new PageInput(
                1,
                "[Visual page catalog; verify against page image]\n"
                        + "Printed terms: OPAQUE\n"
                        + "Visible facts: First atomic operation is visible.\n"
                        + "Second atomic operation is independently visible.\n"
                        + "Third atomic operation is also independently visible.\n"
                        + "Keywords: opaque");

        var outline = model.organize(new OutlineRequest(List.of(page)));

        assertThat(outline.topics().getFirst().retrievalQueries())
                .containsExactly(
                        "OPAQUE",
                        "First atomic operation is visible.",
                        "Second atomic operation is independently visible.",
                        "Third atomic operation is also independently visible.");
    }

    @Test
    void packsALongVisualLedgerIntoBoundedConsecutiveTopicsWithoutDroppingPages() {
        var pages = IntStream.rangeClosed(1, 17)
                .mapToObj(page -> visualPage(
                        page,
                        "Term " + page,
                        "This page has a complete factual ledger for structural admission."))
                .toList();

        var outline = model.organize(new OutlineRequest(pages));

        assertThat(outline.topics()).hasSize(4);
        assertThat(outline.topics()).allSatisfy(topic -> {
            assertThat(topic.sourcePageNumbers()).hasSizeLessThanOrEqualTo(5);
            assertThat(topic.retrievalQueries()).hasSizeLessThanOrEqualTo(8);
        });
        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 17).boxed().toList());
    }

    @Test
    void refusesToSilentlyDropDenseRuleGroupsBeyondThePackedFallbackCapacity() {
        var pages = IntStream.rangeClosed(1, 17)
                .mapToObj(page -> visualPage(
                        page,
                        IntStream.rangeClosed(1, 9)
                                .mapToObj(group -> "P" + page + "G" + group)
                                .collect(java.util.stream.Collectors.joining("; ")),
                        "This page has nine independent page-owned rule groups."))
                .toList();

        assertThatThrownBy(() -> model.organize(new OutlineRequest(pages)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallback capacity");
    }

    @Test
    void visualFallbackPreservesExternalSourceAsASeparateMissingObligation() {
        var page = new PageInput(
                1,
                "[Visual page catalog; verify against page image]\n"
                        + "Printed terms: PLAY A CARD; First Session Booklet\n"
                        + "Visible facts: 当前玩家打出一张牌并执行行动。\n"
                        + "Keywords: action",
                List.of(
                        new SourceDependency("First Session Booklet", List.of("setup")),
                        new SourceDependency("Reference Folio", List.of())));

        var outline = model.organize(new OutlineRequest(List.of(page)));

        assertThat(outline.topics()).hasSize(2);
        assertThat(outline.topics().getFirst().coverageTags())
                .containsExactly("core_loop", "end", "scoring", "source_coverage");
        assertThat(outline.topics().getFirst().retrievalQueries())
                .noneMatch(query -> query.contains("First Session Booklet"));
        assertThat(outline.topics().get(1)).satisfies(dependency -> {
            assertThat(dependency.sourcePageNumbers()).containsExactly(1);
            assertThat(dependency.retrievalQueries())
                    .containsExactly("First Session Booklet", "Reference Folio");
            assertThat(dependency.coverageTags())
                    .containsExactly("source_dependency", "missing_setup_source");
        });
        assertThat(outline.topics()).flatExtracting(topic -> topic.coverageTags())
                .doesNotContain("setup");
    }

    private PageInput visualPage(int number, String terms, String facts) {
        return new PageInput(
                number,
                "[Visual page catalog; verify against page image]\nPrinted terms: "
                        + terms
                        + "\nVisible facts: "
                        + facts
                        + "\nKeywords: opaque");
    }
}
