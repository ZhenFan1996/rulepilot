package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("captured-teaching-richness-reassembly")
class TeachingRichLessonCapturedReassemblyTest {

    private static final String OWNER = "teaching-richness-canary";
    private static final String REQUEST =
            "请根据当前规则书自己决定最适合第一次开局的完整教学结构；复杂规则要拆成可照做的单元。";

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void reassemblesTheExactCapturedSupportedSectionsWithoutReinferringEnglishAnchorsFromChineseProse()
            throws Exception {
        assumeTrue("true".equalsIgnoreCase(
                System.getenv("RULEPILOT_ALLOW_CAPTURED_TEACHING_REASSEMBLY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path source = root.resolve(".local/agent-evaluation/teaching-rich-lesson-canary.json");
        assumeTrue(Files.isRegularFile(source), "captured paid teaching artifact is required");

        long started = System.nanoTime();
        JsonNode artifact = mapper.readTree(source.toFile());
        JsonNode captured = artifact.path("result");
        String rawOutline = captured.path("rawOutlineProviderResponses").get(0).asText();
        OutlineDraft outline = mapper.readValue(rawOutline, OutlineDraft.class);
        UUID versionId = UUID.nameUUIDFromBytes(
                "teaching-richness:dune-pages-4-13".getBytes(StandardCharsets.UTF_8));
        TeachingPlan plan = new TeachingPlanFactory().create(versionId, REQUEST, OWNER, outline);
        List<LessonSection> sections = capturedSections(plan, captured.path("publishedLesson").path("sections"));

        LessonStatus status = new TeachingLessonAssemblyPolicy().status(plan, sections);
        int stepCount = sections.stream().mapToInt(section -> section.steps().size()).sum();
        int visibleCharacters = visibleCharacters(sections);
        LessonSection ending = section(sections, "round-end");
        LessonSection clarifications = section(sections, "clarifications");

        assertThat(status).isEqualTo(LessonStatus.COMPLETE);
        assertThat(sections).hasSize(10).allSatisfy(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED));
        assertThat(stepCount).isEqualTo(captured.path("stepCount").asInt()).isEqualTo(81);
        assertThat(visibleCharacters).isEqualTo(captured.path("visibleCharacterCount").asInt()).isEqualTo(9029);
        assertThat(ending.steps()).hasSize(7);
        assertThat(clarifications.steps()).hasSize(10);
        assertThat(captured.path("allPlannedUnitsCovered").asBoolean()).isTrue();
        assertThat(captured.path("allPlayerFacingFieldsPreserved").asBoolean()).isTrue();
        assertThat(captured.path("allPublishedFieldsComeFromRaw").asBoolean()).isTrue();
        assertThat(captured.path("localProseDeletionCount").asInt()).isZero();
        assertThat(captured.path("wholeGameCompletedBeforeSectionFanOut").asBoolean()).isTrue();
        assertThat(captured.path("allSectionRequestsShareWholeGameContext").asBoolean()).isTrue();

        Path output = root.resolve(".local/agent-evaluation/teaching-rich-lesson-reassembly.json");
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "schemaVersion", 1,
                                "generatedAt", Instant.now().toString(),
                                "sourcePaidArtifact", source.toString(),
                                "sourcePaidGeneratedAt", artifact.path("generatedAt").asText(),
                                "result", reassemblyResult(
                                        captured,
                                        sections,
                                        status,
                                        stepCount,
                                        visibleCharacters,
                                        ending,
                                        clarifications,
                                        Duration.ofNanos(System.nanoTime() - started).toMillis())))
                        + "\n",
                StandardCharsets.UTF_8);
    }

    private List<LessonSection> capturedSections(TeachingPlan plan, JsonNode sectionNodes) {
        Map<String, TeachingPlan.PlannedSection> plannedByTopic = new LinkedHashMap<>();
        plan.sections().forEach(section -> plannedByTopic.put(section.topicKey(), section));
        List<LessonSection> sections = new ArrayList<>();
        sectionNodes.forEach(node -> {
            TeachingPlan.PlannedSection planned = plannedByTopic.get(node.path("topicKey").asText());
            if (planned == null) throw new IllegalArgumentException("captured section has no planned owner");
            List<LessonStep> steps = new ArrayList<>();
            node.path("steps").forEach(step -> steps.add(new LessonStep(
                    step.path("position").asInt(),
                    step.path("heading").asText(),
                    TeachingMove.valueOf(step.path("kind").asText()),
                    step.path("text").asText(),
                    mapper.convertValue(
                            step.path("sourcePages"),
                            mapper.getTypeFactory().constructCollectionType(List.class, Integer.class)),
                    mapper.convertValue(
                            step.path("sourceChunkIds"),
                            mapper.getTypeFactory().constructCollectionType(List.class, UUID.class)))));
            sections.add(new LessonSection(
                    planned.position(),
                    planned.topicKey(),
                    planned.coverageTags(),
                    node.path("title").asText(),
                    planned.required(),
                    EvidenceStatus.valueOf(node.path("evidenceStatus").asText()),
                    VisualKind.REFERENCE_CARD,
                    node.path("visualCaption").asText(),
                    steps));
        });
        return List.copyOf(sections);
    }

    private Map<String, Object> reassemblyResult(
            JsonNode captured,
            List<LessonSection> sections,
            LessonStatus status,
            int stepCount,
            int visibleCharacters,
            LessonSection ending,
            LessonSection clarifications,
            long elapsedMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lessonStatus", status.name());
        result.put("sourcePaidLessonStatusBeforeRemovingDuplicatePostPublicationGate",
                captured.path("lessonStatus").asText());
        result.put("sectionCount", sections.size());
        result.put("stepCount", stepCount);
        result.put("visibleCharacterCount", visibleCharacters);
        result.put("endingStepCount", ending.steps().size());
        result.put("endingPlayerFacingText", visibleText(ending));
        result.put("clarificationsStepCount", clarifications.steps().size());
        result.put("clarificationsPlayerFacingText", visibleText(clarifications));
        result.put("allSectionsSupported", sections.stream()
                .allMatch(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED));
        result.put("allPlannedUnitsCovered", captured.path("allPlannedUnitsCovered").asBoolean());
        result.put("allPlayerFacingFieldsPreserved", captured.path("allPlayerFacingFieldsPreserved").asBoolean());
        result.put("allPublishedFieldsComeFromRaw", captured.path("allPublishedFieldsComeFromRaw").asBoolean());
        result.put("localProseDeletionCount", captured.path("localProseDeletionCount").asInt());
        result.put("wholeGameCompletedBeforeSectionFanOut",
                captured.path("wholeGameCompletedBeforeSectionFanOut").asBoolean());
        result.put("allSectionRequestsShareWholeGameContext",
                captured.path("allSectionRequestsShareWholeGameContext").asBoolean());
        result.put("planContextSurvivedPersistenceRoundTrip",
                captured.path("planContextSurvivedPersistenceRoundTrip").asBoolean());
        result.put("paidSectionModelCalls", captured.path("sectionModelCalls").asInt());
        result.put("paidToolCalls", captured.path("toolCalls").asInt());
        result.put("paidCriticCalls", captured.path("criticCalls").asInt());
        result.put("paidLessonLatencyMs", captured.path("lessonLatencyMs").asLong());
        result.put("paidTotalLatencyMs", captured.path("totalLatencyMs").asLong());
        result.put("offlineModelCalls", 0);
        result.put("offlineReassemblyLatencyMs", elapsedMillis);
        result.put("publicationBoundary", captured.path("publicationBoundary").asText());
        return Map.copyOf(result);
    }

    private LessonSection section(List<LessonSection> sections, String topicKey) {
        return sections.stream()
                .filter(section -> section.topicKey().equals(topicKey))
                .findFirst()
                .orElseThrow();
    }

    private int visibleCharacters(List<LessonSection> sections) {
        return sections.stream().mapToInt(section -> section.title().length()
                        + section.visualCaption().length()
                        + section.steps().stream()
                                .mapToInt(step -> step.heading().length() + step.text().length())
                                .sum())
                .sum();
    }

    private List<Map<String, Object>> visibleText(LessonSection section) {
        return section.steps().stream()
                .map(step -> Map.<String, Object>of(
                        "position", step.position(),
                        "heading", step.heading(),
                        "kind", step.kind().name(),
                        "text", step.text(),
                        "sourcePages", step.sourcePages(),
                        "sourceChunkIds", step.sourceChunkIds()))
                .toList();
    }
}
