package com.rulepilot.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlayerLocaleTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Can the cobalt spindle cross the amber gate? | ZH_CN | EN
            When zorq? | ZH_CN | EN
            Is 青色棱柱 allowed after the amber gate closes? | ZH_CN | EN
            青色棱柱越过琥珀门后还能行动吗？ | EN | ZH_CN
            请比较 cobalt spindle 和 amber lattice 的结算顺序。 | EN | ZH_CN
            请问 cobalt spindle 在哪？ | EN | ZH_CN
            Cobalt spindle | ZH_CN | ZH_CN
            Cobalt | ZH_CN | ZH_CN
            Cobalt | EN | EN
            """)
    void choosesReplyLanguageFromTheCurrentTurnWithTheUiLocaleOnlyAsFallback(
            String question, PlayerLocale fallback, PlayerLocale expected) {
        assertThat(PlayerLocale.forQuestion(question, fallback)).isEqualTo(expected);
    }
}
