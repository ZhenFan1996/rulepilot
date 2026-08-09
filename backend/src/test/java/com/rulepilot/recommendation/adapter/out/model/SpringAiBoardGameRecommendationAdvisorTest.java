package com.rulepilot.recommendation.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.CompositionRequest;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueMessage;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueAct;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.PlanningRequest;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.ProfileView;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.UserModel;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SpringAiBoardGameRecommendationAdvisorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-08T12:34:00Z"), ZoneOffset.UTC);

    @Test
    void explicitlyMarksTheProductionConstructorForSpringInjection() {
        assertThat(java.util.Arrays.stream(SpringAiBoardGameRecommendationAdvisor.class.getConstructors())
                        .filter(constructor -> constructor.getParameterCount() == 7)
                        .findFirst())
                .hasValueSatisfying(constructor -> assertThat(
                                constructor.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                        .isTrue());
    }

    @Test
    void plansAConversationWithTentativePreferencesInsteadOfOnlyClassifyingSlots() {
        Fixture fixture = fixture("""
                {"act":"RECOMMEND","players":4,"maxMinutes":null,"maxWeight":null,
                 "type":"THEMATIC","interaction":"COOPERATIVE",
                 "profileSummary":"四人聚会，可能更重视共同讨论和故事感",
                 "hypotheses":[{"text":"可能喜欢共同决策","confidence":"MEDIUM","basedOn":"想一起讨论剧情"}],
                 "assistantMessage":"我大概抓到方向了，先看看几款。","nextQuestion":null,
                 "researchRequested":false,"researchQuestion":null,
                 "referenceTitle":null,"candidateTypes":["THEMATIC","Science Fiction"],
                 "featureConstraints":[{"term":"Adventure","mode":"PREFERRED","source":"BGG_METADATA","basedOn":"讨论剧情"}],
                 "candidateDiscoveryRequested":true}
                """);

        var result = fixture.adapter.plan(new PlanningRequest(
                List.of(new DialogueMessage("user", "我们 4 个人，想一起讨论剧情")),
                profile(),
                null,
                "zh-CN"));

        assertThat(result).hasValueSatisfying(plan -> {
            assertThat(plan.explicitPatch().players()).isEqualTo(4);
            assertThat(plan.userModel().summary()).contains("共同讨论");
            assertThat(plan.userModel().hypotheses()).singleElement().satisfies(hypothesis -> {
                assertThat(hypothesis.text()).contains("共同决策");
                assertThat(hypothesis.basedOn()).isEqualTo("想一起讨论剧情");
            });
            assertThat(plan.retrievalPlan().candidateTypes()).containsExactly(BggGameType.THEMATIC);
            assertThat(plan.retrievalPlan().candidateDiscoveryRequested()).isTrue();
            assertThat(plan.retrievalPlan().features()).singleElement().satisfies(feature -> {
                assertThat(feature.term()).isEqualTo("Adventure");
                assertThat(feature.basedOn()).isEqualTo("讨论剧情");
            });
        });
        verify(fixture.models).modelFor(Role.RECOMMENDATION);
    }

    @Test
    void extractsAnUnquotedReferenceTitleAsGroundedStructuredAgentOutput() {
        Fixture fixture = fixture("""
                {"act":"RECOMMEND","players":null,"maxMinutes":null,"maxWeight":null,
                 "type":null,"interaction":null,"profileSummary":"以明确提到的游戏为参照","hypotheses":[],
                 "assistantMessage":"我先核对参考游戏。","nextQuestion":null,
                 "researchRequested":false,"researchQuestion":null,"referenceTitle":"白塔庭院",
                 "candidateTypes":[],"featureConstraints":[],"candidateDiscoveryRequested":true}
                """);

        var result = fixture.adapter.plan(new PlanningRequest(
                List.of(new DialogueMessage("user", "我想玩和白塔庭院类似机制的游戏")),
                profile(),
                null,
                "zh-CN"));

        assertThat(result).hasValueSatisfying(plan -> assertThat(plan.referenceTitle()).isEqualTo("白塔庭院"));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .filteredOn(org.springframework.ai.chat.messages.SystemMessage.class::isInstance)
                .extracting(message -> message.getText())
                .singleElement()
                .asString()
                .contains(
                        "referenceTitle",
                        "before or after comparison wording",
                        "short standalone title",
                        "do not remove a leading letter",
                        "Return null when no named game");
    }

    @Test
    void bindsAnExactStandaloneTitleToThePriorUnresolvedComparison() {
        Fixture fixture = fixture("""
                {"act":"RECOMMEND","players":null,"maxMinutes":null,"maxWeight":null,
                 "type":null,"interaction":null,"profileSummary":"以澄清后的标题为参照","hypotheses":[],
                 "assistantMessage":"明白，你说的是 Azul。","nextQuestion":null,
                 "researchRequested":false,"researchQuestion":null,"referenceTitle":"Azul",
                 "candidateTypes":[],"featureConstraints":[],"candidateDiscoveryRequested":true}
                """);
        var transcript = List.of(
                new DialogueMessage("user", "我想玩花砖物语类似机制的游戏"),
                new DialogueMessage("assistant", "请补充原文名，我会再查一次。"),
                new DialogueMessage("user", "Azul"));

        assertThat(fixture.adapter.plan(new PlanningRequest(transcript, profile(), null, "zh-CN")))
                .hasValueSatisfying(plan -> {
                    assertThat(plan.act()).isEqualTo(DialogueAct.RECOMMEND);
                    assertThat(plan.referenceTitle()).isEqualTo("Azul");
                });
    }

    @Test
    void rejectsATruncatedLatinReferenceEvenWhenItIsACharacterSubstring() {
        Fixture fixture = fixture("""
                {"act":"RECOMMEND","players":null,"maxMinutes":null,"maxWeight":null,
                 "type":null,"interaction":null,"profileSummary":"","hypotheses":[],
                 "assistantMessage":"我先核对参考游戏。","nextQuestion":null,
                 "researchRequested":false,"researchQuestion":null,"referenceTitle":"zul",
                 "candidateTypes":[],"featureConstraints":[],"candidateDiscoveryRequested":true}
                """);

        assertThat(fixture.adapter.plan(new PlanningRequest(
                List.of(new DialogueMessage("user", "Azul")), profile(), null, "zh-CN"))).isEmpty();
    }

    @Test
    void rejectsAnAgentReferenceTitleThatDoesNotAppearInPlayerMessages() {
        Fixture fixture = fixture("""
                {"act":"RECOMMEND","players":null,"maxMinutes":null,"maxWeight":null,
                 "type":null,"interaction":null,"profileSummary":"","hypotheses":[],
                 "assistantMessage":"我先核对参考游戏。","nextQuestion":null,
                 "researchRequested":false,"researchQuestion":null,"referenceTitle":"模型猜出的标题",
                 "candidateTypes":[],"featureConstraints":[],"candidateDiscoveryRequested":true}
                """);

        assertThat(fixture.adapter.plan(new PlanningRequest(
                List.of(new DialogueMessage("user", "我想找一款类似的游戏")),
                profile(),
                null,
                "zh-CN"))).isEmpty();
    }

    @Test
    void downgradesAnUnsupportedRequiredFeatureUnlessTheUserCalledItNonNegotiable() {
        Fixture soft = fixture("""
                {"act":"RECOMMEND","players":null,"maxMinutes":null,"maxWeight":null,
                 "type":null,"interaction":null,"profileSummary":"想要强互动","hypotheses":[],
                 "assistantMessage":"我按强互动来找。","nextQuestion":null,
                 "researchRequested":false,"researchQuestion":null,"referenceTitle":null,"candidateTypes":[],
                 "featureConstraints":[{"term":"strong interaction","mode":"REQUIRED","source":"EXPERIENCE","basedOn":"希望有明显互动"}],
                 "candidateDiscoveryRequested":true}
                """);
        Fixture hard = fixture("""
                {"act":"RECOMMEND","players":null,"maxMinutes":null,"maxWeight":null,
                 "type":null,"interaction":null,"profileSummary":"必须强互动","hypotheses":[],
                 "assistantMessage":"我会把强互动作为硬条件。","nextQuestion":null,
                 "researchRequested":false,"researchQuestion":null,"referenceTitle":null,"candidateTypes":[],
                 "featureConstraints":[{"term":"strong interaction","mode":"REQUIRED","source":"EXPERIENCE","basedOn":"必须有明显互动"}],
                 "candidateDiscoveryRequested":true}
                """);

        var softPlan = soft.adapter.plan(new PlanningRequest(
                List.of(new DialogueMessage("user", "希望有明显互动")), profile(), null, "zh-CN"));
        var hardPlan = hard.adapter.plan(new PlanningRequest(
                List.of(new DialogueMessage("user", "必须有明显互动")), profile(), null, "zh-CN"));

        assertThat(softPlan).hasValueSatisfying(plan ->
                assertThat(plan.retrievalPlan().features().getFirst().mode()).isEqualTo(FeatureMode.PREFERRED));
        assertThat(hardPlan).hasValueSatisfying(plan ->
                assertThat(plan.retrievalPlan().features().getFirst().mode()).isEqualTo(FeatureMode.REQUIRED));
    }

    @Test
    void removesInventedNumericHardConstraintsWhenTheLatestTurnContainsNoNumericEvidence() {
        Fixture fixture = fixture("""
                {"act":"RECOMMEND","players":4,"maxMinutes":90,"maxWeight":2.3,
                 "type":"FAMILY","interaction":null,"profileSummary":"第一次带家人玩",
                 "hypotheses":[],"assistantMessage":"先试几款。","nextQuestion":"",
                 "researchRequested":false,"researchQuestion":"",
                 "referenceTitle":null,"candidateTypes":[],"featureConstraints":[],"candidateDiscoveryRequested":false}
                """);

        var result = fixture.adapter.plan(new PlanningRequest(
                List.of(new DialogueMessage("user", "第一次带家人玩，希望气氛舒服")),
                profile(),
                null,
                "zh-CN"));

        assertThat(result).hasValueSatisfying(plan -> {
            assertThat(plan.explicitPatch().players()).isNull();
            assertThat(plan.explicitPatch().maxMinutes()).isNull();
            assertThat(plan.explicitPatch().maxWeight()).isNull();
        });
    }

    @Test
    void preservesModelSlotsWhenTheLatestTurnUsesChineseNumberWords() {
        Fixture fixture = fixture("""
                {"act":"RECOMMEND","players":5,"maxMinutes":90,"maxWeight":null,
                 "type":null,"interaction":"COMPETITIVE","profileSummary":"五人九十分钟竞争局",
                 "hypotheses":[],"assistantMessage":"明白，按五人九十分钟来找。","nextQuestion":"",
                 "researchRequested":false,"researchQuestion":"",
                 "referenceTitle":null,"candidateTypes":[],"featureConstraints":[],"candidateDiscoveryRequested":false}
                """);

        var result = fixture.adapter.plan(new PlanningRequest(
                List.of(new DialogueMessage("user", "我们五个人，最多九十分钟，明确不要合作")),
                profile(),
                null,
                "zh-CN"));

        assertThat(result).hasValueSatisfying(plan -> {
            assertThat(plan.explicitPatch().players()).isEqualTo(5);
            assertThat(plan.explicitPatch().maxMinutes()).isEqualTo(90);
            assertThat(plan.explicitPatch().interaction()).isEqualTo(InteractionPreference.COMPETITIVE);
        });
    }

    @Test
    void rejectsAComposedGameOutsideTheApplicationCandidateAllowList() {
        Fixture fixture = fixture("""
                {"assistantMessage":"推荐这一款。","nextQuestion":"",
                 "choices":[{"bggId":999,"preferenceReasons":["也许适合你"],
                 "researchedReasons":[],"tradeoffs":[]}]}
                """);
        Candidate candidate = new Candidate(
                10, "Game 10", 2025, 1, new BigDecimal("8.5"), new BigDecimal("2.5"),
                2, 4, 60, 45, 60, 10, 10, "Best with 4 players", "Recommended with 2–4 players",
                2, 100, List.of("Strategy"), List.of("Cooperative Game"), List.of(), List.of(), List.of());

        var result = fixture.adapter.compose(new CompositionRequest(
                List.of(new DialogueMessage("user", "推荐一个")),
                profile(),
                new UserModel("想合作", List.of()),
                List.of(candidate),
                Research.empty(),
                null,
                "zh-CN"));

        assertThat(result).isEmpty();
        verify(fixture.values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void discardsModelAuthoredResearchCitationsAndCachesOnlyTheValidatedSlate() {
        Fixture fixture = fixture("""
                {"assistantMessage":"这款值得继续了解。","nextQuestion":"想看规则书吗？",
                 "choices":[{"bggId":10,"preferenceReasons":["可能符合你偏好的温和互动"],
                 "researchedReasons":[{"text":"玩家体验报告认为双人局节奏紧凑。","sourceIndexes":[999],
                 "debug":"ignored"}],"tradeoffs":["卡牌文字较多"]}]}
                """);
        Candidate candidate = new Candidate(
                10, "Game 10", 2025, 1, new BigDecimal("8.5"), new BigDecimal("2.5"),
                2, 4, 60, 45, 60, 10, 10, "Best with 4 players", "Recommended with 2–4 players",
                2, 100, List.of("Strategy"), List.of("Cooperative Game"), List.of(), List.of(), List.of());
        Research research = new Research(
                List.of(new GameResearch(10, List.of(new Observation("双人节奏紧凑", List.of(1))))),
                List.of(new Source(1, "Review", "https://review.example/game-10", "review.example")));

        var result = fixture.adapter.compose(new CompositionRequest(
                List.of(new DialogueMessage("user", "介绍一下这个游戏")),
                profile(),
                new UserModel("偏好温和互动", List.of()),
                List.of(candidate),
                research,
                10,
                "zh-CN"));

        assertThat(result).hasValueSatisfying(slate -> assertThat(slate.choices()).singleElement().satisfies(choice ->
                assertThat(choice.researchedReasons()).isEmpty()));
        verify(fixture.values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void allowsAFocusedExplanationToAnswerDirectlyWithoutForcingARecommendationChoice() {
        Fixture fixture = fixture("""
                {"assistantMessage":"它把工人放置和卡牌构筑连在一起：打出的卡决定能去哪里，版图行动又会买到更强的牌。",
                 "nextQuestion":"你想继续看一轮具体怎么走吗？","choices":[]}
                """);
        Candidate candidate = new Candidate(
                10, "Game 10", 2025, 1, new BigDecimal("8.5"), new BigDecimal("2.5"),
                2, 4, 60, 45, 60, 10, 10, "Best with 4 players", "Recommended with 2–4 players",
                2, 100, List.of("Strategy"), List.of("Deck Building", "Worker Placement"),
                List.of(), List.of(), List.of(), "Players deploy agents and reveal cards at the end of a round.");

        var result = fixture.adapter.compose(new CompositionRequest(
                List.of(new DialogueMessage("user", "它是什么机制，具体怎么玩？")),
                profile(),
                new UserModel("正在了解当前游戏", List.of()),
                List.of(candidate),
                Research.empty(),
                10,
                "zh-CN",
                DialogueAct.EXPLAIN));

        assertThat(result).hasValueSatisfying(slate -> {
            assertThat(slate.assistantMessage()).contains("工人放置", "卡牌构筑");
            assertThat(slate.choices()).isEmpty();
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .filteredOn(org.springframework.ai.chat.messages.SystemMessage.class::isInstance)
                .extracting(message -> message.getText())
                .singleElement()
                .asString()
                .contains(
                        "answer every explicit subquestion separately",
                        "Do not reuse sentences",
                        "Never ask for player count");
    }

    private Fixture fixture(String response) {
        RuntimeModelConfiguration models = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(models.usesFake(Role.RECOMMENDATION)).thenReturn(false);
        when(models.modelFor(Role.RECOMMENDATION)).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage(response)))));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenReturn(1L);
        var adapter = new SpringAiBoardGameRecommendationAdvisor(
                models,
                new ObjectMapper(),
                redis,
                true,
                Duration.ofDays(1),
                60,
                2,
                CLOCK);
        return new Fixture(adapter, models, values, chatModel);
    }

    private ProfileView profile() {
        return new ProfileView(null, null, null, BggGameType.ALL, InteractionPreference.ANY);
    }

    private record Fixture(
            SpringAiBoardGameRecommendationAdvisor adapter,
            RuntimeModelConfiguration models,
            ValueOperations<String, String> values,
            ChatModel chatModel) {}
}
