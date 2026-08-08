package com.rulepilot.catalog.adapter.out.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.Candidate;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.CompositionRequest;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.DialogueMessage;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.PlanningRequest;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.ProfileView;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.UserModel;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.catalog.application.BggRankedCatalog.GameType;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
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
                 "researchRequested":false,"researchQuestion":null}
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
        });
        verify(fixture.models).modelFor(Role.RECOMMENDATION);
    }

    @Test
    void removesInventedNumericHardConstraintsWhenTheLatestTurnContainsNoNumericEvidence() {
        Fixture fixture = fixture("""
                {"act":"RECOMMEND","players":4,"maxMinutes":90,"maxWeight":2.3,
                 "type":"FAMILY","interaction":null,"profileSummary":"第一次带家人玩",
                 "hypotheses":[],"assistantMessage":"先试几款。","nextQuestion":"",
                 "researchRequested":false,"researchQuestion":""}
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
        return new Fixture(adapter, models, values);
    }

    private ProfileView profile() {
        return new ProfileView(null, null, null, GameType.ALL, InteractionPreference.ANY);
    }

    private record Fixture(
            SpringAiBoardGameRecommendationAdvisor adapter,
            RuntimeModelConfiguration models,
            ValueOperations<String, String> values) {}
}
