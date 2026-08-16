package com.rulepilot.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.retrieval.AnswerRetrievalPlan.EvidenceNeed;
import com.rulepilot.retrieval.AnswerRetrievalQuestion.QuestionType;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.RuleFactStatus;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnswerEvidenceRetrieverTest {

    private static final String VISUAL_PLACEHOLDER =
            "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
    private final UUID versionId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    @Test
    void selectsTextEvidenceAndEnrichesItWithTheMatchingVisualPageFact() {
        HybridEvidenceHit source = evidence("执行该行动后获得一分。", 0.7);
        VisualRulebookPageFactSearch facts = (documentVersionId, query, limit) -> List.of(
                new VisualRulebookPageFactSearch.PageFactMatch(
                        4, "Score marker", "The marker advances one space after this action.", List.of("marker"), 0.9));
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(pageNumbers).contains(4);
                return List.of(source.evidence());
            }
        };
        AnswerEvidenceRetriever retriever = retriever((documentVersionId, query, options) -> List.of(source), facts, lookup);

        AnswerRetrievalQuestion question = question("How do I score after this action?");
        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question, context(), "alice", visualPlan(question));

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.READY);
        assertThat(result.evidence()).anySatisfy(hit ->
                assertThat(hit.evidence().excerpt()).contains("执行该行动后获得一分", "Visible facts", "marker"));
    }

    @Test
    void performsAnExactBoundedVisualLookupForPrintedIdentifiersBeforeIntentExpansion() {
        HybridEvidenceHit generic = evidence("Actions may provide several different benefits.", 0.9);
        List<String> visualQueries = new ArrayList<>();
        VisualRulebookPageFactSearch facts = (documentVersionId, query, limit) -> {
            visualQueries.add(query);
            if (!query.equals("A-01 B#02")) return List.of();
            assertThat(limit).isEqualTo(4);
            return List.of(new VisualRulebookPageFactSearch.PageFactMatch(
                    7, "A-01 B#02", "A-01 grants movement; B#02 grants energy.", List.of("A-01", "B#02"), 1.0));
        };
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(pageNumbers).contains(7);
                return List.of(hit("VISUAL", "Catalogue", "Printed catalogue.", 7, 0.5).evidence());
            }
        };
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(generic), facts, lookup);

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("比较 A-01 和 B#02 的功能。"), context(), "alice");

        assertThat(visualQueries).first().isEqualTo("A-01 B#02");
        assertThat(result.evidence()).anySatisfy(hit ->
                assertThat(hit.evidence().excerpt()).contains("A-01 grants movement", "B#02 grants energy"));
    }

    @Test
    void usesDocumentPageFactsToRecoverAHeadedRuleWhenGenericTextRetrievalAnchorsTheWrongPage() {
        HybridEvidenceHit wrongText = hit(
                "ACTIONS",
                "Exchange",
                "Exchange two resources after choosing this action.",
                6,
                0.9);
        List<String> visualQueries = new ArrayList<>();
        VisualRulebookPageFactSearch facts = (documentVersionId, query, limit) -> {
            visualQueries.add(query);
            if (!query.toLowerCase().contains("recover")) return List.of();
            return List.of(new VisualRulebookPageFactSearch.PageFactMatch(
                    2,
                    "RECOVER",
                    "RECOVER returns every previously played action card to the player's hand.",
                    List.of("RECOVER", "action cards"),
                    1.0));
        };
        RuleEvidenceHit directRule = hit(
                        "ACTIONS",
                        "RECOVER",
                        "Return every previously played action card to your hand.",
                        2,
                        0.8)
                .evidence();
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return pageNumbers.contains(2) ? List.of(directRule) : List.of();
            }
        };
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(wrongText), facts, lookup);

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(),
                question("If I use RECOVER now, which played cards return to my hand?"),
                context(),
                "alice");

        assertThat(visualQueries).isNotEmpty();
        assertThat(result.evidence()).anySatisfy(hit -> {
            assertThat(hit.evidence().pageFrom()).isEqualTo(2);
            assertThat(hit.evidence().heading()).isEqualTo("RECOVER");
            assertThat(hit.evidence().excerpt()).contains("every previously played action card");
        });
    }

    @ParameterizedTest
    @MethodSource("imageOnlyRuleQuestions")
    void fallsBackToPageScopedVisualTranscriptionForDifferentImageOnlyRulebooks(
            String playerQuestion,
            String factualSummary) {
        HybridEvidenceHit placeholder = hit("VISUAL", "Rendered rulebook page", VISUAL_PLACEHOLDER, 3, 0.2);
        List<String> visualQueries = new ArrayList<>();
        VisualRulebookPageFactSearch facts = (documentVersionId, query, limit) -> {
            visualQueries.add(query);
            return List.of(new VisualRulebookPageFactSearch.PageFactMatch(
                    3, "Visible rule text", factualSummary, List.of("page fact"), 0.9));
        };
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return pageNumbers.contains(3) ? List.of(placeholder.evidence()) : List.of();
            }
        };
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(placeholder), facts, lookup);

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question(playerQuestion), context(), "alice");

        assertThat(visualQueries).isNotEmpty();
        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.READY);
        assertThat(result.evidence()).anySatisfy(hit -> assertThat(hit.evidence().excerpt())
                .startsWith("Visual-transcribed rule evidence")
                .contains(factualSummary)
                .doesNotContain(VISUAL_PLACEHOLDER));
    }

    @Test
    void keepsAnImageOnlyPageUnassertiveWhenNoMatchingVisualFactExists() {
        HybridEvidenceHit placeholder = hit("VISUAL", "Rendered rulebook page", VISUAL_PLACEHOLDER, 8, 0.2);
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(placeholder),
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of(placeholder.evidence()));

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("What happens to unused pieces?"), context(), "alice");

        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void reportsUnavailableWhenEveryCoreRetrievalCallFails() {
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> {
                    throw new IllegalStateException("search unavailable");
                },
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of());

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("How do I score after this action?"), context(), "alice");

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.UNAVAILABLE);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void rejectsConflictingSnapshotsForTheSameEvidenceIdentity() {
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(
                        evidence("执行该行动后获得一分。", 0.7),
                        evidence("执行该行动后失去一分。", 0.7)),
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of());

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("How do I score after this action?"), context(), "alice");

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.CONFLICTING);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void preservesRetrieverOrderInsteadOfSemanticallyRerankingCandidateProse() {
        HybridEvidenceHit setup = hit(
                "SETUP",
                "Player setup",
                "Each player receives a hand of numbered cards before the game starts.",
                4,
                0.9);
        HybridEvidenceHit collision = hit(
                "ROUND_STRUCTURE",
                "Resolving a bump",
                "Players who played the same number resolve the bump in the printed order.",
                10,
                0.8);
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(setup, collision),
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of());

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("What happens when two players play the same number?"), context(), "alice");

        assertThat(result.evidence()).extracting(hit -> hit.evidence().heading())
                .containsExactly("Player setup", "Resolving a bump");
    }

    @Test
    void keepsBroadSupplementaryRecallBehindThePrimaryDirectAnchor() {
        HybridEvidenceHit direct = hit(
                "ROUND_STRUCTURE",
                "Collision",
                "When players choose the same number, resolve the collision in the printed order.",
                10,
                0.6);
        HybridEvidenceHit broadSupplement = hit(
                "ROUND_STRUCTURE",
                "Round cleanup",
                "After the round, every player retrieves their numbered card and draws a replacement.",
                11,
                0.9);
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> query.contains("rule condition consequence")
                        ? List.of(broadSupplement)
                        : List.of(direct),
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of());

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("What happens when players choose the same number?"), context(), "alice");

        assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId())
                .containsExactly(direct.evidence().chunkId(), broadSupplement.evidence().chunkId());
    }

    @Test
    void keepsEveryDirectObligationAnchoredWhenTheFiveIntentBudgetIsFull() {
        java.util.concurrent.atomic.AtomicInteger searchIndex = new java.util.concurrent.atomic.AtomicInteger();
        List<String> directHeadings = new ArrayList<>();
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> {
                    int index = searchIndex.getAndIncrement();
                    String heading = "Direct obligation " + index;
                    directHeadings.add(heading);
                    return List.of(
                            hit("RULE", heading, "Opaque direct clause " + index, index + 1, 0.1),
                            hit("GENERAL", "Metadata " + index + "A", "Opaque metadata.", index + 20, 1.0),
                            hit("GENERAL", "Metadata " + index + "B", "Opaque metadata.", index + 30, 0.9));
                },
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of());
        AnswerRetrievalQuestion question = question("Current cobalt spindle question");
        AnswerRetrievalPlan fullPlan = new AnswerRetrievalPlan(
                List.of(
                        new AnswerRetrievalPlan.Subquestion("first obligation", Set.of(EvidenceNeed.DIRECT_RULE)),
                        new AnswerRetrievalPlan.Subquestion("second obligation", Set.of(EvidenceNeed.DIRECT_RULE)),
                        new AnswerRetrievalPlan.Subquestion("third obligation", Set.of(EvidenceNeed.DIRECT_RULE)),
                        new AnswerRetrievalPlan.Subquestion("fourth obligation", Set.of(EvidenceNeed.DIRECT_RULE))),
                false);

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question, context(), "alice", fullPlan);

        assertThat(searchIndex).hasValue(5);
        assertThat(result.evidence())
                .extracting(hit -> hit.evidence().heading())
                .containsAll(directHeadings);
    }

    @Test
    void acceptsCrossLanguageRewritesThroughTheCallerPortWithoutOwningAModelContract() {
        List<String> searchedQueries = new ArrayList<>();
        List<String> rewriteInputs = new ArrayList<>();
        HybridEvidenceHit source = evidence("Setup happens before the first turn.", 0.8);
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> {
                    searchedQueries.add(query);
                    return List.of(source);
                },
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of(),
                (runId, username, question, previousQuestion) -> {
                    rewriteInputs.add(username);
                    rewriteInputs.add(question);
                    rewriteInputs.add(previousQuestion);
                    return List.of("setup before first turn");
                });

        retriever.retrieve(
                UUID.randomUUID(),
                question("游戏开始前如何设置？"),
                new AnswerRetrievalContext(versionId, "上一轮问了回合顺序。", null),
                "alice");

        assertThat(rewriteInputs).containsExactly("alice", "游戏开始前如何设置？", null);
        assertThat(searchedQueries).contains("setup before first turn");
    }

    @Test
    void treatsAnExplicitPageAsALocatorAndRanksItsCurrentRuleFactsAboveDescriptiveMetadata() {
        RuleEvidenceHit hintedPage = hit(
                        "GENERAL", "Rendered page", VISUAL_PLACEHOLDER, 47, 0.1)
                .evidence();
        HybridEvidenceHit descriptive = hit(
                "GENERAL",
                "Archive panel",
                "A release archive entry describes the document but states no playable rule.",
                12,
                1.0);
        VisualRulebookPageFactSearch facts = new VisualRulebookPageFactSearch() {
            @Override
            public List<PageFactMatch> search(UUID documentVersionId, String query, int limit) {
                return List.of(new PageFactMatch(
                        12,
                        "Archive panel",
                        "This page is descriptive material only.",
                        List.of("archive"),
                        1.0,
                        RuleFactStatus.NO_RULE_CONTENT));
            }

            @Override
            public List<PageFactMatch> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                if (!pageNumbers.contains(47)) return List.of();
                return List.of(new PageFactMatch(
                        47,
                        "cobalt spindle",
                        "The cobalt spindle returns after the current interval.",
                        List.of("cobalt spindle"),
                        0.8,
                        RuleFactStatus.CURRENT_RULE_FACTS));
            }
        };
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                List<RuleEvidenceHit> sources = new ArrayList<>();
                if (pageNumbers.contains(47)) sources.add(hintedPage);
                if (pageNumbers.contains(12)) sources.add(descriptive.evidence());
                return List.copyOf(sources);
            }
        };
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(descriptive), facts, lookup);
        AnswerRetrievalQuestion question = question(
                "On page 47, does the cobalt spindle return after the current interval?");
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(new AnswerRetrievalPlan.Subquestion(
                        "does the cobalt spindle return after the current interval?",
                        Set.of(EvidenceNeed.DIRECT_RULE),
                        AnswerRetrievalPlan.QuestionOwner.CURRENT_QUESTION)),
                false,
                AnswerRetrievalPlan.ReferenceBinding.CURRENT_QUESTION,
                null,
                List.of("cobalt spindle"),
                List.of(new AnswerRetrievalPlan.PageHint("page 47", 47)));

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question, context(), "alice", plan);

        assertThat(result.evidence()).singleElement().satisfies(hit -> {
            assertThat(hit.evidence().pageFrom()).isEqualTo(47);
            assertThat(hit.evidence().excerpt())
                    .startsWith("Visual-transcribed rule evidence")
                    .contains("returns after the current interval");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Promotional panel", "Document listing", "Request failure notice"})
    void excludesTypedNonRulePagesWithoutClassifyingTheirWording(String heading) {
        HybridEvidenceHit source = hit(
                "GENERAL", heading, "Opaque descriptive content with no executable clause.", 31, 1.0);
        VisualRulebookPageFactSearch facts = (documentVersionId, query, limit) -> List.of(new PageFactMatch(
                31,
                heading,
                "The page has no gameplay rule group.",
                List.of("descriptive"),
                1.0,
                RuleFactStatus.NO_RULE_CONTENT));
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return pageNumbers.contains(31) ? List.of(source.evidence()) : List.of();
            }
        };
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(source), facts, lookup);

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("What does the cobalt spindle do?"), context(), "alice");

        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void propagatesTheOwningAgentStopSignalInsteadOfDegradingItAsSearchUnavailability() {
        RuntimeException stopped = new RuntimeException("tool budget stopped");
        AnswerRetrievalInvocations stoppedInvocations = new AnswerRetrievalInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                throw stopped;
            }

            @Override
            public boolean executionStopped(RuntimeException failure) {
                return failure == stopped;
            }
        };
        AnswerEvidenceRetriever retriever = new AnswerEvidenceRetriever(
                (documentVersionId, query, options) -> List.of(),
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of(),
                stoppedInvocations,
                (runId, username, question, previousQuestion) -> List.of());

        assertThatThrownBy(() -> retriever.retrieve(
                        UUID.randomUUID(), question("How does this action work?"), context(), "alice"))
                .isSameAs(stopped);
    }

    private AnswerEvidenceRetriever retriever(
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup) {
        return retriever(
                retrieval,
                visualFacts,
                evidenceLookup,
                (runId, username, question, previousQuestion) -> List.of());
    }

    private AnswerEvidenceRetriever retriever(
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup,
            AnswerRetrievalQueryRewriter queryRewriter) {
        ImmediateAnswerRetrievalInvocations invocations = new ImmediateAnswerRetrievalInvocations();
        return new AnswerEvidenceRetriever(
                retrieval,
                visualFacts,
                evidenceLookup,
                invocations,
                queryRewriter);
    }

    private AnswerRetrievalQuestion question(String text) {
        return new AnswerRetrievalQuestion(
                text.toLowerCase(), QuestionType.RULE_QUERY, List.of("score"));
    }

    private AnswerRetrievalContext context() {
        return new AnswerRetrievalContext(versionId);
    }

    private AnswerRetrievalPlan visualPlan(AnswerRetrievalQuestion question) {
        return new AnswerRetrievalPlan(
                List.of(new AnswerRetrievalPlan.Subquestion(
                        question.normalizedQuestion(), Set.of(EvidenceNeed.VISUAL_REFERENCE))),
                false);
    }

    private HybridEvidenceHit evidence(String excerpt, double score) {
        return new HybridEvidenceHit(
                new RuleEvidenceHit(chunkId, versionId, "ACTIONS", "Scoring", excerpt, 4, 4, score),
                score,
                1,
                null,
                false);
    }

    private HybridEvidenceHit hit(String sectionType, String heading, String excerpt, int page, double score) {
        return new HybridEvidenceHit(
                new RuleEvidenceHit(UUID.randomUUID(), versionId, sectionType, heading, excerpt, page, page, score),
                score,
                1,
                null,
                false);
    }

    private static java.util.stream.Stream<Arguments> imageOnlyRuleQuestions() {
        return java.util.stream.Stream.of(
                Arguments.of(
                        "Where do the unused pieces go after I take one kind?",
                        "After one kind is taken, every unused piece moves to the shared area."),
                Arguments.of(
                        "我选完行动后，没打出的卡要怎么处理？",
                        "行动结算后，未打出的卡全部面朝下放入弃牌区。"));
    }
}
