package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardGameReferenceIntentTest {

    private final BoardGameReferenceIntent resolver = new BoardGameReferenceIntent();

    @Test
    void extractsNamedComparisonGamesAcrossChineseAndEnglishWording() {
        assertThat(resolver.resolve(List.of(), "我想找一款类似白塔庭院的桌游"))
                .hasValueSatisfying(intent -> assertThat(intent.title()).isEqualTo("白塔庭院"));
        assertThat(resolver.resolve(List.of(), "Something similar to Harbor Guild but easier"))
                .hasValueSatisfying(intent -> assertThat(intent.title()).isEqualTo("Harbor Guild"));
        assertThat(resolver.resolve(List.of(), "有没有接近《星河驿站》的游戏？"))
                .hasValueSatisfying(intent -> assertThat(intent.title()).isEqualTo("星河驿站"));
    }

    @Test
    void recoversThePriorNamedReferenceAfterAPlayerCorrection() {
        var transcript = List.of(
                new DialogueMessage("user", "我想找一款类似白塔庭院的桌游"),
                new DialogueMessage("assistant", "你可能喜欢它的拼图感。"),
                new DialogueMessage("user", "你根本不了解它"));

        assertThat(resolver.resolve(transcript, "你根本不了解它"))
                .hasValueSatisfying(intent -> {
                    assertThat(intent.title()).isEqualTo("白塔庭院");
                    assertThat(intent.correction()).isTrue();
                });
        assertThat(resolver.resolve(List.of(), "白塔庭院不是拼图游戏"))
                .hasValueSatisfying(intent -> assertThat(intent.title()).isEqualTo("白塔庭院"));
    }

    @Test
    void doesNotTreatOrdinaryLikesOrGenericCorrectionsAsGameTitles() {
        assertThat(resolver.resolve(List.of(), "我喜欢板块放置和资源管理")).isEmpty();
        assertThat(resolver.resolve(List.of(), "我想和朋友玩《星河驿站》")).isEmpty();
        assertThat(resolver.resolve(List.of(), "这个游戏不是合作游戏")).isEmpty();
        assertThat(resolver.resolve(List.of(), "I like games with auctions")).isEmpty();
    }
}
