package com.rulepilot.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.recommendation.PlayerFacingMessagePolicy.Issue;
import com.rulepilot.recommendation.PlayerFacingMessagePolicy.Purpose;
import org.junit.jupiter.api.Test;

class PlayerFacingMessagePolicyTest {

    @Test
    void acceptsCompletePlainTextInChineseAndEnglish() {
        assertThat(PlayerFacingMessagePolicy.issue(
                        "我把已核对的差异放在下面；资料不足的桌感会明确留空。",
                        Purpose.CONVERSATION))
                .isEmpty();
        assertThat(PlayerFacingMessagePolicy.issue(
                        "Which constraint matters most for the next comparison?",
                        Purpose.QUESTION))
                .isEmpty();
        assertThat(PlayerFacingMessagePolicy.issue("谢谢，这个修正很有用。", Purpose.CONVERSATION))
                .isEmpty();
    }

    @Test
    void rejectsTransportCompleteTextThatIsStructurallyUnfinished() {
        assertThat(PlayerFacingMessagePolicy.issue(
                        "I checked the candidates, but the deciding difference is",
                        Purpose.CONVERSATION))
                .contains(Issue.INCOMPLETE);
        assertThat(PlayerFacingMessagePolicy.issue(
                        "我已经核对了人数和时长，接下来最关键的是：",
                        Purpose.CONVERSATION))
                .contains(Issue.INCOMPLETE);
        assertThat(PlayerFacingMessagePolicy.issue(
                        "Which one should we keep",
                        Purpose.QUESTION))
                .contains(Issue.INCOMPLETE);
    }

    @Test
    void rejectsRawMarkupAndUnbalancedDelimitersBeforeTheyReachAPlainTextSurface() {
        assertThat(PlayerFacingMessagePolicy.issue(
                        "**Best fit:** choose the first candidate.",
                        Purpose.RECOMMENDATION_CONNECTIVE))
                .contains(Issue.RAW_MARKUP);
        assertThat(PlayerFacingMessagePolicy.issue(
                        "The verified note says (two to four players.",
                        Purpose.CONVERSATION))
                .contains(Issue.UNBALANCED_DELIMITER);
    }
}
