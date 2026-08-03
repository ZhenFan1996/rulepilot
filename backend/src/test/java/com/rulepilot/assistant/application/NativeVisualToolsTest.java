package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeVisualEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeVisualToolsTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final UUID evidenceId = UUID.randomUUID();
    private final NativeVisualEvidence evidence = new StubVisualEvidence(evidenceId);

    @Test
    void exposesVisualToolsOnlyToTheVisualRoleAndRejectsAnswerExecution() {
        var registry = new NativeAgentToolRegistry(
                List.of(
                        new ReadRulePageImageNativeTool(evidence, mapper),
                        new CropRulePageImageNativeTool(evidence, mapper),
                        new ReadVisualPageFactsNativeTool(evidence, mapper)),
                mapper,
                ignored -> true);

        assertThat(registry.specifications(Role.VISUAL)).extracting(spec -> spec.name())
                .containsExactly("crop_rule_page_image", "read_rule_page_image", "read_visual_page_facts");
        assertThat(registry.specifications(Role.ANSWER)).isEmpty();
        assertThat(registry.specifications(Role.TEACHING)).isEmpty();

        var rejected = registry.execute(
                Role.ANSWER,
                "read_rule_page_image",
                "{\"evidenceId\":\"" + evidenceId + "\",\"pageNumber\":4}",
                scope());
        assertThat(rejected.observation().status()).isEqualTo(ObservationStatus.ERROR);
        assertThat(rejected.observation().code()).isEqualTo("TOOL_NOT_ALLOWED");
    }

    @Test
    void returnsBoundedPageAndCropMediaWithNoMechanicalAuthority() {
        var page = new ReadRulePageImageNativeTool(evidence, mapper).execute(
                "{\"evidenceId\":\"" + evidenceId + "\",\"pageNumber\":4}", scope());
        var crop = new CropRulePageImageNativeTool(evidence, mapper).execute(
                "{\"evidenceId\":\"" + evidenceId
                        + "\",\"pageNumber\":4,\"x\":100,\"y\":120,\"width\":300,\"height\":240}",
                scope());

        assertThat(page.code()).isEqualTo("PAGE_IMAGE_FOUND");
        assertThat(page.data()).containsEntry("mechanicalRuleAuthority", false);
        assertThat(page.media()).singleElement().satisfies(media -> {
            assertThat(media.mediaType()).isEqualTo("image/png");
            assertThat(media.content()).containsExactly(1, 2, 3);
        });
        assertThat(crop.code()).isEqualTo("PAGE_CROP_FOUND");
        assertThat(crop.data())
                .containsEntry("mechanicalRuleAuthority", false)
                .containsEntry("nextAction", "RETURN_FINAL_JSON_WITH_THIS_EXACT_RECTANGLE");
        assertThat(crop.media()).singleElement().satisfies(media ->
                assertThat(media.label()).contains("crop"));
    }

    @Test
    void normalizesJointGeometryOverflowAndReturnsNoMediaForAnUnknownEvidenceHandle() {
        var crop = new CropRulePageImageNativeTool(evidence, mapper);
        var normalized = crop.execute(
                "{\"evidenceId\":\"" + evidenceId
                        + "\",\"pageNumber\":4,\"x\":900,\"y\":0,\"width\":200,\"height\":20}",
                scope());
        assertThat(normalized.data().get("rectangle").toString()).contains("width=100", "x=900");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crop.execute(
                        "{\"evidenceId\":\"" + evidenceId
                                + "\",\"pageNumber\":4,\"x\":0,\"y\":0,\"width\":-1,\"height\":20}",
                        scope()))
                .isInstanceOf(IllegalArgumentException.class);

        UUID unknown = UUID.randomUUID();
        var missing = new ReadRulePageImageNativeTool(evidence, mapper).execute(
                "{\"evidenceId\":\"" + unknown + "\",\"pageNumber\":4}", scope());
        assertThat(missing.status()).isEqualTo(ObservationStatus.PARTIAL);
        assertThat(missing.media()).isEmpty();
    }

    @Test
    void visualFactsStayLiteralAndNonAuthoritative() {
        var result = new ReadVisualPageFactsNativeTool(evidence, mapper).execute(
                "{\"evidenceId\":\"" + evidenceId + "\",\"pageNumber\":4}", scope());

        assertThat(result.code()).isEqualTo("VISUAL_FACTS_FOUND");
        assertThat(result.data()).containsEntry("mechanicalRuleAuthority", false);
        assertThat(result.media()).isEmpty();
        assertThat(result.data().toString()).contains("visible board layout", "UNEXPLAINED");
    }

    private ToolScope scope() {
        return new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30));
    }

    private static final class StubVisualEvidence implements NativeVisualEvidence {
        private final UUID knownEvidenceId;

        private StubVisualEvidence(UUID knownEvidenceId) {
            this.knownEvidenceId = knownEvidenceId;
        }

        @Override
        public Optional<VisualPage> readPage(UUID documentVersionId, UUID evidenceId, int pageNumber) {
            return knownEvidenceId.equals(evidenceId) && pageNumber == 4
                    ? Optional.of(new VisualPage(evidenceId, pageNumber, "image/png", new byte[] {1, 2, 3}, 800, 1000))
                    : Optional.empty();
        }

        @Override
        public Optional<VisualCrop> cropPage(
                UUID documentVersionId, UUID evidenceId, int pageNumber,
                int x, int y, int width, int height) {
            return knownEvidenceId.equals(evidenceId) && pageNumber == 4
                    ? Optional.of(new VisualCrop(
                            evidenceId, pageNumber, "image/jpeg", new byte[] {4, 5, 6},
                            x, y, width, height, 240, 240))
                    : Optional.empty();
        }

        @Override
        public List<VisualPageFact> readPageFacts(UUID documentVersionId, UUID evidenceId, int pageNumber) {
            return knownEvidenceId.equals(evidenceId) && pageNumber == 4
                    ? List.of(new VisualPageFact(
                            4,
                            "printed label",
                            "visible board layout",
                            List.of(new VisualAnchor("BOARD", "layout", "literal zones", 100, 100, 300, 300)),
                            List.of(new VisualIcon(
                                    "symbol", "round mark", "UNEXPLAINED", "", 200, 200, 40, 40))))
                    : List.of();
        }
    }
}
