package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.testing.PaidCanaryTrace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

/**
 * Small opt-in paid experiment for the proposed visual boundary: local tools own geometry and the model selects only
 * opaque candidate identifiers. The rulebook crops remain ignored local fixtures and are never added to Git.
 */
@Tag("real-visual-candidate-evaluation")
class VisualCandidateSelectionPaidCanaryTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void acceptsTheProductionSixFieldCandidateProtocolAndAbstainsWithoutEmittingGeometry() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_VISUAL_CANDIDATE_EVAL")));
        Path fixtures = Path.of(requiredEnvironment("RULEPILOT_VISUAL_CANDIDATE_FIXTURE_DIR"));
        List<CandidateFixture> candidates = List.of(
                candidate(fixtures, "K7M2", 3, "cascadia/crop-0002-picture-p003.png"),
                candidate(fixtures, "P4Q9", 3, "cascadia/crop-0006-picture-p003.png"),
                candidate(fixtures, "R2V8", 4, "cat-lady/crop-0010-picture-p003.png"),
                candidate(fixtures, "T6N1", 4, "cat-lady/crop-0011-picture-p003.png"));
        candidates.forEach(candidate -> assumeTrue(Files.isRegularFile(candidate.path()),
                "ignored local layout candidate is required: " + candidate.path()));

        long started = System.nanoTime();
        String content;
        String traceId;
        VisualLocatorResponsePolicy.ModelGuide guide;
        try (PaidCanaryTrace trace = PaidCanaryTrace.start("visual_candidate_selection")) {
            traceId = trace.traceId();
            try {
                content = trace.observe("candidate_choice", () -> select(trace, candidates));
                writeRawArtifact(
                        content,
                        traceId,
                        Duration.ofNanos(System.nanoTime() - started).toMillis());
                guide = trace.observe("candidate_contract_validation", () -> validate(content));
            } catch (RuntimeException | Error thrown) {
                trace.recordFailure(thrown);
                throw thrown;
            }
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        writeArtifact(content, traceId, latencyMs, guide);
    }

    private VisualLocatorResponsePolicy.ModelGuide validate(String content) {
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("visual candidate response was not JSON", failure);
        }
        assertThat(fieldNames(root)).containsExactlyInAnyOrder("batchAction", "reviews");
        assertThat(root.path("batchAction").asText()).isEqualTo("STOP");
        assertThat(root.path("reviews").isArray()).isTrue();
        assertThat(root.path("reviews")).hasSize(3);
        root.path("reviews").forEach(review -> assertThat(fieldNames(review))
                .containsExactlyInAnyOrder(
                        "stepPosition",
                        "action",
                        "candidateId",
                        "label",
                        "visibleDescription",
                        "supportedClaimRefs"));
        var guide = VisualLocatorResponsePolicy.parseModelGuide(content).orElseThrow();

        assertThat(guide.reviews())
                .extracting(
                        VisualLocatorResponsePolicy.ModelReview::stepPosition,
                        VisualLocatorResponsePolicy.ModelReview::action,
                        VisualLocatorResponsePolicy.ModelReview::candidateId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                1, VisualLocatorResponsePolicy.ModelAction.ACCEPT_CANDIDATE, "K7M2"),
                        org.assertj.core.groups.Tuple.tuple(
                                2, VisualLocatorResponsePolicy.ModelAction.ACCEPT_CANDIDATE, "T6N1"),
                        org.assertj.core.groups.Tuple.tuple(
                                3, VisualLocatorResponsePolicy.ModelAction.NO_VISUAL, null));
        assertThat(guide.reviews().stream()
                        .filter(review -> review.action()
                                == VisualLocatorResponsePolicy.ModelAction.ACCEPT_CANDIDATE))
                .allSatisfy(review -> {
                    assertThat(review.label()).isNotBlank();
                    assertThat(review.visibleDescription()).isNotBlank();
                    assertThat(review.supportedClaimRefs())
                            .containsExactly("C" + review.stepPosition());
                });
        assertThat(content)
                .doesNotContain(
                        "\"x\"",
                        "\"y\"",
                        "width",
                        "height",
                        "coordinate",
                        "pageNumber",
                        "sourceKind");

        return guide;
    }

    private String select(PaidCanaryTrace trace, List<CandidateFixture> candidates) {
        var model = new ChatModelFactory(trace.observations(), Duration.ofSeconds(60))
                .create(
                        "qwen",
                        requiredEnvironment("QWEN_API_KEY"),
                        requiredEnvironment("QWEN_BASE_URL"),
                        requiredEnvironment("QWEN_MODEL"));
        String modelName = requiredEnvironment("QWEN_MODEL");
        var options = SpringAiVisualRegionLocator.qwenJsonOptions(modelName);
        return ChatClient.create(model)
                .prompt()
                .options(options)
                .system(SpringAiVisualRegionLocator.QWEN_SYSTEM)
                .user(user -> {
                    user.text("""
                                    Section: Visual candidate boundary canary
                                    Claims: {claims}
                                    Candidate manifest (same order as image attachments): {manifest}
                                    batchNumber: 1
                                    hasMoreCandidates: false

                                    Return the exact batchAction plus reviews JSON object only.
                                    """)
                            .param("claims", VisualLocatorResponsePolicy.promptJson(List.of(
                                    Map.of(
                                            "ref", "C1",
                                            "stepPosition", 1,
                                            "text", "Choose the crop that visibly distinguishes the hexagonal habitat tile or token family from wildlife scoring cards.",
                                            "sourcePages", List.of(3)),
                                    Map.of(
                                            "ref", "C2",
                                            "stepPosition", 2,
                                            "text", "Choose the crop showing examples from the stray cat deck, not examples from the main deck.",
                                            "sourcePages", List.of(4)),
                                    Map.of(
                                            "ref", "C3",
                                            "stepPosition", 3,
                                            "text", "Prove that the player's final score is exactly 87 points.",
                                            "sourcePages", List.of(3)))))
                            .param("manifest", manifest(candidates));
                    candidates.forEach(candidate -> user.media(
                            MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(read(candidate.path()))));
                })
                .call()
                .content();
    }

    private String manifest(List<CandidateFixture> candidates) {
        try {
            return mapper.writeValueAsString(candidates.stream()
                    .map(candidate -> Map.of(
                            "candidateId", candidate.id(),
                            "attachmentIndex", candidate.attachmentIndex(),
                            "pageNumber", candidate.pageNumber()))
                    .toList());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not serialize visual candidate manifest", failure);
        }
    }

    private CandidateFixture candidate(Path root, String id, int pageNumber, String relativePath) {
        return new CandidateFixture(id, pageNumber, root.resolve(relativePath), attachmentCounter++);
    }

    private int attachmentCounter = 1;

    private byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not read visual candidate fixture", failure);
        }
    }

    private List<String> fieldNames(JsonNode node) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    private void writeArtifact(
            String rawOutput,
            String traceId,
            long latencyMs,
            VisualLocatorResponsePolicy.ModelGuide guide) throws Exception {
        Map<String, Object> payload = baseArtifact(rawOutput, traceId, latencyMs);
        payload.put("validation", "passed");
        payload.put("reviews", guide.reviews());
        writeArtifact(payload);
    }

    private void writeRawArtifact(String rawOutput, String traceId, long latencyMs) throws Exception {
        Map<String, Object> payload = baseArtifact(rawOutput, traceId, latencyMs);
        payload.put("validation", "not_yet_validated");
        writeArtifact(payload);
    }

    private Map<String, Object> baseArtifact(String rawOutput, String traceId, long latencyMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("generatedAt", Instant.now().toString());
        payload.put("traceId", traceId);
        payload.put("provider", "qwen");
        payload.put("model", requiredEnvironment("QWEN_MODEL"));
        payload.put("promptContract", "production_candidate_batch_action_six_field_review");
        payload.put("modelCalls", 1);
        payload.put("toolCalls", 0);
        payload.put("latencyMs", latencyMs);
        payload.put("coordinateFieldsAccepted", false);
        payload.put("candidateIds", List.of("K7M2", "P4Q9", "R2V8", "T6N1"));
        payload.put("rawOutput", rawOutput);
        return payload;
    }

    private void writeArtifact(Map<String, Object> payload) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path artifact = root.resolve(
                ".local/agent-evaluation/visual-candidate-selection-paid-canary-20260826.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(
                artifact,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + "\n",
                StandardCharsets.UTF_8);
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized paid canary");
        return value.strip();
    }

    private record CandidateFixture(String id, int pageNumber, Path path, int attachmentIndex) {}
}
