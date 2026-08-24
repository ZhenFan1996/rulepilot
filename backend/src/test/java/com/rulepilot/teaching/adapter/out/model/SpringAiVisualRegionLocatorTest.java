package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.ReviewAction;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;

class SpringAiVisualRegionLocatorTest {

    @Test
    void reportsTheOwnersConfiguredVisualCapability() {
        var models = mock(RuntimeModelConfiguration.class);
        when(models.usesFake(Role.VISUAL, "text-player")).thenReturn(true);
        when(models.usesFake(Role.VISUAL, "visual-player")).thenReturn(false);
        when(models.supportsVision(Role.VISUAL, "visual-player")).thenReturn(true);
        var locator = new SpringAiVisualRegionLocator(models);

        assertThat(locator.supportsVisualEvidence("text-player")).isFalse();
        assertThat(locator.supportsVisualEvidence("visual-player")).isTrue();
    }

    @Test
    void parsesSeveralOwnedVisualsForOneStepWithoutATwoImageProtocolCap() {
        var guide = VisualLocatorResponsePolicy.parseModelGuide("""
                {"reviews":[
                  {"stepPosition":2,"action":"ACCEPT","source":{"pageNumber":4,"label":"行动图标","visibleDescription":"骰子图标旁有一枚向右箭头","x":100,"y":100,"width":80,"height":80,"sourceKind":"PAGE_REGION","supportedClaimRefs":["C1"]}},
                  {"stepPosition":2,"action":"ACCEPT","source":{"pageNumber":4,"label":"行动状态","visibleDescription":"棋子位于弧形轨道的第三格","x":300,"y":420,"width":240,"height":180,"sourceKind":"EMBEDDED_AUTHOR_IMAGE","supportedClaimRefs":["C1"]}},
                  {"stepPosition":2,"action":"ACCEPT","source":{"pageNumber":5,"label":"图例","visibleDescription":"三个图标以箭头相连","x":80,"y":200,"width":360,"height":160,"sourceKind":"PAGE_REGION","supportedClaimRefs":["C2"]}}
                ]}
                """).orElseThrow();

        assertThat(guide.reviews()).hasSize(3).allSatisfy(review -> {
            assertThat(review.action()).isEqualTo(ReviewAction.ACCEPT);
            assertThat(review.stepPosition()).isEqualTo(2);
        });
        assertThat(guide.regions())
                .extracting(VisualLocatorResponsePolicy.ModelRegion::sourceKind)
                .containsExactly(
                        VisualSourceKind.PAGE_REGION,
                        VisualSourceKind.EMBEDDED_AUTHOR_IMAGE,
                        VisualSourceKind.PAGE_REGION);
    }

    @Test
    void representsAZeroVisualPlanWithATypedReject() {
        var guide = VisualLocatorResponsePolicy.parseModelGuide(
                        "{\"reviews\":[{\"stepPosition\":3,\"action\":\"REJECT\",\"source\":null}]}")
                .orElseThrow();

        assertThat(guide.hasOnlyRejections()).isTrue();
        assertThat(guide.regions()).isEmpty();
        assertThat(VisualLocatorResponsePolicy.isExplicitNoRegion(
                "{\"reviews\":[{\"stepPosition\":3,\"action\":\"REJECT\",\"source\":null}]}"))
                .isTrue();
    }

    @Test
    void acceptsATypedFullPageFallbackWithoutAnAreaHeuristic() {
        var review = VisualLocatorResponsePolicy.parseModelGuide("""
                {"reviews":[{"stepPosition":1,"action":"USE_FULL_PAGE","source":{
                  "pageNumber":7,"label":"完整流程图","visibleDescription":"整页是一张连续流程图，节点由箭头连接",
                  "x":0,"y":0,"width":1000,"height":1000,"sourceKind":"FULL_PAGE","supportedClaimRefs":["C1"]
                }}]}
                """).orElseThrow().reviews().getFirst();

        assertThat(review.action()).isEqualTo(ReviewAction.USE_FULL_PAGE);
        assertThat(review.source().sourceKind()).isEqualTo(VisualSourceKind.FULL_PAGE);
    }

