package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceLanguageRetrievalPolicyTest {

    @Test
    void rejectsTranslatedQueriesForAnEnglishRulebook() {
        OutlineRequest request = englishRequest();

        assertThatThrownBy(() -> SourceLanguageRetrievalPolicy.validate(
                        request,
                        outline(List.of("如何完成玩家设置？", "游戏什么时候结束？"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source-language");
    }

    @Test
    void acceptsQueriesContainingExactEnglishRulebookTerms() {
        assertThatNoException().isThrownBy(() -> SourceLanguageRetrievalPolicy.validate(
                englishRequest(),
                outline(List.of("PLAYER SETUP starting resources", "END OF GAME final scoring"))));
    }

    @Test
    void keepsValidationForTextRulebooksEvenWhenAPlannerAlsoSuppliesPageBindings() {
        assertThatThrownBy(() -> SourceLanguageRetrievalPolicy.validate(
                        englishRequest(),
                        new OutlineDraft(
                                "SETI",
                                "寻找外星生命并获得分数。",
                                List.of(new TopicDraft(
                                        "setup-and-end",
                                        "设置与结束",
                                        "完成设置并知道何时计分。",
                                        true,
                                        true,
                                        List.of("如何完成玩家设置？"),
                                        List.of("setup", "end", "scoring"),
                                        List.of(1))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source-language");
    }

    private OutlineRequest englishRequest() {
        String source = "PLAYER SETUP Each player takes starting resources. END OF GAME Final scoring begins "
                + "after five rounds. During each turn choose an action and follow its printed steps. ".repeat(8);
        return new OutlineRequest(4, 4, 25, List.of(new PageInput(1, source)));
    }

    private OutlineDraft outline(List<String> queries) {
        return new OutlineDraft(
                "SETI",
                "寻找外星生命并获得分数。",
                List.of(new TopicDraft(
                        "setup-and-end",
                        "设置与结束",
                        "完成设置并知道何时计分。",
                        true,
                        true,
                        queries,
                        List.of("setup", "end", "scoring"))));
    }
}
