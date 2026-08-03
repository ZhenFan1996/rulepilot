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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgenticVisualRegionLocatorTest {

    @Test
    void acceptsOnlyTheExactCropObservedThroughTheCitedEvidenceHandle() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed(
                """
                {"regions":[{"pageNumber":4,"label":"棋盘区域","visibleDescription":"图中可见一组带边框的棋盘区域。","x":100,"y":120,"width":300,"height":240,"supportedClaimRefs":["C1"]}]}
                """,
                cropObservation(evidenceId, 4, 100, 120, 300, 240)));
        RecordingFallback fallback = new RecordingFallback();
        var locator = locator(agent, fallback);

        LocateGuideResult result = locator.locateGuideWithResult(request(evidenceId, true));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.FOUND);
        assertThat(result.regions()).singleElement().satisfies(region -> {
            assertThat(region.pageNumber()).isEqualTo(4);
            assertThat(region.supportedEvidenceIds()).containsExactly(evidenceId);
            assertThat(region.supportedStepPositions()).containsExactly(2);
            assertThat(region.claimContradicted()).isFalse();
        });
        assertThat(fallback.calls).isZero();
        assertThat(agent.request.role()).isEqualTo(Role.VISUAL);
        assertThat(agent.request.playerRequest()).contains(evidenceId.toString(), "sourcePages");
    }

    @Test
    void rejectsAJsonRectangleThatWasNotReturnedByTheCropTool() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed(
                """
                {"regions":[{"pageNumber":4,"label":"棋盘区域","visibleDescription":"图中可见棋盘区域。","x":101,"y":120,"width":300,"height":240,"supportedClaimRefs":["C1"]}]}
                """,
                cropObservation(evidenceId, 4, 100, 120, 300, 240)));
        RecordingFallback fallback = new RecordingFallback();

        LocateGuideResult result = locator(agent, fallback).locateGuideWithResult(request(evidenceId, true));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.UNSUPPORTED_SCOPE);
        assertThat(fallback.calls).isZero();
    }

    @Test
    void preservesAnExplicitVisualAbstentionWithoutCallingTheFallbackProvider() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed("{\"regions\":[]}"));
        RecordingFallback fallback = new RecordingFallback();

        LocateGuideResult result = locator(agent, fallback).locateGuideWithResult(request(evidenceId, true));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.EXPLICIT_NO_REGION);
        assertThat(fallback.calls).isZero();
    }

    @Test
    void usesTheEstablishedFallbackForLegacyContextAndTextOnlyModels() {
        UUID evidenceId = UUID.randomUUID();
        StubAgent agent = new StubAgent(completed("{\"regions\":[]}"));
        RecordingFallback fallback = new RecordingFallback();
        var locator = locator(agent, fallback);

        locator.locateGuideWithResult(request(evidenceId, false));
        agent.supported = false;
        locator.locateGuideWithResult(request(evidenceId, true));

        assertThat(fallback.calls).isEqualTo(2);
        assertThat(agent.request).isNull();
    }

    private AgenticVisualRegionLocator locator(StubAgent agent, RecordingFallback fallback) {
        NativeToolScopes scopes = (owner, documentVersionId, runId) -> Optional.of(
                new ToolScope(owner, documentVersionId, runId, Instant.now().plusSeconds(30)));
        return new AgenticVisualRegionLocator(
                agent, scopes, fallback, JsonMapper.builder().findAndAddModules().build());
    }

    private VisualRegionLocator.VisualLocationRequest request(UUID evidenceId, boolean agentContext) {
        UUID documentVersionId = agentContext ? UUID.randomUUID() : null;
        UUID runId = agentContext ? UUID.randomUUID() : null;
        return new VisualRegionLocator.VisualLocationRequest(
                "开局设置",
                List.of(new VisualRegionLocator.Claim(
                        evidenceId, "把对应组件放到图中标出的区域。", List.of(4), 2)),
                List.of(new Candidate(
                        4, new RulebookUnderstanding.Rectangle(80, 100, 360, 300), "page visual context")),
                List.of(new VisualRegionLocator.PageImage(4, "image/png", new byte[] {1})),
                "player",
                documentVersionId,
                runId);
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
        return new ObservationRecord(
                2,
                "crop_rule_page_image",
                "hash",
                new ToolObservation(
                        ObservationStatus.SUCCESS,
                        "PAGE_CROP_FOUND",
                        Map.of(
                                "evidenceId", evidenceId.toString(),
                                "pageNumber", page,
                                "rectangle", Map.of(
                                        "x", x, "y", y, "width", width, "height", height),
                                "mechanicalRuleAuthority", false),
                        1));
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
