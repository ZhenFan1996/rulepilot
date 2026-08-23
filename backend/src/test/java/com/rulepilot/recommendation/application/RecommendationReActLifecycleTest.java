package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogFilters;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.TurnCheckpoint;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RecommendationReActLifecycleTest {

    @Test
    void doesNotCommitAValidSiblingWhenAnotherTypedPreferenceIsInvalid() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "mixed-invalid-read",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"preferenceUpdates\":["
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                                + "{\"field\":\"playerCount\",\"value\":99,\"evidence\":\"U1\",\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}"),
                action(
                        "transparent-finish",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"我刚才没有可靠地读出完整桌面人数，所以不会把这个猜测写进条件；你可以直接告诉我总人数，我再继续挑。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(500, "Quiet Harbor", "静港", "A calm game."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "大约想玩一小时，但我只知道会带朋友来，还没确认最后总共有几个人。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().durationMinutes()).isNull();
        assertThat(response.profile().playerCount()).isNull();
        assertThat(catalog.searches).hasValue(0);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:PLAYERS_OUT_OF_RANGE")
                .doesNotContain("UPDATE_PREFERENCES", "SEARCH_BGG_CATALOG");

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsAnInvalidPreferenceAtomicallyAndLetsTheSameCallRepairWithoutCatalogSideEffects() {
        String invalidBrowse = "{\"limit\":1,\"preferenceUpdates\":[{\"field\":\"playerCount\",\"value\":4,\"evidence\":\"U99\",\"evidenceClassification\":\"DIRECT\"}]}";
        String validBrowse = "{\"limit\":1,\"preferenceUpdates\":[{\"field\":\"playerCount\",\"value\":4,\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}";
        ScriptedModel model = new ScriptedModel(List.of(
                action("invalid-read-1", BoardGameRecommendationAgent.BROWSE_TOOL, invalidBrowse),
                action("invalid-read-2", BoardGameRecommendationAgent.BROWSE_TOOL, invalidBrowse),
                action("valid-read", BoardGameRecommendationAgent.BROWSE_TOOL, validBrowse),
                action(
                        "finish",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"selections\":[{\"bggId\":501,\"reason\":\"它让四个人都能很快参与讨论，而且一小时内可以从容结束。\"}],\"requestedCount\":1,\"playerReply\":\"我会先从这一款开始。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(501, "Open Table", "开放桌面", "A social game."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "周六我们四个人聚会，两位朋友几乎没玩过桌游；先直接给我一个容易聊起来的选择。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(value -> {
                    assertThat(value.game().ranking().bggId()).isEqualTo(501);
                    assertThat(value.replyParts()).singleElement().satisfies(part -> {
                        assertThat(part.role())
                                .isEqualTo(BoardGameRecommendationAgent.ReplyPartRole.WHY_FIT);
                        assertThat(part.claim().text())
                                .isEqualTo("它让四个人都能很快参与讨论，而且一小时内可以从容结束。");
                    });
                });
        assertThat(response.profile().playerCount()).satisfies(range -> {
            assertThat(range.exact()).isTrue();
            assertThat(range.minimum()).isEqualTo(4);
        });
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:PREFERENCE_EVIDENCE_NOT_GROUNDED", "REUSED_ACTION_ERROR")
                .doesNotContain("REJECTED_REPEATED_ACTION");

        Request afterRepeatedError = model.requests.get(2);
        assertThat(afterRepeatedError.messages()).hasSize(6);
        assertThat(afterRepeatedError.messages().get(3).toolCallId()).isEqualTo("invalid-read-1");
        assertThat(afterRepeatedError.messages().get(5).toolCallId()).isEqualTo("invalid-read-2");
        assertThat(afterRepeatedError.messages().get(3).content())
                .contains("PREFERENCE_EVIDENCE_NOT_GROUNDED");
        assertThat(afterRepeatedError.messages().get(5).content())
                .contains("PREFERENCE_EVIDENCE_NOT_GROUNDED");

        loop.stopBoundedCalls();
    }

    @Test
    void reusesASuccessfulReadWhileKeepingOneBoundedCurrentStateViewAndEveryCallResultPair() {
        String browse = "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"offset\":0}";
        String userRequest = "我们没有明确目标，只想找一款四个人第一次见面也不会冷场的桌游。";
        ScriptedModel model = new ScriptedModel(List.of(
                action("read-1", BoardGameRecommendationAgent.BROWSE_TOOL, browse),
                action("read-2", BoardGameRecommendationAgent.BROWSE_TOOL, browse),
                action(
                        "finish",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"selections\":[{\"bggId\":502,\"reason\":\"它的节奏适合边玩边聊，新手也能迅速跟上。\"}],\"requestedCount\":1,\"playerReply\":\"这桌我会先选它。\"}")));
        String longDescription = "Players build a shared garden and talk through each turn. "
                + "Every round adds another gentle choice. ".repeat(120)
                + "TAIL_SHOULD_NOT_REACH_CONTEXT";
        RecordingCatalog catalog = new RecordingCatalog(game(502, "Garden Voices", "花园絮语", longDescription));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        userRequest),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains("SEARCH_BGG_CATALOG", "REUSED_READ_OBSERVATION")
                .doesNotContain("REJECTED_REPEATED_ACTION");

        Request decisionAfterDuplicate = model.requests.get(2);
        assertThat(decisionAfterDuplicate.messages()).hasSize(6);
        assertThat(decisionAfterDuplicate.messages().get(3).toolCallId()).isEqualTo("read-1");
        assertThat(decisionAfterDuplicate.messages().get(5).toolCallId()).isEqualTo("read-2");
        List<Message> toolMessages = decisionAfterDuplicate.messages().stream()
                .filter(message -> message.role() == BoardGameRecommendationModel.Role.TOOL)
                .toList();
        assertThat(toolMessages).hasSize(2);
        assertThat(toolMessages.getFirst().content()).doesNotContain("\"turnState\"");
        assertThat(toolMessages.getLast().content())
                .contains("\"turnState\"", "publisherDescription")
                .doesNotContain("\"facts\"", "TAIL_SHOULD_NOT_REACH_CONTEXT");
        assertThat(toolMessages).allSatisfy(message -> assertThat(message.content()).doesNotContain(userRequest));
        assertThat(toolMessages.stream().filter(message -> message.content().contains("\"turnState\"")).count())
                .isEqualTo(1);

        loop.stopBoundedCalls();
    }

    @Test
    void readsAgainWhenAnInterveningActionChangesThePreferenceState() {
        String initialBrowse = "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"offset\":0}";
        String correctedBrowse = "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"offset\":1,"
                + "\"preferenceUpdates\":[{\"field\":\"durationMinutes\","
                + "\"value\":{\"minimum\":null,\"maximum\":60},"
                + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}";
        ScriptedModel model = new ScriptedModel(List.of(
                action("initial-read", BoardGameRecommendationAgent.BROWSE_TOOL, initialBrowse),
                action("corrected-read", BoardGameRecommendationAgent.BROWSE_TOOL, correctedBrowse),
                action("read-after-correction", BoardGameRecommendationAgent.BROWSE_TOOL, initialBrowse),
                action(
                        "finish",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"selections\":[{\"bggId\":504,\"reason\":\"它符合刚确认的一小时上限，也适合四个人轻松讨论。\"}],\"requestedCount\":1,\"playerReply\":\"按刚补充的时长，我会选这一款。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(
                504,
                "Evening Exchange",
                "晚间交换",
                "Four players exchange clues and complete a round in forty-five minutes."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们四个人想边聊边玩，刚确认今晚最多只有一小时；先帮我挑一款。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().durationMinutes()).satisfies(range ->
                assertThat(range.maximum()).isEqualTo(60));
        assertThat(catalog.searches).hasValue(3);
        assertThat(response.harness().actions())
                .contains("RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE")
                .doesNotContain("REUSED_READ_OBSERVATION");

        loop.stopBoundedCalls();
    }

    @Test
    void retriesAPreviouslyUnavailableTerminalActionAfterAReadChangesTheTurnState() {
        String recommendation = "{\"selections\":[{\"bggId\":503,\"reason\":\"它适合第一次一起玩的四个人，很快就能进入共同讨论。\"}],\"requestedCount\":1,\"playerReply\":\"有了目录资料后，我会先选这一款。\"}";
        ScriptedModel model = new ScriptedModel(List.of(
                action("premature-finish", BoardGameRecommendationAgent.RECOMMEND_TOOL, recommendation),
                action(
                        "read-candidate",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1}"),
                action("settled-finish", BoardGameRecommendationAgent.RECOMMEND_TOOL, recommendation)));
        RecordingCatalog catalog = new RecordingCatalog(game(
                503,
                "Shared Signals",
                "共享信号",
                "Players discuss a shared display before choosing one signal together."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们四个人第一次一起玩，想先看一款能让大家自然讨论的游戏。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(value -> assertThat(value.game().ranking().bggId()).isEqualTo(503));
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains("REJECTED_UNAVAILABLE_ACTION", "SEARCH_BGG_CATALOG", "RECOMMEND_GAMES")
                .doesNotContain("REUSED_ACTION_ERROR");

        loop.stopBoundedCalls();
    }

    @Test
    void doesNotConsumeTheBrowseOrPreferenceWhenALaterBrowseArgumentIsInvalid() {
        String preference = "[{\"field\":\"durationMinutes\","
                + "\"value\":{\"minimum\":null,\"maximum\":60},"
                + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]";
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "invalid-page",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"offset\":201,\"preferenceUpdates\":"
                                + preference + "}"),
                action(
                        "repaired-page",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"purpose\":\"SELECTABLE_CARDS\",\"limit\":1,\"offset\":0,\"preferenceUpdates\":"
                                + preference + "}"),
                action(
                        "finish",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        "{\"selections\":[{\"bggId\":505,\"reason\":\"四十五分钟能在今晚的一小时内完整玩完，也给第一次同桌的人留出了聊天空间。\"}],\"requestedCount\":1,\"playerReply\":\"按你刚确认的时间，我会先选这一款。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(
                505,
                "One Hour Welcome",
                "一小时欢迎局",
                "A forty-five minute game for a new group."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚最多六十分钟，我们四个人第一次同桌；请直接挑一款能完整玩完的。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.profile().durationMinutes()).satisfies(range ->
                assertThat(range.maximum()).isEqualTo(60));
        assertThat(catalog.searches).hasValue(1);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:CATALOG_OFFSET_INVALID", "UPDATE_PREFERENCES", "SEARCH_BGG_CATALOG");
        assertThat(response.harness().actions().stream()
                        .filter("UPDATE_PREFERENCES"::equals)
                        .count())
                .isEqualTo(1);

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsUnsupportedContextualFieldsWithoutCommittingValidSiblings() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "unsupported-context",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"preferenceUpdates\":["
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                                + "{\"field\":\"interaction\",\"value\":\"COMPETITIVE\",\"evidence\":\"U1\",\"evidenceClassification\":\"CONTEXTUAL_COMPLETE_GROUP\"}]}"),
                action(
                        "finish",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"你没有说明合作或对抗偏好，我不会把它悄悄变成筛选条件。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(506, "Open Choice", "开放选择", "A flexible game."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚最多六十分钟；合作还是对抗都还没讨论，先别替我们决定。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().durationMinutes()).isNull();
        assertThat(catalog.searches).hasValue(0);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID")
                .doesNotContain("UPDATE_PREFERENCES", "RECORD_CONTEXTUAL_PREFERENCE", "SEARCH_BGG_CATALOG");

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsPreferenceAliasesThatAddressTheSameLogicalFieldTwice() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "duplicate-player-field",
                        BoardGameRecommendationAgent.BROWSE_TOOL,
                        "{\"limit\":1,\"preferenceUpdates\":["
                                + "{\"field\":\"players\",\"value\":4,\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"},"
                                + "{\"field\":\"playerCount\",\"value\":{\"minimum\":4,\"maximum\":4},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                action(
                        "finish",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"人数只需要记录一次；我不会把两个同义字段当成两条不同条件。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(507, "Four Once", "四人一次", "A four-player game."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我们总共四个人，请按四人局来考虑。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().playerCount()).isNull();
        assertThat(catalog.searches).hasValue(0);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:PREFERENCE_FIELD_INVALID")
                .doesNotContain("UPDATE_PREFERENCES", "SEARCH_BGG_CATALOG");

        loop.stopBoundedCalls();
    }

    @Test
    void doesNotCommitTerminalPreferencesWhenReferencedGameOwnershipIsInvalid() {
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "invalid-reference",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"先把时长记下来。\",\"referencedBggIds\":[999],\"preferenceUpdates\":["
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                action(
                        "finish",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"我没有已验证的游戏可引用，所以先只确认你的问题，不会伪造游戏依据。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(game(508, "Unseen Game", "未见之局", "Not read in this turn."));
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "今晚最多六十分钟；先告诉我你是否已经有可引用的游戏资料。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
        assertThat(response.profile().durationMinutes()).isNull();
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:REPLY_ID_NOT_VERIFIED", "REPLY_TO_USER")
                .doesNotContain("UPDATE_PREFERENCES");

        loop.stopBoundedCalls();
    }

    @Test
    void rejectsAnExcludedDirectTargetWithoutPublishingOrPoisoningATypedRecovery() {
        Game excluded = game(520, "Ember Court", "余烬宫廷", "A previously rejected court game.");
        Game selected = game(521, "Northbound", "一路向北", "A travel game the player selected instead.");
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "excluded-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Ember Court\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"先回到《余烬宫廷》。\","
                                + "\"reason\":\"这款已经有完整目录资料。\"}"),
                action(
                        "replacement-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Northbound\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"好，改成你明确选的《一路向北》。\","
                                + "\"reason\":\"它没有被排除，可以继续进入规则书和讲解。\"}")));
        RecordingCatalog catalog = new RecordingCatalog(excluded, selected);
        RecommendationReActLoop loop = loop(model, catalog);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "不要再给我 Ember Court；这次请直接找 Northbound，然后继续规则书和讲解。",
                        List.of(520),
                        List.of(),
                        null,
                        List.of(),
                        List.of()),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(521));
        assertThat(response.candidatesEvaluated()).isEqualTo(1);
        assertThat(response.harness().actions())
                .contains("REJECTED_ACTION:FINAL_ID_EXCLUDED", "RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES")
                .doesNotContain("REUSE_VERIFIED_BGG_REFERENCE");
        assertThat(response.harness().actions().stream()
                        .filter("RESOLVE_BGG_REFERENCE"::equals)
                        .count())
                .isEqualTo(1);
        assertThat(catalog.localReferenceResolutions).hasValue(2);
        assertThat(catalog.remoteReferenceResolutions).hasValue(2);

        loop.stopBoundedCalls();
    }

    @Test
    void resolverFailuresDoNotConsumeTheStructuredReferenceAttemptBudget() {
        Game selected = game(522, "Northbound", "一路向北", "A travel game with a stable catalog identity.");
        ScriptedModel model = new ScriptedModel(List.of(
                action(
                        "failed-reference-one",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Fog Atlas\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"我先核对 Fog Atlas。\",\"reason\":\"需要目录确认。\"}"),
                action(
                        "failed-reference-two",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Copper Vale\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"我再核对 Copper Vale。\",\"reason\":\"仍需目录确认。\"}"),
                action(
                        "recovered-reference",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        "{\"title\":\"Northbound\",\"purpose\":\"TARGET_GAME\",\"evidence\":\"U1\","
                                + "\"playerReply\":\"目录恢复了，就是《一路向北》。\","
                                + "\"reason\":\"这次返回了完整的结构化游戏身份，可以继续规则书流程。\"}")));
        BoardGameRecommendationTools tools = mock(BoardGameRecommendationTools.class);
        AtomicInteger unsettledCalls = new AtomicInteger();
        when(tools.resolveLocalReferenceTitle(anyString())).thenAnswer(ignored -> {
            if (unsettledCalls.incrementAndGet() <= 2) {
                throw new IllegalStateException("resolver ended without a structured outcome");
            }
            return new ReferenceObservation(ToolStatus.PARTIAL, List.of(), "REFERENCE_NOT_FOUND");
        });
        when(tools.resolveReferenceTitle("Northbound"))
                .thenReturn(new ReferenceObservation(ToolStatus.SUCCESS, List.of(selected), ""));
        RecommendationReActLoop loop = loop(model, tools);

        var response = loop.converse(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "我提到过 Fog Atlas 和 Copper Vale，但最后明确选 Northbound；请核对后继续。"),
                "zh-CN",
                "player",
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(522));
        assertThat(response.harness().actions().stream()
                        .filter("REJECTED_ACTION:ACTION_UNAVAILABLE"::equals)
                        .count())
                .isEqualTo(2);
        assertThat(response.harness().actions())
                .contains("RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES")
                .doesNotContain("REJECTED_UNAVAILABLE_ACTION");
        verify(tools, times(3)).resolveLocalReferenceTitle(anyString());
        verify(tools, times(1)).resolveReferenceTitle("Northbound");

        loop.stopBoundedCalls();
    }

    @Test
    void checkpointsATerminalReadBeforePublishingAndReusesItsVerifiedIdentityOnRetry() {
        String userMessage = "我们最后选了静港（Quiet Harbor），请把它作为接下来讲解的游戏。";
        String targetAction = "{\"title\":\"Quiet Harbor\",\"purpose\":\"TARGET_GAME\","
                + "\"evidence\":\"U1\",\"playerReply\":\"好，就从《静港》开始。\","
                + "\"reason\":\"它就是你明确选定、接下来要进入讲解的游戏。\"}";
        Game selected = game(509, "Quiet Harbor", "静港", "A calm game about building a harbor together.");
        RecordingCatalog catalog = new RecordingCatalog(selected);
        AtomicReference<TurnCheckpoint> checkpoint = new AtomicReference<>();
        List<String> publicationOrder = new ArrayList<>();
        RecommendationReActLoop firstLoop = loop(
                new ScriptedModel(List.of(action(
                        "first-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        targetAction))),
                catalog);
        ConversationRequest firstRequest = validatedRequest(userMessage, List.of());

        assertThatThrownBy(() -> firstLoop.converseValidated(
                        firstRequest,
                        "zh-CN",
                        "player",
                        ignored -> {},
                        ignored -> {
                            publicationOrder.add("answer");
                            throw new IllegalStateException("client disconnected");
                        },
                        value -> {
                            publicationOrder.add("checkpoint");
                            checkpoint.set(value);
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("client disconnected");

        assertThat(publicationOrder).containsExactly("checkpoint", "answer");
        assertThat(checkpoint.get()).isNotNull();
        assertThat(checkpoint.get().verifiedGames()).singleElement()
                .satisfies(game -> assertThat(game.ranking().bggId()).isEqualTo(509));
        assertThat(catalog.localReferenceResolutions).hasValue(1);
        assertThat(catalog.remoteReferenceResolutions).hasValue(1);
        firstLoop.stopBoundedCalls();

        RecommendationReActLoop retryLoop = loop(
                new ScriptedModel(List.of(action(
                        "retry-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        targetAction))),
                catalog);
        ConversationRequest retryRequest = validatedRequest(userMessage, checkpoint.get().verifiedGames());
        var response = retryLoop.converseValidated(
                retryRequest,
                "zh-CN",
                "player",
                ignored -> {},
                ignored -> {},
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(509));
        assertThat(response.harness().actions()).contains("REUSE_VERIFIED_BGG_REFERENCE");
        assertThat(catalog.localReferenceResolutions).hasValue(1);
        assertThat(catalog.remoteReferenceResolutions).hasValue(1);
        retryLoop.stopBoundedCalls();
    }

    @Test
    void resolvesAuthoritativelyWhenAStoredTitleIdentityIsAmbiguous() {
        String userMessage = "我选的是 Quiet Harbor，请按这款继续，不要凭重名记录猜。";
        String targetAction = "{\"title\":\"Quiet Harbor\",\"purpose\":\"TARGET_GAME\","
                + "\"evidence\":\"U1\",\"playerReply\":\"我会先核对同名游戏，再继续这款。\","
                + "\"reason\":\"目录解析确认了你这次明确指定的游戏身份。\"}";
        Game selected = game(511, "Quiet Harbor", "静港", "The authoritative catalog result.");
        Game sameTitle = game(512, "Quiet Harbor", "静港旧版", "A different stored identity with the same title.");
        RecordingCatalog catalog = new RecordingCatalog(selected);
        RecommendationReActLoop loop = loop(
                new ScriptedModel(List.of(action(
                        "ambiguous-target",
                        BoardGameRecommendationAgent.RESOLVE_TOOL,
                        targetAction))),
                catalog);

        var response = loop.converseValidated(
                validatedRequest(userMessage, List.of(selected, sameTitle)),
                "zh-CN",
                "player",
                ignored -> {},
                ignored -> {},
                ignored -> {});

        assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(response.games()).singleElement()
                .satisfies(game -> assertThat(game.game().ranking().bggId()).isEqualTo(511));
        assertThat(response.harness().actions())
                .contains("RESOLVE_BGG_REFERENCE")
                .doesNotContain("REUSE_VERIFIED_BGG_REFERENCE");
        assertThat(catalog.localReferenceResolutions).hasValue(1);
        assertThat(catalog.remoteReferenceResolutions).hasValue(1);

        loop.stopBoundedCalls();
    }

    @Test
    void canonicalActionIdentityIgnoresObjectFormattingButPreservesArrayAndInvalidPayloadIdentity() {
        RecommendationReActLoop loop = loop(
                new ScriptedModel(List.of()),
                new RecordingCatalog(game(510, "Ordered Evidence", "有序证据", "An identity fixture.")));

        String canonical = loop.actionFingerprint(new ToolCall(
                "one",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"limit\":3,\"types\":[\"PARTY\",\"STRATEGY\"]}"));
        String reordered = loop.actionFingerprint(new ToolCall(
                "two",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{  \"types\" : [\"PARTY\", \"STRATEGY\"], \"limit\" : 3 }"));
        String differentArrayOrder = loop.actionFingerprint(new ToolCall(
                "three",
                BoardGameRecommendationAgent.BROWSE_TOOL,
                "{\"limit\":3,\"types\":[\"STRATEGY\",\"PARTY\"]}"));
        String invalid = loop.actionFingerprint(new ToolCall(
                "four", BoardGameRecommendationAgent.BROWSE_TOOL, "{\"limit\":"));
        String sameInvalid = loop.actionFingerprint(new ToolCall(
                "five", BoardGameRecommendationAgent.BROWSE_TOOL, "{\"limit\":"));
        String differentlyFormattedInvalid = loop.actionFingerprint(new ToolCall(
                "six", BoardGameRecommendationAgent.BROWSE_TOOL, "{ \"limit\":"));

        assertThat(reordered).isEqualTo(canonical);
        assertThat(differentArrayOrder).isNotEqualTo(canonical);
        assertThat(sameInvalid).isEqualTo(invalid);
        assertThat(differentlyFormattedInvalid).isNotEqualTo(invalid);

        loop.stopBoundedCalls();
    }

    private static ConversationRequest validatedRequest(String message, List<Game> priorVerifiedGames) {
        return new ConversationRequest(
                RecommendationProfile.empty(),
                message,
                List.of(),
                List.of(new DialogueMessage("user", message)),
                null,
                List.of(),
                List.of(),
                priorVerifiedGames);
    }

    private RecommendationReActLoop loop(ScriptedModel model, RecordingCatalog catalog) {
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }
        };
        return loop(model, new BoardGameRecommendationTools(catalog, research));
    }

    private RecommendationReActLoop loop(ScriptedModel model, BoardGameRecommendationTools tools) {
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.65"), Duration.ofSeconds(30));
        return new RecommendationReActLoop(
                model,
                tools,
                new BoardGameRecommendationSelector(properties),
                properties,
                new ObjectMapper());
    }

    private static Turn action(String id, String name, String arguments) {
        return new Turn("", List.of(new ToolCall(id, name, arguments)), CompletionStatus.COMPLETE);
    }

    private static Game game(int bggId, String name, String chineseName, String description) {
        return new Game(
                new Ranking(
                        bggId,
                        name,
                        2024,
                        bggId,
                        new BigDecimal("7.1"),
                        new BigDecimal("7.4"),
                        1_500,
                        List.of(BggGameType.PARTY)),
                new Details(
                        name,
                        chineseName,
                        "",
                        2,
                        4,
                        45,
                        new BigDecimal("1.6"),
                        List.of("Party Game"),
                        List.of("Simultaneous Action Selection"),
                        30,
                        45,
                        10,
                        10,
                        "4",
                        "3-4",
                        2,
                        100,
                        List.of(),
                        List.of("Avery Stone"),
                        List.of("Open Shelf"),
                        description,
                        ""));
    }

    private static final class ScriptedModel implements BoardGameRecommendationModel {
        private final List<Turn> turns;
        private final List<Request> requests = new ArrayList<>();
        private int next;

        private ScriptedModel(List<Turn> turns) {
            this.turns = List.copyOf(turns);
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean configured(String ownerUsername) {
            return true;
        }

        @Override
        public Turn next(Request request) {
            requests.add(request);
            if (next >= turns.size()) throw new AssertionError("scripted model exhausted");
            return turns.get(next++);
        }

        @Override
        public Turn next(Request request, String ownerUsername) {
            return next(request);
        }

        @Override
        public Turn streamNext(Request request, String ownerUsername, java.util.function.Consumer<String> listener) {
            return next(request);
        }
    }

    private static final class RecordingCatalog implements BoardGameRecommendationCatalog {
        private final List<Game> games;
        private final AtomicInteger searches = new AtomicInteger();
        private final AtomicInteger localReferenceResolutions = new AtomicInteger();
        private final AtomicInteger remoteReferenceResolutions = new AtomicInteger();

        private RecordingCatalog(Game... games) {
            this.games = List.of(games);
        }

        @Override
        public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
            return new CandidateSet(games.size(), games.stream().limit(maximum).toList());
        }

        @Override
        public CandidateSet searchGames(CatalogFilters filters) {
            searches.incrementAndGet();
            return new CandidateSet(games.size(), games);
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> bggIds) {
            return games.stream().filter(game -> bggIds.contains(game.ranking().bggId())).toList();
        }

        @Override
        public List<Game> resolveLocalReferenceTitle(String title) {
            localReferenceResolutions.incrementAndGet();
            return List.of();
        }

        @Override
        public List<Game> resolveReferenceTitle(String title) {
            remoteReferenceResolutions.incrementAndGet();
            String expected = title.strip().replaceAll("\\s+", " ");
            return games.stream()
                    .filter(game -> java.util.stream.Stream.of(
                                    game.ranking().sourceName(),
                                    game.details() == null ? null : game.details().name(),
                                    game.details() == null ? null : game.details().officialChineseName())
                            .filter(java.util.Objects::nonNull)
                            .map(value -> value.strip().replaceAll("\\s+", " "))
                            .anyMatch(value -> value.equalsIgnoreCase(expected)))
                    .toList();
        }

        @Override
        public int gameCount() {
            return games.size();
        }
    }
}
