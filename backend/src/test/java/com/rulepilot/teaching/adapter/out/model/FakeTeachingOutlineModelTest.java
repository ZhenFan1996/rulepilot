package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
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
    void visualFallbackPreservesEveryAdmittedPageInDocumentOrderWithoutSemanticRouting() {
        var outline = model.organize(new OutlineRequest(IntStream.rangeClosed(1, 12)
                .mapToObj(page -> visualPage(
                        page,
                        "Opaque printed term " + page,
                        "A sufficiently detailed page-local observation number " + page))
                .toList()));

        assertThat(outline.topics()).hasSize(3);
        assertThat(outline.topics()).extracting(topic -> topic.sourcePageNumbers())
                .containsExactly(
                        List.of(1, 2, 3, 4, 5),
                        List.of(6, 7, 8, 9, 10),
                        List.of(11, 12));
        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 12).boxed().toList());
        assertThat(outline.topics().getFirst().coverageTags())
                .containsExactly("setup", "core_loop", "end", "scoring");
        assertThat(outline.topics().subList(1, outline.topics().size()))
                .allSatisfy(topic -> assertThat(topic.coverageTags()).containsExactly("source_coverage"));
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
    void refusesToSilentlyDropPagesBeyondTheStructuralFallbackCapacity() {
        var pages = IntStream.rangeClosed(1, 81)
                .mapToObj(page -> visualPage(
                        page,
                        "Term " + page,
                        "This page has a complete factual ledger for structural admission."))
                .toList();

        assertThatThrownBy(() -> model.organize(new OutlineRequest(pages)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallback capacity");
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