    @Test
    void rejectsWholePageGeometryUnlessTheReviewExplicitlyChoosesUseFullPage() {
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("""
                {"reviews":[{"stepPosition":1,"action":"ACCEPT","source":{
                  "pageNumber":7,"label":"整页","visibleDescription":"整页流程图","x":0,"y":0,"width":1000,
                  "height":1000,"sourceKind":"FULL_PAGE","supportedClaimRefs":["C1"]
                }}]}
                """)).isEmpty();
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("""
                {"reviews":[{"stepPosition":1,"action":"USE_FULL_PAGE","source":{
                  "pageNumber":7,"label":"局部","visibleDescription":"局部流程图","x":10,"y":10,"width":900,
                  "height":900,"sourceKind":"FULL_PAGE","supportedClaimRefs":["C1"]
                }}]}
                """)).isEmpty();
    }

    @Test
    void admitsRecropAsAnIntermediateTypedDecision() {
        var review = VisualLocatorResponsePolicy.parseModelGuide("""
                {"reviews":[{"stepPosition":4,"action":"RECROP","source":{
                  "pageNumber":8,"label":"牌面细节","visibleDescription":"卡牌下方可见一排资源图标","x":120,
                  "y":300,"width":500,"height":420,"sourceKind":"EMBEDDED_AUTHOR_IMAGE","supportedClaimRefs":["C3"]
                }}]}
                """).orElseThrow().reviews().getFirst();

        assertThat(review.action()).isEqualTo(ReviewAction.RECROP);
        assertThat(VisualLocatorResponsePolicy.retryInstruction(VisualLocatorResponsePolicy.Rejection.RECROP))
                .contains("RECROP", "final");
    }

    @Test
    void rejectsUnknownFieldsMissingClaimOwnershipAndDuplicateReferences() {
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("""
                {"reviews":[{"stepPosition":1,"action":"ACCEPT","source":{
                  "pageNumber":2,"label":"棋盘","visibleDescription":"中央棋盘","x":10,"y":10,"width":300,
                  "height":300,"sourceKind":"PAGE_REGION","supportedClaimRefs":[],"extra":true
                }}]}
                """)).isEmpty();
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("""
                {"reviews":[{"stepPosition":1,"action":"ACCEPT","source":{
                  "pageNumber":2,"label":"棋盘","visibleDescription":"中央棋盘","x":10,"y":10,"width":300,
                  "height":300,"sourceKind":"PAGE_REGION","supportedClaimRefs":["C1","C1"]
                }}]}
                """)).isEmpty();
    }

    @Test
    void rejectsTheWholeSourceWhenAnyClaimReferenceLacksStepPageOrEvidenceOwnership() {
        UUID evidenceId = UUID.randomUUID();
        var review = VisualLocatorResponsePolicy.parseModelGuide("""
                {"reviews":[{"stepPosition":2,"action":"ACCEPT","source":{
                  "pageNumber":4,"label":"棋盘","visibleDescription":"中央棋盘有一圈行动格",
                  "x":10,"y":10,"width":300,"height":300,"sourceKind":"PAGE_REGION",
                  "supportedClaimRefs":["C1","C2"]}}]}
                """).orElseThrow().reviews().getFirst();
        var request = new VisualRegionLocator.VisualLocationRequest(
                "行动",
                List.of(new VisualRegionLocator.Claim(evidenceId, "沿行动格移动。", List.of(4), 2)),
                List.of(new Candidate(4, new Rectangle(0, 0, 1_000, 1_000), "cited page")),
                List.of(new VisualRegionLocator.PageImage(4, "image/png", new byte[] {1})),
                "owner",
                null,
                null,
                1);

        assertThat(new SpringAiVisualRegionLocator(mock(RuntimeModelConfiguration.class)).ownedClaims(review, request))
                .isEmpty();
    }

    @Test
    void rejectsProseNullEmptyAndMoreThanTheAbsoluteSafetyBound() {
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("not json")).isEmpty();
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("null")).isEmpty();
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("{\"reviews\":[]}")).isEmpty();
        String decisions = java.util.stream.IntStream.rangeClosed(1, 13)
                .mapToObj(position -> "{\"stepPosition\":" + position
                        + ",\"action\":\"REJECT\",\"source\":null}")
                .collect(java.util.stream.Collectors.joining(","));
        assertThat(VisualLocatorResponsePolicy.parseModelGuide("{\"reviews\":[" + decisions + "]}"))
                .isEmpty();
    }

    @Test
    void requestsQwenJsonModeWithoutThinkingAndAllowsAMultiVisualResponse() {
        var options = SpringAiVisualRegionLocator.qwenJsonOptions("qwen3-vl-plus").build();

        assertThat(options.getModel()).isEqualTo("qwen3-vl-plus");
        assertThat(options.getMaxTokens()).isEqualTo(1_600);
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(SpringAiVisualRegionLocator.QWEN_SYSTEM)
                .contains("zero or several", "USE_FULL_PAGE", "EMBEDDED_AUTHOR_IMAGE", "visualBudget");
    }
}
