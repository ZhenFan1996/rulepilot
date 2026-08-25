package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.ObservationRecord;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.LocateGuideResult;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgenticVisualRegionLocatorTest {

    @Test
    void acceptsOnlyAnExactObservedCropWithTypedStepAndEvidenceOwnership() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed(
                reviewedRegion(2, "ACCEPT", "PAGE_REGION", 4, 100, 120, 300, 240, "C1"),
                cropObservation(evidenceId, 4, 100, 120, 300, 240)));
        RecordingFallback fallback = new RecordingFallback();

        LocateGuideResult result = locator(agent, fallback).locateGuideWithResult(request(evidenceId, 1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.FOUND);
        assertThat(result.regions()).singleElement().satisfies(region -> {
            assertThat(region.supportedEvidenceIds()).containsExactly(evidenceId);
            assertThat(region.supportedStepPositions()).containsExactly(2);
            assertThat(region.sourceKind()).isEqualTo(VisualSourceKind.PAGE_REGION);
        });
        assertThat(agent.request.finalResponseAfterToolSuccesses())
                .containsEntry("crop_rule_page_image", 1);
        assertThat(agent.request.playerRequest()).contains("visualBudget", "stepPosition");
        assertThat(fallback.calls).isZero();
    }

    @Test
    void acceptsSeveralDistinctCropsForTheSameStepUpToTheExplicitTotalBudget() {
        UUID evidenceId = UUID.randomUUID();
        String reviews = """
                {"reviews":[
                  {"stepPosition":2,"action":"ACCEPT","source":{"pageNumber":4,"label":"图一","visibleDescription":"第一组图标","x":40,"y":40,"width":120,"height":100,"sourceKind":"PAGE_REGION","supportedClaimRefs":["C1"]}},
                  {"stepPosition":2,"action":"ACCEPT","source":{"pageNumber":4,"label":"图二","visibleDescription":"第二张牌面","x":240,"y":220,"width":180,"height":240,"sourceKind":"EMBEDDED_AUTHOR_IMAGE","supportedClaimRefs":["C1"]}},
                  {"stepPosition":2,"action":"ACCEPT","source":{"pageNumber":4,"label":"图三","visibleDescription":"第三个状态图","x":520,"y":500,"width":220,"height":180,"sourceKind":"PAGE_REGION","supportedClaimRefs":["C1"]}}
                ]}
                """;
        StubAgent agent = new StubAgent(completed(
                reviews,
                cropObservation(evidenceId, 4, 40, 40, 120, 100),
                cropObservation(evidenceId, 4, 240, 220, 180, 240),
                cropObservation(evidenceId, 4, 520, 500, 220, 180)));

        LocateGuideResult result = locator(agent, new RecordingFallback())
                .locateGuideWithResult(request(evidenceId, 3));

        assertThat(result.regions()).hasSize(3)
                .extracting(LocatedRegion::label)
                .containsExactly("图一", "图二", "图三");
        assertThat(agent.request.finalResponseAfterToolSuccesses())
                .containsEntry("crop_rule_page_image", 3);
    }

    @Test
    void acceptsAFullPageFallbackOnlyWhenTheOwnedPageWasActuallyRead() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent observed = new StubAgent(completed(
                reviewedRegion(2, "USE_FULL_PAGE", "FULL_PAGE", 4, 0, 0, 1_000, 1_000, "C1"),
                pageObservation(evidenceId, 4)));
        StubAgent unobserved = new StubAgent(completed(
                reviewedRegion(2, "USE_FULL_PAGE", "FULL_PAGE", 4, 0, 0, 1_000, 1_000, "C1")));

        LocateGuideResult accepted = locator(observed, new RecordingFallback())
                .locateGuideWithResult(request(evidenceId, 1));
        LocateGuideResult rejected = locator(unobserved, new RecordingFallback())
                .locateGuideWithResult(request(evidenceId, 1));

        assertThat(accepted.regions()).singleElement().satisfies(region -> {
            assertThat(region.sourceKind()).isEqualTo(VisualSourceKind.FULL_PAGE);
            assertThat(region.width()).isEqualTo(1_000);
            assertThat(region.height()).isEqualTo(1_000);
        });
        assertThat(rejected.diagnostic()).isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
    }

    @Test
    void preservesATypedRejectWithoutCallingAnotherProvider() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed(
                "{\"reviews\":[{\"stepPosition\":2,\"action\":\"REJECT\",\"source\":null}]}"));
        RecordingFallback fallback = new RecordingFallback();

        LocateGuideResult result = locator(agent, fallback).locateGuideWithResult(request(evidenceId, 1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.EXPLICIT_NO_REGION);
        assertThat(result.regions()).isEmpty();
        assertThat(fallback.calls).isZero();
    }

    @Test
    void rejectsAClaimReferenceThatDoesNotOwnTheObservedCrop() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed(
                reviewedRegion(2, "ACCEPT", "PAGE_REGION", 4, 100, 120, 300, 240, "C2"),
                cropObservation(evidenceId, 4, 100, 120, 300, 240)));

        LocateGuideResult result = locator(agent, new RecordingFallback())
                .locateGuideWithResult(request(evidenceId, 1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
    }

    @Test
    void rejectsTheWholeSourceWhenOneOfSeveralClaimReferencesIsUnowned() {
        UUID evidenceId = UUID.randomUUID();
        String reviews = """
                {"reviews":[{"stepPosition":2,"action":"ACCEPT","source":{
                  "pageNumber":4,"label":"棋盘区域","visibleDescription":"图中可见带边框的棋盘区域",
                  "x":100,"y":120,"width":300,"height":240,"sourceKind":"PAGE_REGION",
                  "supportedClaimRefs":["C1","C2"]}}]}
                """;
        StubAgent agent = new StubAgent(completed(
                reviews,
                cropObservation(evidenceId, 4, 100, 120, 300, 240)));

        LocateGuideResult result = locator(agent, new RecordingFallback())
                .locateGuideWithResult(request(evidenceId, 1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
        assertThat(result.regions()).isEmpty();
    }

    @Test
    void rejectsModelGeometryThatDoesNotExactlyMatchTheAuthorizedCrop() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed(
                reviewedRegion(2, "ACCEPT", "PAGE_REGION", 4, 101, 120, 300, 240, "C1"),
                cropObservation(evidenceId, 4, 100, 120, 300, 240)));

        LocateGuideResult result = locator(agent, new RecordingFallback())
                .locateGuideWithResult(request(evidenceId, 1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
    }

    @Test
    void rejectsAPlanThatExceedsTheRequestTotalVisualBudget() {
        UUID evidenceId = UUID.randomUUID();
        String reviews = """
                {"reviews":[
                  {"stepPosition":2,"action":"ACCEPT","source":{"pageNumber":4,"label":"一","visibleDescription":"一","x":40,"y":40,"width":120,"height":100,"sourceKind":"PAGE_REGION","supportedClaimRefs":["C1"]}},
                  {"stepPosition":2,"action":"ACCEPT","source":{"pageNumber":4,"label":"二","visibleDescription":"二","x":240,"y":220,"width":180,"height":240,"sourceKind":"PAGE_REGION","supportedClaimRefs":["C1"]}}
                ]}
                """;
        StubAgent agent = new StubAgent(completed(reviews));

        LocateGuideResult result = locator(agent, new RecordingFallback())
                .locateGuideWithResult(request(evidenceId, 1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
    }

    @Test
    void usesTheEstablishedFallbackWhenAgentContextOrVisionSupportIsUnavailable() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed(
                "{\"reviews\":[{\"stepPosition\":2,\"action\":\"REJECT\",\"source\":null}]}"));
        RecordingFallback fallback = new RecordingFallback();
        var locator = locator(agent, fallback);

        locator.locateGuideWithResult(request(evidenceId, false, 1));
        agent.supported = false;
        locator.locateGuideWithResult(request(evidenceId, true, 1));

        assertThat(fallback.calls).isEqualTo(2);
        assertThat(agent.request).isNull();
    }

    private String reviewedRegion(
            int step, String action, String sourceKind, int page, int x, int y, int width, int height, String claim) {
        return "{\"reviews\":[{\"stepPosition\":" + step + ",\"action\":\"" + action
                + "\",\"source\":{\"pageNumber\":" + page
                + ",\"label\":\"棋盘区域\",\"visibleDescription\":\"图中可见带边框的棋盘区域\",\"x\":" + x
                + ",\"y\":" + y + ",\"width\":" + width + ",\"height\":" + height
                + ",\"sourceKind\":\"" + sourceKind
                + "\",\"supportedClaimRefs\":[\"" + claim + "\"]}}]}";
    }

    private AgenticVisualRegionLocator locator(StubAgent agent, RecordingFallback fallback) {
        NativeToolScopes scopes = (owner, documentVersionId, runId) -> Optional.of(
                new ToolScope(owner, documentVersionId, runId, Instant.now().plusSeconds(30)));
        return new AgenticVisualRegionLocator(
                agent, scopes, fallback, JsonMapper.builder().findAndAddModules().build());
    }

    private VisualRegionLocator.VisualLocationRequest request(UUID evidenceId, int visualBudget) {
        return request(evidenceId, true, visualBudget);
    }

    private VisualRegionLocator.VisualLocationRequest request(UUID evidenceId, boolean agentContext, int visualBudget) {
        return new VisualRegionLocator.VisualLocationRequest(
                "开局设置",
                List.of(new VisualRegionLocator.Claim(
                        evidenceId, "把对应组件放到图中标出的区域。", List.of(4), 2)),
                List.of(new Candidate(
                        4, new RulebookUnderstanding.Rectangle(0, 0, 1_000, 1_000), "page visual context")),
                List.of(new VisualRegionLocator.PageImage(4, "image/png", new byte[] {1})),
                "player",
                agentContext ? UUID.randomUUID() : null,
                agentContext ? UUID.randomUUID() : null,
                visualBudget);
    }

    private RunResult completed(String text, ObservationRecord... observations) {
        return new RunResult(
                RunStatus.COMPLETED,
                text,
                "MODEL_COMPLETED",
                3,
                observations.length,
                List.of(observations));
    }

    private ObservationRecord cropObservation(
            UUID evidenceId, int page, int x, int y, int width, int height) {
        return observation(
                "crop_rule_page_image",
                "PAGE_CROP_FOUND",
                Map.of(
                        "evidenceId", evidenceId.toString(),
                        "pageNumber", page,
                        "rectangle", Map.of("x", x, "y", y, "width", width, "height", height)));
    }

    private ObservationRecord pageObservation(UUID evidenceId, int page) {
        return observation(
                "read_rule_page_image",
                "PAGE_IMAGE_FOUND",
                Map.of("evidenceId", evidenceId.toString(), "pageNumber", page));
    }

    private ObservationRecord observation(String tool, String code, Map<String, Object> data) {
        return new ObservationRecord(
                2,
                tool,
                "hash",
                new ToolObservation(ObservationStatus.SUCCESS, code, data, 1));
    }

    private static final class StubAgent implements NativeToolAgent {
        private final RunResult result;
        private boolean supported = true;
        private RunRequest request;

        private StubAgent(RunResult result) {
            this.result = result;
        }

        @Override
        public RunResult run(RunRequest request) {
            this.request = request;
            return result;
        }

        @Override
        public boolean supports(Role role, String ownerUsername) {
            return supported;
        }
    }

    private static final class RecordingFallback implements VisualRegionLocator {
        private int calls;

        @Override
        public Optional<LocatedRegion> locate(VisualLocationRequest request) {
            calls++;
            return Optional.empty();
        }

        @Override
        public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
            calls++;
            return LocateGuideResult.unavailable(Diagnostic.MODEL_UNAVAILABLE);
        }
    }
}
