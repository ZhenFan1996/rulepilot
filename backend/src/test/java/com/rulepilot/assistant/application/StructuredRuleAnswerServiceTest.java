package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StructuredRuleAnswerServiceTest {

    private final UUID versionId = UUID.randomUUID();
    private final DeterministicQuestionUnderstanding understanding = new DeterministicQuestionUnderstanding();

    @Test
    void returnsOnlyValidatedCitationsFromCurrentVersion() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, 1, false)),
                request -> new ModelDraft(
                        "Coins score one point.", "Each coin contributes one point.",
                        List.of(source.chunkId()), List.of("Only count remaining coins."), "HIGH"));

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(source.chunkId());
            assertThat(citation.documentVersionId()).isEqualTo(versionId);
            assertThat(citation.pageFrom()).isEqualTo(8);
        });
        assertThat(answer.official()).isFalse();
    }

    @Test
    void removesShortInternalEvidenceIdentifiersFromPlayerFacingText() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "可以执行该行动[322c770b]。",
                        "满足规则所列条件后即可执行。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("PLAYER_FACING_OUTPUT"));
                return previousDraft;
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.8, 1, null, false)),
                model);

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).doesNotContain("322c770b");
        assertThat(revisions).hasValue(1);
    }

    @Test
    void answersImageOnlyRulesFromSearchedVisualPageFactsInsteadOfRandomPlaceholderPages() {
        RuleEvidenceHit randomPlaceholder = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "GENERAL",
                "Visual rulebook page 12",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                12,
                12,
                0.02);
        UUID pageSixChunkId = UUID.randomUUID();
        RuleEvidenceHit pageSixSource = new RuleEvidenceHit(
                pageSixChunkId,
                versionId,
                "GENERAL",
                "Visual rulebook page 6",
                randomPlaceholder.excerpt(),
                6,
                6,
                1.0);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                new VisualRulebookPageFactSearch.PageFactMatch(
                        6,
                        "Overpopulation, Wildlife Token, Nature Token",
                        "4 个相同标记时自动清除且同一回合可重复触发；3 个相同标记时当前玩家可以选择清除，且每回合只能这样做一次。",
                        List.of("Overpopulation", "Wildlife Token"),
                        0.9));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(pageNumbers).containsExactly(6);
                return List.of(pageSixSource);
            }
        };
        AtomicReference<RuleAnswerModel.ModelRequest> captured = new AtomicReference<>();
        RuleAnswerModel model = request -> {
            captured.set(request);
            return new ModelDraft(
                    "三个相同标记时可以选择清除。",
                    "当前玩家每回合只能执行一次这种三标记清除；四个相同标记则自动清除，并可能在同一回合再次触发。",
                    List.of(pageSixChunkId),
                    List.of(),
                    "HIGH");
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(randomPlaceholder, 0.02, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "Is clearing three matching wildlife tokens optional?",
                new QuestionContext(versionId, "ACTIONS", null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(captured.get().evidence()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo(pageSixChunkId);
            assertThat(source.excerpt()).contains("每回合只能这样做一次");
            assertThat(source.pageFrom()).isEqualTo(6);
        });
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(pageSixChunkId);
            assertThat(citation.pageFrom()).isEqualTo(6);
        });
    }

    @Test
    void retriesAVisualFactBackedRuleWhenTheQuestionAlreadyStatesItsCondition() {
        UUID pageChunkId = UUID.randomUUID();
        RuleEvidenceHit pageSource = new RuleEvidenceHit(
                pageChunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Draw phase",
                "Visual rulebook page evidence is available.",
                10,
                10,
                1.0);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(
                        10,
                        "Draw Zone, Discard Zone",
                        "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。",
                        95));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(pageNumbers).containsExactly(10);
                return List.of(pageSource);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                assertThat(request.evidence()).singleElement().satisfies(source -> assertThat(source.excerpt())
                        .contains("若抽骰区没有骰子", "弃骰区的所有骰子移回抽骰区"));
                return new ModelDraft(false, "Current state is incomplete.", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString().contains(
                        "condition written into a player's question",
                        "named replenishment condition",
                        "DIRECT_REPLENISHMENT_PROCEDURE");
                return new ModelDraft(
                        "把弃骰区全部移回抽骰区，再继续抽骰。",
                        "抽骰区为空时，先回收弃骰区的全部骰子到抽骰区；随后继续本次抽骰流程。",
                        List.of(pageChunkId),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(), visualFacts, pageLookup, model);

        var answer = service.answer(
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                new QuestionContext(versionId, "ROUND_STRUCTURE", "DRAW", 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("弃骰区");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(10);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void publishesACitedDirectReplenishmentProcedureOnlyAfterTwoEvidenceBackedAbstentions() {
        UUID pageChunkId = UUID.randomUUID();
        RuleEvidenceHit pageSource = new RuleEvidenceHit(
                pageChunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Draw phase",
                "Visual rulebook page evidence is available.",
                10,
                10,
                1.0);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(
                        10,
                        "Draw Zone, Discard Zone",
                        "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。",
                        95));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(pageSource);
            }
        };
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(false, "Unable to compose.", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                return new ModelDraft(false, "Still unable to compose.", null, null, List.of(), List.of(), "LOW");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(), visualFacts, pageLookup, model);

        var answer = service.answer(
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                new QuestionContext(versionId, "ROUND_STRUCTURE", "DRAW", 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).isEqualTo("若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。");
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(pageChunkId);
    }

    @Test
    void retrievesExplicitReplenishmentEvidenceBeforeComposingAnExhaustedDrawZoneAnswer() {
        UUID pageChunkId = UUID.randomUUID();
        RuleEvidenceHit pageSource = new RuleEvidenceHit(
                pageChunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Draw phase",
                "Visual rulebook page evidence is available.",
                10,
                10,
                1.0);
        List<String> visualQueries = new java.util.ArrayList<>();
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> {
            visualQueries.add(query);
            return query.contains("耗尽")
                    ? List.of(visualFact(
                            10,
                            "Draw Zone, Discard Zone",
                            "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。",
                            95))
                    : List.of();
        };
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(pageNumbers).contains(10);
                return List.of(pageSource);
            }
        };
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::chunkId).contains(pageChunkId);
            return new ModelDraft(
                    "抽骰区为空时，回收弃骰区后继续抽骰。",
                    "将弃骰区的全部骰子移回抽骰区，再继续本次抽骰。",
                    List.of(pageChunkId),
                    List.of(),
                    "HIGH");
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(), visualFacts, pageLookup, model);

        var answer = service.answer(
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                new QuestionContext(versionId, "ROUND_STRUCTURE", "DRAW", 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(visualQueries).anyMatch(query -> query.contains("耗尽")
                && query.contains("回收")
                && query.contains("继续"));
    }

    @Test
    void replacesAnAnswerThatCitesDrawAmountInsteadOfTheDirectReplenishmentProcedure() {
        UUID replenishmentChunkId = UUID.randomUUID();
        UUID drawAmountChunkId = UUID.randomUUID();
        RuleEvidenceHit replenishment = new RuleEvidenceHit(
                replenishmentChunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Draw phase",
                "Visual rulebook page evidence is available.",
                10,
                10,
                1.0);
        RuleEvidenceHit drawAmount = new RuleEvidenceHit(
                drawAmountChunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Draw amount",
                "Visual rulebook page evidence is available.",
                11,
                11,
                1.0);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> query.contains("耗尽")
                ? List.of(visualFact(
                        10,
                        "Draw Zone, Discard Zone",
                        "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。",
                        95))
                : List.of(visualFact(
                        11,
                        "Draw Amount",
                        "基础抽牌量为9，手形标记和红线会调整抽牌量。",
                        80));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(replenishment, drawAmount);
            }
        };
        RuleAnswerModel model = request -> new ModelDraft(
                "按抽牌量计算。",
                "抽牌量由基础值和修正值组成，因此按剩余数量抽取。",
                List.of(drawAmountChunkId),
                List.of(),
                "HIGH");
        var service = answerService(
                (documentVersionId, query, options) -> List.of(), visualFacts, pageLookup, model);

        var answer = service.answer(
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                new QuestionContext(versionId, "ROUND_STRUCTURE", "DRAW", 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("弃骰区", "继续抽骰");
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(replenishmentChunkId);
    }

    @Test
    void enrichesExtractedTextWithSamePageVisualFactsWhenInlineIconsAreMissing() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidenceHit textSource = new RuleEvidenceHit(
                chunkId,
                versionId,
                "SPECIAL_RULE",
                "Wager",
                "Use the wager only if you have at least 2  . Place 2  on it.",
                14,
                14,
                0.8);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(14, "victory point token", "The missing icon is a victory point token.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(textSource);
            }
        };
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).singleElement().satisfies(source -> assertThat(source.excerpt())
                    .contains("at least 2", "victory point token"));
            return new ModelDraft(
                    "支付2个胜利点。",
                    "把2个胜利点放在赌注卡上。",
                    List.of(chunkId),
                    List.of(),
                    "HIGH");
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(textSource, 0.8, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "What token pays for the wager?",
                new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(answer.shortVerdict()).contains("胜利点");
    }

    @Test
    void repairsAnUnresolvedCrossPageIconIdentityBeforePublishingTheAnswer() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "SETUP",
                "Player setup",
                "Give each player 1 score token in public and 2 energy tokens hidden behind the screen.",
                3,
                3,
                0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "SPECIAL_RULE",
                "Wager",
                "To make the wager, place 2  on the card. If you win, keep the 2  and gain 2 additional  .",
                9,
                9,
                0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token; energy token", "The red icon labels score token; the green icon labels energy token.", 80),
                visualFact(9, "Wager; 2 🔴", "Place 2 🔴 on the wager; the icon is likely an energy token.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "下注支付2个能量令牌（🔴）。",
                        "获胜后保留2个🔴并额外获得2个🔴。",
                        List.of(wager.chunkId(), setup.chunkId()),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString()
                        .contains("VISUAL_IDENTITY", "worked arithmetic", "set answerable to false");
                return new ModelDraft(
                        "下注放置2个得分令牌（score token）。",
                        "获胜时保留已经放置的2个得分令牌，再获得2个得分令牌；净增加2个。",
                        List.of(wager.chunkId(), setup.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "下注支付哪一种令牌，获胜后怎么结算？",
                new QuestionContext(versionId, "SPECIAL_RULE", null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("得分令牌").doesNotContain("🔴", "能量令牌");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(9, 3);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void refusesAnAnswerWhenVisualIdentityRepairStillUsesAnUnresolvedIcon() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Payment", "Pay 2  to activate.", 5, 5, 0.8);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) ->
                List.of(visualFact(5, "2 🔴", "The payment shows 2 🔴; the resource name is uncertain.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(source);
            }
        };
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return unresolvedDraft(source.chunkId());
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                return unresolvedDraft(source.chunkId());
            }

            private ModelDraft unresolvedDraft(UUID citationId) {
                return new ModelDraft(
                        "支付2个🔴。", "现有页面只显示🔴图标。", List.of(citationId), List.of(), "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(new HybridEvidenceHit(source, 0.8, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "激活时支付哪一种资源？",
                new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.shortVerdict()).contains("无法从现有证据中可靠确定");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void usesAnExplicitCrossPageIconMappingWithoutAStochasticSecondModelPass() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token in public and 2 energy tokens hidden.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager",
                "Place 2  on the wager. If you win, keep the 2  and gain 2 additional  .", 9, 9, 0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token; energy token", "The reference page labels both components.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "下注放置2个得分令牌（score token）。",
                        "获胜后保留下注，并额外获得2个得分令牌。",
                        List.of(wager.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return previousDraft;
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "下注支付哪一种令牌？", new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("得分令牌", "score token");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(9, 3);
        assertThat(revisions).hasValue(0);
    }

    @Test
    void ignoresAnUncitedSupplementaryIconMappingForAnUnrelatedRoundQuestion() {
        RuleEvidenceHit round = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ROUND_END", "Going out",
                "The first player to empty their hand takes first place. Others continue until one player remains.",
                4, 4, 0.9);
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager", "Place 2 on the wager.", 9, 9, 0.7);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token", "The component is labeled 'score token'.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "不会立刻结束，其他玩家继续决定后续名次。",
                        "你出完手牌后取得第一名并退出本轮，其余玩家继续。",
                        List.of(round.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return previousDraft;
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(new HybridEvidenceHit(round, 0.9, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "我第一个出完手牌后，其他玩家继续吗？",
                new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).doesNotContain("score token");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(4);
        assertThat(revisions).hasValue(0);
    }

    @Test
    void repairsAContinuationThatAssignsTheNextActionToAPlayerWhoIsOut() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ROUND_STRUCTURE", "Next eligible player",
                "The trick winner leads next. If that winner is out of cards, the next player to the left starts instead.",
                8, 8, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "你出完所有手牌后，下一墩由你领出。",
                        "你已经无牌并退出本轮，但默认仍由你开始下一墩。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                int revision = revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("INACTIVE_ACTOR", "successor"));
                if (revision == 1) {
                    return new ModelDraft(false, "uncertain successor", null, null, List.of(), List.of(), "LOW");
                }
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("EVIDENCED_SUCCESSOR_RULE"));
                return new ModelDraft(
                        "你出完所有手牌后，由你左手边的下一位玩家领出下一墩。",
                        "你退出本轮；下一墩不由你领出，改由左手边下一位仍在本轮中的玩家开始。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(new HybridEvidenceHit(source, 0.9, 1, null, false)),
                model);

        var answer = service.answer(
                "我出完手牌后，下一墩由谁领出？",
                new QuestionContext(versionId, "ROUND_STRUCTURE", null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("左手边的下一位玩家");
        assertThat(revisions).hasValue(2);
    }

    @Test
    void repairsAResourceIconThatTheDraftAlsoMisstatesAsAHandSizeRequirement() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token in public and 2 energy tokens hidden.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager",
                "Use the wager only if you have at least 2  . Place 2  on it; it is not used as a card this round.",
                9, 9, 0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token; energy token", "The reference page labels both components.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "放置2个得分令牌（score token）。",
                        "至少需要2张基础牌才能发动；图标对应基础牌数量。",
                        List.of(wager.chunkId(), setup.chunkId()),
                        List.of("手牌不足2张时不能发动。"),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString()
                        .contains("RESOURCE_CARD_CONFLATION", "fewer cards", "named token");
                return new ModelDraft(
                        "放置2个得分令牌（score token）。",
                        "至少拥有2个得分令牌时才能发动；放置后，该牌本轮不再作为手牌使用。",
                        List.of(wager.chunkId(), setup.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "发动时支付哪一种资源？", new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.explanation()).contains("2个得分令牌").doesNotContain("2张基础牌", "手牌不足");
        assertThat(revisions).hasValue(1);
    }

    @Test
    void allowsAnExplicitDenialOfAHandSizeRequirementAfterVisualReconciliation() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token and 2 energy tokens.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager",
                "Use the wager only if you have at least 2 . Place 2 on it.", 9, 9, 0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token", "The component is labeled 'score token'.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "需要2个得分令牌（score token），不需要至少2张手牌。",
                        "发动前检查并放置得分令牌；这张牌本轮不参与出牌，所以使用后你的手牌会更少。",
                        List.of(wager.chunkId(), setup.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return previousDraft;
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "需要至少2张手牌才能发动吗？", new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("不需要至少2张手牌", "score token");
        assertThat(answer.explanation()).contains("使用后你的手牌会更少");
        assertThat(revisions).hasValue(0);
    }

    @Test
    void replacesAnImprovisedGlyphWithTheResolvedPrintedComponentName() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager",
                "Place 2 on the wager.", 9, 9, 0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token", "The component is labeled 'score token'.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "放置2个得分令牌（score token，🔴）。",
                        "获胜后保留2个🔴。",
                        List.of(wager.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("MAPPED_COMPONENT_GLYPH"));
                return previousDraft;
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "下注支付哪一种令牌？", new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("score token").doesNotContain("🔴");
        assertThat(answer.explanation()).contains("score token").doesNotContain("🔴");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(9, 3);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void putsSpecificVisualInstructionsBeforeGenericVisualIntentAnchors() {
        String placeholder =
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
        RuleEvidenceHit components = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "COMPONENTS", "Visual rulebook page 3", placeholder, 3, 3, 0.02);
        RuleEvidenceHit placement = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Visual rulebook page 7", placeholder, 7, 7, 0.02);
        RuleEvidenceHit overview = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Visual rulebook page 8", placeholder, 8, 8, 0.02);
        Map<Integer, RuleEvidenceHit> pages = Map.of(3, components, 7, placement, 8, overview);
        AtomicInteger retrievalCalls = new AtomicInteger();
        HybridRuleSearch hybridSearch = (documentVersionId, query, options) -> switch (retrievalCalls.getAndIncrement()) {
            case 0 -> List.of(new HybridEvidenceHit(components, 0.04, 1, null, false));
            case 1 -> List.of(new HybridEvidenceHit(overview, 0.04, 1, null, false));
            default -> List.of(new HybridEvidenceHit(components, 0.03, 1, null, false));
        };
        AtomicInteger visualCalls = new AtomicInteger();
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) ->
                switch (visualCalls.getAndIncrement()) {
                    case 0 -> List.of(
                            visualFact(3, "Wildlife Tokens", "The game contains wildlife tokens.", 20),
                            visualFact(7, "Place the Tile and Token", "Place the token on a legal tile.", 60));
                    case 1 -> List.of(
                            visualFact(8, "Keystone Tile", "A matching token on a Keystone grants a Nature Token.", 50),
                            visualFact(7, "Place the Tile and Token", "Place the token on a legal tile.", 60));
                    default -> List.of(
                            visualFact(3, "Wildlife Tokens", "The game contains wildlife tokens.", 20),
                            visualFact(8, "Keystone Tile", "A matching token on a Keystone grants a Nature Token.", 50));
                };
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return pageNumbers.stream().map(pages::get).toList();
            }
        };
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::pageFrom)
                    .startsWith(7)
                    .contains(8);
            return new ModelDraft(
                    "放在显示对应动物图标的空板块上。",
                    "可以放在新板块或环境中其他合法板块；放在关键石板块上获得一个自然标记。",
                    List.of(placement.chunkId(), overview.chunkId()),
                    List.of("每个板块最多放一个动物标记。"),
                    "HIGH");
        };
        var service = answerService(hybridSearch, visualFacts, pageLookup, model);

        var answer = service.answer(
                "Where can I place a Wildlife Token, and what do I gain after placing one on a Keystone Tile?",
                new QuestionContext(versionId, "ACTIONS", "ACTION_PHASE", 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(7, 8);
    }

    @Test
    void passesUnderstoodGameplayContextToTheAnswerModel() {
        RuleEvidenceHit source = evidence("ACTIONS");
        UUID expansionId = UUID.randomUUID();
        AtomicReference<RuleAnswerModel.ModelRequest> captured = new AtomicReference<>();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    captured.set(request);
                    return new ModelDraft(
                            "可以执行。", "在行动阶段支付规则所列费用后执行。",
                            List.of(source.chunkId()), List.of(), "HIGH");
                });

        var answer = service.answer(
                "Can I take this action now?",
                new QuestionContext(versionId, "ACTIONS", "ACTION_PHASE", 4, Set.of(expansionId)));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(captured.get().questionType())
                .isEqualTo(com.rulepilot.assistant.domain.QuestionType.LESSON_STEP_FOLLOW_UP);
        assertThat(captured.get().context().currentLessonSection()).isEqualTo("ACTIONS");
        assertThat(captured.get().context().gamePhase()).isEqualTo("ACTION_PHASE");
        assertThat(captured.get().context().playerCount()).isEqualTo(4);
        assertThat(captured.get().context().activeExpansionCount()).isEqualTo(1);
    }

    @Test
    void honorsModelAbstentionWithoutPublishingGeneratedClaims() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        false,
                        "The evidence describes coins but not the requested bonus.",
                        "",
                        "",
                        List.of(),
                        List.of(),
                        "LOW"));

        var answer = service.answer(
                "How is the hidden bonus scored?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.shortVerdict()).contains("未能直接回答");
        assertThat(answer.shortVerdict()).doesNotContain("hidden bonus", "coins");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void retriesAnOrdinaryQuestionWhenRetrievedEvidenceDirectlyResolvesAnInitialAbstention() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Tidal gate",
                "After raising its sail, a ship may cross the tidal gate. The cost is the same as entering the current channel.",
                12, 12, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(false, "uncertain", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return new ModelDraft(
                        "先升起船帆；之后才能通过。",
                        "通过费用与进入当前航道的费用相同。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.04, 1, 1, true)), model);

        var answer = service.answer(
                "Can a ship cross the tidal gate?", new QuestionContext(versionId, "ACTIONS", null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(revisions).hasValue(1);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(source.chunkId());
    }

    @Test
    void putsModelProvidedCrossLanguageSearchPhrasesAheadOfSurfaceLanguageQueries() {
        RuleEvidenceHit directSource = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Wild suits",
                "A wild card may be treated as any required suit when matching an action.",
                6, 6, 0.9);
        RuleEvidenceHit unrelatedSource = evidence("ACTIONS");
        AtomicReference<String> firstQuery = new AtomicReference<>();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public List<String> rewriteRetrievalQueries(RetrievalQueryRequest request) {
                assertThat(request.question()).isEqualTo("万能牌能匹配行动花色吗？");
                return List.of("wild card matching action suit");
            }

            @Override
            public ModelDraft compose(ModelRequest request) {
                assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::chunkId)
                        .contains(directSource.chunkId());
                return new ModelDraft(
                        "可以按规则作为所需花色。",
                        "匹配行动时，万能牌可以视为任何所需花色。",
                        List.of(directSource.chunkId()), List.of(), "HIGH");
            }
        };
        var service = answerService(
                (version, query, options) -> {
                    firstQuery.compareAndSet(null, query);
                    return query.equals("wild card matching action suit")
                            ? List.of(new HybridEvidenceHit(directSource, 0.09, 1, 1, true))
                            : List.of(new HybridEvidenceHit(unrelatedSource, 0.03, 1, null, false));
                }, model);

        var answer = service.answer(
                "万能牌能匹配行动花色吗？", new QuestionContext(versionId, "ACTIONS", null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(firstQuery).hasValue("wild card matching action suit");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(6);
    }

    @Test
    void rejectsCitationThatWasNotRetrieved() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft("Unsupported", "Unsupported", List.of(UUID.randomUUID()), List.of(), "HIGH"));

        var answer = service.answer(
                "How is scoring resolved?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void missingContextStopsBeforeRetrievalAndModel() {
        AtomicBoolean called = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> {
                    called.set(true);
                    return List.of();
                },
                request -> {
                    called.set(true);
                    return null;
                });

        var answer = service.answer(
                "Can I play this card from my hand?",
                new QuestionContext(versionId, null, null, 3, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.clarification()).contains("GAME_PHASE", "SITUATION_DETAILS");
        assertThat(called).isFalse();
    }

    @Test
    void refusesWhenNoEvidenceWasRetrieved() {
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "What does this unknown symbol do?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void answersFromContextualSupplementaryRetrievalWhenPrimaryHasNoMatch() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicInteger retrievalCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> {
                    if (retrievalCalls.getAndIncrement() == 0) {
                        assertThat(options.sectionTypes()).isEmpty();
                        return List.of();
                    }
                    assertThat(query).contains("step prerequisite consequence exception", "ACTION PHASE", "4 players");
                    assertThat(options.sectionTypes()).contains("ACTIONS");
                    return List.of(new HybridEvidenceHit(source, 0.03, 1, null, true));
                },
                request -> new ModelDraft(
                        "可以执行。", "行动阶段允许执行该行动。",
                        List.of(source.chunkId()), List.of(), "MEDIUM"));

        var answer = service.answer(
                "Can I take this action now?",
                new QuestionContext(versionId, "ACTIONS", "ACTION_PHASE", 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(retrievalCalls).hasValue(2);
    }

    @Test
    void keepsHighestScoringEvidenceAcrossRetrievalIntents() {
        RuleEvidenceHit relevant = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Players begin with four credits.", 6, 6, 0.8);
        List<HybridEvidenceHit> primary = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new HybridEvidenceHit(
                        new RuleEvidenceHit(
                                UUID.randomUUID(), versionId, "SETUP", "Unrelated " + index,
                                "Unrelated setup detail " + index, 10 + index, 10 + index, 0.2),
                        0.01 + index * 0.001, index + 1, null, false))
                .toList();
        AtomicInteger retrievalCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> retrievalCalls.getAndIncrement() == 0
                        ? primary
                        : List.of(new HybridEvidenceHit(relevant, 0.05, 1, 1, true)),
                request -> {
                    assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::chunkId)
                            .contains(relevant.chunkId())
                            .doesNotContain(primary.get(1).evidence().chunkId());
                    return new ModelDraft(
                            "开局有 4 信用点。", "玩家开局获得 4 信用点。",
                            List.of(relevant.chunkId()), List.of(), "HIGH");
                });

        var answer = service.answer(
                "开局有多少信用点？", new QuestionContext(versionId, "SETUP", null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(relevant.chunkId());
    }

    @Test
    void letsDifferentRetrievalIntentsContributeDistinctEvidence() {
        RuleEvidenceHit ending = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "END_CONDITIONS", "Game end",
                "The game ends after the final round.", 20, 20, 0.8);
        RuleEvidenceHit ties = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "TIE_BREAKERS", "Ties",
                "The tied player with more credits wins.", 21, 21, 0.8);
        var repeatedRanking = List.of(
                new HybridEvidenceHit(ending, 0.04, 1, 1, false),
                new HybridEvidenceHit(ties, 0.03, 2, 2, false));
        var service = answerService(
                (version, query, options) -> repeatedRanking,
                request -> {
                    assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::chunkId)
                            .contains(ending.chunkId(), ties.chunkId());
                    return new ModelDraft(
                            "同分时比较信用点。", "游戏结束后，同分玩家比较信用点。",
                            List.of(ties.chunkId()), List.of(), "HIGH");
                });

        var answer = service.answer(
                "When does the game end, and how are ties resolved?",
                new QuestionContext(versionId, null, null, 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(ties.chunkId());
    }

    @Test
    void keepsPrimaryEvidenceWhenSupplementaryRetrievalFails() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger retrievalCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> {
                    if (retrievalCalls.getAndIncrement() == 0) {
                        return List.of(new HybridEvidenceHit(source, 0.03, 1, null, false));
                    }
                    throw new IllegalStateException("supplementary retrieval unavailable");
                },
                request -> new ModelDraft(
                        "每枚硬币一分。", "计算最终分数时，每枚硬币计一分。",
                        List.of(source.chunkId()), List.of(), "HIGH"));

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).hasSize(1);
        assertThat(retrievalCalls).hasValue(2);
    }

    @Test
    void reportsModelTimeoutWithoutLeakingAnswerContent() {
        RuleEvidenceHit source = evidence("ACTIONS");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    throw new RuleAnswerModelTimeoutException("provider details", new RuntimeException("secret"));
                });

        var answer = service.answer(
                "Which actions are available during a turn?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.MODEL_TIMEOUT);
        assertThat(answer.shortVerdict()).doesNotContain("provider details", "secret");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void rejectsVersionConflictBeforeCallingModel() {
        UUID otherVersion = UUID.randomUUID();
        RuleEvidenceHit wrongVersion = new RuleEvidenceHit(
                UUID.randomUUID(), otherVersion, "SCORING", "Scoring", "One point.", 2, 2, 0.7);
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(wrongVersion, 0.03, 1, null, false)),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "How does scoring work?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.VERSION_CONFLICT);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void refusesConflictingSnapshotsBeforeCallingModel() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidenceHit first = new RuleEvidenceHit(
                chunkId, versionId, "SCORING", "Scoring", "Each coin scores one point.", 8, 8, 0.8);
        RuleEvidenceHit conflicting = new RuleEvidenceHit(
                chunkId, versionId, "SCORING", "Scoring", "Each coin scores two points.", 8, 8, 0.7);
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(
                        new HybridEvidenceHit(first, 0.03, 1, null, false),
                        new HybridEvidenceHit(conflicting, 0.02, 2, null, false)),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "How does scoring work?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.shortVerdict()).contains("冲突");
        assertThat(modelCalled).isFalse();
    }

    @Test
    void blocksLowConfidenceAnswerRejectedByCritic() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger criticCalls = new AtomicInteger();
        GeneratedContentCritic rejectingCritic = (request, risk) -> {
            criticCalls.incrementAndGet();
            assertThat(request.taskContext().objective()).contains("how does scoring work?");
            assertThat(request.taskContext().requiredCoverage()).contains("RULE_QUERY", "player count not provided");
            return new GeneratedContentCritic.Review(true, List.of(new Issue(
                    IssueType.OVERREACH, 1, List.of(source.chunkId()), "The conclusion exceeds the evidence.")));
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        "Coins always decide the winner.", "Coins decide every tie.",
                        List.of(source.chunkId()), List.of(), "LOW"),
                rejectingCritic);

        var answer = service.answer(
                "How does scoring work?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.shortVerdict()).contains("一致性审查");
        assertThat(answer.citations()).isEmpty();
        assertThat(criticCalls).hasValue(1);
    }

    @Test
    void alwaysCritiquesContextResolvedFollowUps() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicReference<GeneratedContentCritic.ReviewRisk> capturedRisk = new AtomicReference<>();
        GeneratedContentCritic recordingCritic = (request, risk) -> {
            capturedRisk.set(risk);
            assertThat(request.taskContext().objective())
                    .contains("那还能再做一次吗", "执行一次主要行动后还能执行自由行动吗");
            assertThat(request.taskContext().requiredCoverage()).contains("repeatability claim");
            return new GeneratedContentCritic.Review(true, List.of());
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        "可以继续。", "规则允许在主要行动后执行自由行动。",
                        List.of(source.chunkId()), List.of(), "HIGH"),
                recordingCritic);

        var answer = service.answer(
                "那还能再做一次吗？",
                new QuestionContext(
                        versionId, "ACTIONS", null, 4, Set.of(),
                        "执行一次主要行动后还能执行自由行动吗？"));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(capturedRisk.get()).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void passesThePlayerLearningIntentToCompositionAndCritique() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicReference<RuleAnswerModel.ModelRequest> modelRequest = new AtomicReference<>();
        AtomicReference<GeneratedContentCritic.ReviewRequest> criticRequest = new AtomicReference<>();
        AtomicReference<GeneratedContentCritic.ReviewRisk> criticRisk = new AtomicReference<>();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, true)),
                request -> {
                    modelRequest.set(request);
                    return new ModelDraft(
                            "记住一个重点。", "先支付费用，再执行行动结果。",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                (request, risk) -> {
                    criticRequest.set(request);
                    criticRisk.set(risk);
                    return new GeneratedContentCritic.Review(false, List.of());
                });

        var answer = service.answer(
                "请讲简单一点。",
                new QuestionContext(
                        versionId, "ACTIONS", null, 4, Set.of(), null, LearningIntent.SIMPLIFY));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(modelRequest.get().context().learningIntent()).isEqualTo(LearningIntent.SIMPLIFY);
        assertThat(criticRequest.get().taskContext().requiredCoverage()).contains("SIMPLIFY");
        assertThat(criticRisk.get()).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void revisesRejectedLearningResponseWithBoundedCriticFeedback() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicInteger compositions = new AtomicInteger();
        AtomicInteger revisions = new AtomicInteger();
        AtomicReference<List<String>> revisionFeedback = new AtomicReference<>();
        RuleAnswerModel adaptiveModel = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                compositions.incrementAndGet();
                return new ModelDraft(
                        "可以不限次数执行。", "可以在主要行动后任意次执行自由行动。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                revisionFeedback.set(feedback);
                return new ModelDraft(
                        "自由行动可以在主要行动后执行。",
                        "规则只说明自由行动的时机；现有证据没有说明可重复多少次。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }
        };
        AtomicInteger criticCalls = new AtomicInteger();
        GeneratedContentCritic correctingCritic = (request, risk) -> {
            assertThat(risk).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
            if (criticCalls.getAndIncrement() == 0) {
                return new GeneratedContentCritic.Review(true, List.of(new Issue(
                        IssueType.OVERREACH,
                        1,
                        List.of(source.chunkId()),
                        "Evidence establishes timing but not unlimited frequency.")));
            }
            assertThat(request.claims()).extracting(GeneratedContentCritic.Claim::text)
                    .noneMatch(claim -> claim.contains("不限次数") || claim.contains("任意次"));
            return new GeneratedContentCritic.Review(true, List.of());
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, true)),
                adaptiveModel,
                correctingCritic);

        var answer = service.answer(
                "请讲简单一点。",
                new QuestionContext(
                        versionId, "ACTIONS", null, 4, Set.of(), null, LearningIntent.SIMPLIFY));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.explanation()).contains("没有说明可重复多少次");
        assertThat(compositions).hasValue(1);
        assertThat(revisions).hasValue(1);
        assertThat(criticCalls).hasValue(2);
        assertThat(revisionFeedback.get()).containsExactly(
                "OVERREACH: Evidence establishes timing but not unlimited frequency.");
    }

    @Test
    void critiquesAndRepairsAnyDocumentSpecificLiveTableRuling() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Tidal gate",
                "A ship may cross the tidal gate only after raising its sail. Its crossing cost is the same as "
                        + "the cost of entering the current channel.",
                12, 12, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "穿过潮汐门固定支付3枚硬币。", "无需检查船帆状态。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anyMatch(message -> message.contains("prerequisite and relative cost"));
                return new ModelDraft(
                        "先升起船帆；费用与进入当前航道相同。",
                        "潮汐门使用相对费用，不能脱离当前航道写成固定数字。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }
        };
        AtomicInteger reviews = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> {
            assertThat(risk).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
            int call = reviews.getAndIncrement();
            return call == 0
                    ? new GeneratedContentCritic.Review(true, List.of(new Issue(
                            IssueType.CONTRADICTION,
                            1,
                            List.of(source.chunkId()),
                            "The prerequisite and relative cost are replaced by unsupported claims.")))
                    : new GeneratedContentCritic.Review(true, List.of());
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, true)),
                model,
                critic);

        var answer = service.answer(
                "我的船现在能穿过潮汐门吗，费用是多少？",
                new QuestionContext(versionId, "ACTIONS", "主要行动", 4, Set.of()),
                "alice",
                UUID.randomUUID());

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("先升起船帆", "相同");
        assertThat(revisions).hasValue(1);
        assertThat(reviews).hasValue(2);
    }

    @Test
    void reconsidersALiveTableAbstentionUsingOnlyTheCurrentDocumentsEvidence() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "TIDAL GATE",
                "After raising its sail, a ship may cross the tidal gate. The cost is the same as entering the "
                        + "current channel.",
                12, 12, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(false, "uncertain", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString()
                        .contains("EVIDENCE_SUFFICIENCY", "conditional branch", "relative rules");
                return new ModelDraft(
                        "未升起船帆前不能通过；升起后费用与进入当前航道相同。",
                        "先检查船帆条件，再沿用当前航道的进入费用。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.04, 1, 1, true)),
                model);

        var answer = service.answer(
                "我还没有升起船帆，现在能穿过潮汐门吗？之后费用怎么算？",
                new QuestionContext(versionId, "ACTIONS", "主要行动", 4, Set.of()),
                "alice",
                UUID.randomUUID());

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(12);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void returnsOwnedConfirmedRulingBeforeCacheRetrievalAndModel() {
        UUID rulingId = UUID.randomUUID();
        UUID expansionId = UUID.randomUUID();
        RuleEvidenceHit source = evidence("SCORING");
        AtomicBoolean downstreamCalled = new AtomicBoolean();
        RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfirmedRulingLookup lookup = (documentVersionId, expansionIds, question, username) -> {
            assertThat(documentVersionId).isEqualTo(versionId);
            assertThat(expansionIds).containsExactly(expansionId);
            assertThat(question).isEqualTo("how are coins scored?");
            assertThat(username).isEqualTo("alice");
            return Optional.of(new ConfirmedRulingLookup.ConfirmedAnswer(
                    rulingId,
                    versionId,
                    "Use the confirmed score.",
                    "Each remaining coin scores one point.",
                    List.of(new ConfirmedRulingLookup.Citation(
                            source.chunkId(), versionId, source.sectionType(), source.heading(), source.excerpt(), 8, 8)),
                    List.of(),
                    "HIGH",
                    false,
                    4));
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> {
                    downstreamCalled.set(true);
                    return List.of();
                },
                request -> {
                    downstreamCalled.set(true);
                    return null;
                },
                new InMemoryAnswerCache(),
                rateLimiter,
                new MutableRuleDataVersion(),
                lookup,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);

        StructuredRuleAnswer answer = service.answer(
                "How are coins scored?",
                new QuestionContext(versionId, "SCORING", null, 3, Set.of(expansionId)),
                "alice",
                null);

        assertThat(answer.shortVerdict()).isEqualTo("Use the confirmed score.");
        assertThat(answer.confirmedRulingId()).isEqualTo(rulingId);
        assertThat(answer.confirmedRulingVersion()).isEqualTo(4);
        assertThat(downstreamCalled).isFalse();
        assertThat(rateLimiter.userChecks).isZero();
        assertThat(metrics.counter("rulepilot.answer.requests", "source", "confirmed-ruling").count())
                .isEqualTo(1);
    }

    @Test
    void naturallyMissesOldCacheEntryAfterRuleDataVersionChanges() {
        RuleEvidenceHit source = evidence("SCORING");
        InMemoryAnswerCache cache = new InMemoryAnswerCache();
        RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        MutableRuleDataVersion versions = new MutableRuleDataVersion();
        AtomicInteger modelCalls = new AtomicInteger();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.", "Each coin contributes one point.",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                cache,
                rateLimiter,
                versions,
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);
        QuestionContext context = new QuestionContext(versionId, "SCORING", null, 3, Set.of());

        StructuredRuleAnswer first = service.answer("How are coins scored?", context);
        StructuredRuleAnswer second = service.answer("How are coins scored?", context);
        versions.increment(versionId);
        StructuredRuleAnswer afterRuleChange = service.answer("How are coins scored?", context);

        assertThat(second).isEqualTo(first);
        assertThat(afterRuleChange).isEqualTo(first);
        assertThat(modelCalls).hasValue(2);
        assertThat(rateLimiter.userChecks).isEqualTo(3);
        assertThat(rateLimiter.modelAcquires).isEqualTo(2);
        assertThat(rateLimiter.releases).isEqualTo(2);
        assertThat(metrics.counter("rulepilot.answer.cache.requests", "result", "miss").count()).isEqualTo(2);
        assertThat(metrics.counter("rulepilot.answer.cache.requests", "result", "hit").count()).isEqualTo(1);
    }

    @Test
    void retrievesAndReturnsValidatedAnswerWhenCacheIsUnavailable() {
        RuleEvidenceHit source = evidence("SCORING");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        AtomicInteger modelCalls = new AtomicInteger();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.", "Each coin contributes one point.",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                new UnavailableAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);

        StructuredRuleAnswer answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId, "SCORING", null, 3, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).hasSize(1);
        assertThat(modelCalls).hasValue(1);
        assertThat(metrics.counter("rulepilot.answer.cache.errors", "operation", "read").count()).isEqualTo(1);
        assertThat(metrics.counter("rulepilot.answer.cache.errors", "operation", "write").count()).isEqualTo(1);
    }

    @Test
    void bypassesTheCacheWhenRuleDataVersionIsUnavailableButEvidenceIsReadable() {
        RuleEvidenceHit source = evidence("SCORING");
        InMemoryAnswerCache cache = new InMemoryAnswerCache();
        AtomicInteger modelCalls = new AtomicInteger();
        RuleDataVersion unavailableVersion = new RuleDataVersion() {
            @Override
            public long current(UUID documentVersionId) {
                throw new IllegalArgumentException("document version does not exist");
            }

            @Override
            public long increment(UUID documentVersionId) {
                throw new UnsupportedOperationException("rule data is unavailable");
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.", "Each coin contributes one point.",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                cache,
                new RecordingRateLimiter(),
                unavailableVersion,
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
        QuestionContext context = new QuestionContext(versionId, "SCORING", null, 3, Set.of());

        StructuredRuleAnswer first = service.answer("How are coins scored?", context);
        StructuredRuleAnswer second = service.answer("How are coins scored?", context);

        assertThat(first.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(second.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(modelCalls).hasValue(2);
        assertThat(cache.values).isEmpty();
    }

    @Test
    void repairsAnEndOfTurnAnswerUntilItCitesTheDirectProcedure() {
        RuleEvidenceHit setupOnly = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Event deck", "Put the event deck beside the board.", 3, 3, 0.9);
        RuleEvidenceHit turnProcedure = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "GENERAL", "Turn end", "At the end of your turn, draw an event card and resolve its effect.", 6, 6, 0.8);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "Draw an event card.",
                        "After ending your turn, draw and resolve the event.",
                        List.of(setupOnly.chunkId()),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                assertThat(feedback).anyMatch(item -> item.startsWith("END_TURN_PROCEDURE_CITATION"));
                revisions.incrementAndGet();
                return new ModelDraft(
                        "Draw an event card and resolve it.",
                        "After ending your turn, draw the event card and carry out its effect.",
                        List.of(turnProcedure.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (version, query, options) -> query.startsWith("completed turn draw reveal")
                        ? List.of(
                                new HybridEvidenceHit(setupOnly, 0.04, 1, null, false),
                                new HybridEvidenceHit(turnProcedure, 0.03, 2, null, false))
                        : List.of(new HybridEvidenceHit(setupOnly, 0.04, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "我结束自己的回合后，事件牌要怎样处理？",
                new QuestionContext(versionId, "ROUND_STRUCTURE", "TURN", 3, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(turnProcedure.chunkId());
        assertThat(revisions).hasValue(1);
    }

    @Test
    void stopsBeforeRetrievalWhenRateLimitStorageIsUnavailable() {
        AtomicBoolean retrievalCalled = new AtomicBoolean();
        RuleAnswerRateLimiter unavailableLimiter = new RuleAnswerRateLimiter() {
            @Override
            public void checkUser(String username) {
                throw new RuleAnswerRateLimitUnavailableException(5, new IllegalStateException("Redis unavailable"));
            }

            @Override
            public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
                throw new AssertionError("model permit must not be acquired");
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> {
                    retrievalCalled.set(true);
                    return List.of();
                },
                request -> null,
                new InMemoryAnswerCache(),
                unavailableLimiter,
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.answer(
                        "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of())))
                .isInstanceOf(RuleAnswerRateLimitUnavailableException.class);
        assertThat(retrievalCalled).isFalse();
    }

    private StructuredRuleAnswerService answerService(HybridRuleSearch retrieval, RuleAnswerModel model) {
        return answerService(retrieval, model, acceptedCritic());
    }

    private StructuredRuleAnswerService answerService(
            HybridRuleSearch retrieval, RuleAnswerModel model, GeneratedContentCritic critic) {
        return new StructuredRuleAnswerService(
                understanding, retrieval, model, new InMemoryAnswerCache(), new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                critic,
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
    }

    private StructuredRuleAnswerService answerService(
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup,
            RuleAnswerModel model) {
        return new StructuredRuleAnswerService(
                understanding,
                retrieval,
                visualFacts,
                evidenceLookup,
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
    }

    private GeneratedContentCritic acceptedCritic() {
        return (request, risk) -> new GeneratedContentCritic.Review(false, List.of());
    }

    private ConfirmedRulingLookup noConfirmedRulings() {
        return (documentVersionId, expansionIds, question, username) -> Optional.empty();
    }

    private RuleEvidenceHit evidence(String sectionType) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, "Scoring", "Each coin is worth one point.", 8, 8, 0.8);
    }

    private VisualRulebookPageFactSearch.PageFactMatch visualFact(
            int page, String printedTerms, String summary, double score) {
        return new VisualRulebookPageFactSearch.PageFactMatch(
                page, printedTerms, summary, List.of(printedTerms), score);
    }

    private static final class InMemoryAnswerCache implements RuleAnswerCache {
        private final Map<AnswerCacheKey, StructuredRuleAnswer> values = new HashMap<>();

        @Override
        public Optional<StructuredRuleAnswer> find(AnswerCacheKey key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void save(AnswerCacheKey key, StructuredRuleAnswer answer) {
            values.put(key, answer);
        }
    }

    private static final class UnavailableAnswerCache implements RuleAnswerCache {
        @Override
        public Optional<StructuredRuleAnswer> find(AnswerCacheKey key) {
            throw new IllegalStateException("Redis unavailable");
        }

        @Override
        public void save(AnswerCacheKey key, StructuredRuleAnswer answer) {
            throw new IllegalStateException("Redis unavailable");
        }
    }

    private static final class RecordingRateLimiter implements RuleAnswerRateLimiter {
        private int userChecks;
        private int modelAcquires;
        private int releases;

        @Override
        public void checkUser(String username) {
            userChecks++;
        }

        @Override
        public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
            modelAcquires++;
            return () -> releases++;
        }
    }

    private static final class MutableRuleDataVersion implements RuleDataVersion {
        private long value = 1;

        @Override
        public long current(UUID documentVersionId) {
            return value;
        }

        @Override
        public long increment(UUID documentVersionId) {
            return ++value;
        }
    }
}
