package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SimplifiedChineseTextTest {

    @Test
    void convertsTraditionalAndMixedChineseByPhraseWithoutChangingLatinOrExistingSimplifiedText() {
        assertThat(SimplifiedChineseText.normalize("奇幻寶島：城市擴充（Deluxe）"))
                .isEqualTo("奇幻宝岛：城市扩充（Deluxe）");
        assertThat(SimplifiedChineseText.normalize("展翅翱翔 Wingspan")).isEqualTo("展翅翱翔 Wingspan");
        assertThat(SimplifiedChineseText.normalize(List.of("牌庫構築", "區域控制", "牌库构筑")))
                .containsExactly("牌库构筑", "区域控制", "牌库构筑");
    }
}
