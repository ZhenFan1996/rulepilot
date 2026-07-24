package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRegionLocator.Claim;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualExactCropReviewPolicyTest {

    @Test
    void reviewsOnlyHighRiskExactStepHeadings() {
        Claim routineSupply = claim("步骤 4（建立物品供应）：摆放物品标记。请注意，完成设置后通过得分决定胜负。");
        Claim namedCard = claim("步骤 1（打出统治卡）：打出统治卡。");
        Claim scoring = claim("步骤 2（计分）：比较分数。");

        assertThat(VisualExactCropReviewPolicy.qwenNeedsExactCropReview(List.of(routineSupply))).isFalse();
        assertThat(VisualExactCropReviewPolicy.qwenNeedsExactCropReview(List.of(namedCard))).isTrue();
        assertThat(VisualExactCropReviewPolicy.qwenNeedsExactCropReview(List.of(scoring))).isTrue();
    }

    @Test
    void keepsAClaimWithoutAnExactHeadingOutOfTheOptionalReview() {
        assertThat(VisualExactCropReviewPolicy.qwenNeedsExactCropReview(List.of(claim("unstructured claim"))))
                .isFalse();
    }

    private Claim claim(String text) {
        return new Claim(UUID.randomUUID(), text, List.of(4), 1);
    }
}
