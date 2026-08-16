package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.adapter.out.model.FakeContentCriticModel;
import com.rulepilot.assistant.adapter.out.model.SpringAiContentCriticModel;
import com.rulepilot.assistant.application.ConditionalGeneratedContentCritic;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.adapter.out.model.FakeTeachingLessonModel;
import com.rulepilot.teaching.adapter.out.model.SpringAiTeachingLessonModel;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import io.micrometer.observation.ObservationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("real-teaching-agent-evaluation")
class TeachingEvidenceAgentRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void fillsTeachingCoverageGapsAcrossThreeRulebooksAndRejectsAnUnrelatedNeed() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_TEACHING_AGENT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode manifest = mapper.readTree(root.resolve(".local/agent-evaluation/manifest.json").toFile());
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        JsonNode evaluation = evaluationCases();
        List<CaseConfiguration> cases = new ArrayList<>();
        for (JsonNode caseNode : evaluation.path("cases")) {
            cases.add(caseFor(
                    root,
                    manifest,
                    inventory,
                    caseNode,
                    new ProviderConfiguration("application", "", "", "")));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (CaseConfiguration case_ : cases) results.add(runCase(case_));
        Map<String, Object> negative = runNegative(cases.getLast(), evaluation.path("crossRulebookNegative"));

        Path output = root.resolve(".local/agent-evaluation/teaching-agent-real-rulebooks.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results,
                "crossRulebookNegative", negative)) + "\n", StandardCharsets.UTF_8);

        assertThat(results).hasSize(3).allSatisfy(result -> {
            assertThat((Integer) result.get("toolCalls")).isGreaterThan(0);
            assertThat(result).containsEntry("expectedCoverageAdded", true)
                    .containsEntry("citationAccepted", true)
                    .containsEntry("modelCalls", 0)
                    .containsEntry("directAdditionalModelCalls", 0)
                    .containsEntry("directAdditionalToolCalls", 1)
                    .containsEntry("withinLatencyBudget", true);
            assertThat(result.get("toolOperations"))
                    .isEqualTo(List.of("readTeachingSourcePages|1"));
        });
        assertThat(negative).containsEntry("state", "EMPTY")
                .containsEntry("evidenceCount", 0)
                .containsEntry("crossScopeEvidence", false);
    }

    @Test
    void comparesCompleteTeachingSectionsWithAndWithoutTheBoundedToolPortfolio() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_TEACHING_VALUE_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode manifest = mapper.readTree(root.resolve(".local/agent-evaluation/manifest.json").toFile());
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        JsonNode evaluation = evaluationCases();
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/teaching-tool-value-generated-sections.json");
        String selectedCase = System.getenv("RULEPILOT_TEACHING_VALUE_CASE");

        for (JsonNode caseNode : evaluation.path("cases")) {
            if (selectedCase != null && !selectedCase.isBlank()
                    && !selectedCase.equals(caseNode.path("caseId").asText())) continue;
            ProviderConfiguration provider = provider("TEXT_LAYER".equals(caseNode.path("family").asText())
                    ? "deepseek"
                    : "qwen");
            CaseConfiguration configured = caseFor(root, manifest, inventory, caseNode, provider);
            results.add(compareTeachingSection(configured));
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "schemaVersion", 2,
                    "generatedAt", Instant.now().toString(),
                    "results", List.copyOf(results))) + "\n", StandardCharsets.UTF_8);
        }
        publishReleaseSummary(root, results);

        int expectedCaseCount = selectedCase == null || selectedCase.isBlank()
                ? evaluation.path("cases").size()
                : 1;
        assertThat(results).hasSize(expectedCaseCount);
        results.forEach(result -> {
            String caseId = String.valueOf(result.get("caseId"));
            assertThat(result.get("publishableSectionProduced"))
                    .as("%s produced a publishable section", caseId)
                    .isEqualTo(true);
            assertThat(result.get("reviewedEvidenceStatus"))
                    .as("%s completed production post-publication review", caseId)
                    .isEqualTo(EvidenceStatus.SUPPORTED.name());
            assertThat((Integer) result.get("criticCalls"))
                    .as("%s stayed within the bounded semantic review and confirmation passes", caseId)
                    .isBetween(1, 8);
            assertThat((List<?>) result.get("criticOperations"))
                    .as("%s exercised production post-publication review", caseId)
                    .anyMatch("reviewPublishedTeachingLesson"::equals);
            assertThat(result.get("sourceCitationsPresent"))
                    .as("%s retained source citations on every step", caseId)
                    .isEqualTo(true);
            assertThat(result.get("toolSectionUsesExpectedPage"))
                    .as("%s used the expected source page", caseId)
                    .isEqualTo(true);
            assertThat(result.get("boundedToolCalls"))
                    .as("%s stayed within its expected tool portfolio", caseId)
                    .isEqualTo(true);
            assertThat(result.get("withinLatencyBudget"))
                    .as("%s completed the readable cited lesson and review within three minutes", caseId)
                    .isEqualTo(true);
            assertThat(result.get("rawVisibleResponsesRecorded"))
                    .as("%s retained provider-visible output outside Git", caseId)
                    .isEqualTo(true);
            assertThat(result.get("completeOutcomesRecordedForInspection"))
                    .as("%s retained the complete comparison outcome", caseId)
                    .isEqualTo(true);
        });
    }

    @Test
    void publishesOneRealSectionThroughTheSimplifiedCriticalPath() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_TEACHING_CRITICAL_PATH_CANARY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode manifest = mapper.readTree(root.resolve(".local/agent-evaluation/manifest.json").toFile());
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        String requestedCase = System.getenv("RULEPILOT_TEACHING_VALUE_CASE");
        String caseId = requestedCase == null || requestedCase.isBlank() ? "rr-text-001" : requestedCase;
        JsonNode caseNode = java.util.stream.StreamSupport.stream(
                        evaluationCases().path("cases").spliterator(), false)
                .filter(candidate -> caseId.equals(candidate.path("caseId").asText()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown teaching canary case: " + caseId));
        CaseConfiguration configured = caseFor(root, manifest, inventory, caseNode, provider("deepseek"));

        Map<String, Object> result = runCriticalPathCanary(root, configured);
        Path output = root.resolve(".local/agent-evaluation/teaching-critical-path-canary.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "result", result)) + "\n", StandardCharsets.UTF_8);

        assertThat(result)
                .containsEntry("caseId", caseId)
                .containsEntry("provider", "deepseek")
                .containsEntry("evidenceStatus", EvidenceStatus.SUPPORTED.name())
                .containsEntry("semanticEntailmentReviewed", false)
                .containsEntry("criticCalls", 0)
                .containsEntry("toolCalls", 1)
                .containsEntry("toolLoopModelCalls", 0)
                .containsEntry("sourceCitationsPresent", true)
                .containsEntry("expectedSourcePageUsed", true)
                .containsEntry("contentPreservedAtPublication", true)
                .containsEntry("withinLatencyBudget", true);
        assertThat((Integer) result.get("sectionModelCalls")).isBetween(1, 2);
        assertThat((Integer) result.get("stepCount")).isGreaterThanOrEqualTo(4);
        assertThat(((Number) result.get("distinctTeachingMoveCount")).longValue()).isGreaterThanOrEqualTo(3);
        assertThat((Integer) result.get("visibleCharacterCount")).isGreaterThanOrEqualTo(300);
        Map<?, ?> fieldPreservation = (Map<?, ?>) result.get("fieldPreservation");
        assertThat(fieldPreservation.get("rawStructuredToNormalizerRecordEqual")).isEqualTo(true);
        assertThat(fieldPreservation.get("allStepTextExactThroughPublication")).isEqualTo(true);
        assertThat(fieldPreservation.get("onlyLifecycleStatusChangedAtPublication")).isEqualTo(true);
    }

    private Map<String, Object> runCriticalPathCanary(Path root, CaseConfiguration case_) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(("teaching-critical-path:" + case_.caseId())
                .getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfTeachingEvidence corpus = new PdfTeachingEvidence(case_.pdf(), versionId);
        TeachingPlan plan = plan(versionId, case_.caseNode(), case_.sourcePages());
        PlannedSection planned = plan.sections().getFirst();
        var initial = new TeachingSectionEvidenceRetriever.Result(
                List.of(corpus.first(case_.initialPage())),
                1,
                TeachingSectionEvidenceRetriever.State.VERIFIED);
        DirectAuditedInvocations toolAudit = new DirectAuditedInvocations();
        DirectAuditedInvocations compositionAudit = new DirectAuditedInvocations();
        List<String> rawResponses = new ArrayList<>();
        RecordingTeachingLessonModel teachingModel = new RecordingTeachingLessonModel(
                teachingModel(case_.provider(), rawResponses));

        long started = System.nanoTime();
        var refined = refiner(corpus, toolAudit, versionId, runId)
                .refine(plan, planned, runId, initial);
        TeachingSectionDraftCandidate candidate = sectionComposer(teachingModel, compositionAudit)
                .compose(plan, planned, List.of(), refined.evidence(), runId, 0, false);
        LessonSection published = new TeachingBaseSectionPublicationPolicy().publish(candidate);
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        String visibleText = visibleSectionText(published);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", case_.caseId());
        result.put("provider", case_.provider().provider());
        result.put("qualityScope", "one ending-resolution chapter; this is not a complete multi-chapter lesson evaluation");
        result.put("objective", planned.objective());
        result.put("publicationBoundary", "schema + citation scope/version + visual geometry + quantitative checks");
        result.put("evidenceStatus", published.evidenceStatus().name());
        result.put("evidenceStatusMeaning", "passed deterministic publication checks; no independent semantic Critic ran");
        result.put("semanticEntailmentReviewed", false);
        result.put("toolCalls", toolAudit.toolCalls.get());
        result.put("toolOperations", List.copyOf(toolAudit.toolOperations));
        result.put("toolLoopModelCalls", toolAudit.modelCalls.get());
        result.put("sectionModelCalls", compositionAudit.modelCalls.get());
        result.put("totalModelCalls", toolAudit.modelCalls.get() + compositionAudit.modelCalls.get());
        result.put("criticCalls", toolAudit.criticCalls.get() + compositionAudit.criticCalls.get());
        result.put("latencyMs", latencyMs);
        result.put("withinLatencyBudget", latencyMs < 120_000);
        result.put("rawProviderResponses", List.copyOf(rawResponses));
        result.put("draftAttempts", teachingModel.drafts().stream().map(this::visibleDraft).toList());
        result.put("validatedCandidate", visibleSection(candidate.section()));
        result.put("publishedSection", visibleSection(published));
        result.put("contentPreservedAtPublication", sameSectionContent(candidate.section(), published));
        result.put("fieldPreservation", fieldPreservation(
                teachingModel.drafts().getLast(), candidate.draft(), candidate.section(), published));
        result.put("apiAndUiMapping", Map.of(
                "api", "IllustratedLessonController and PublicLessonController serialize the domain lesson directly",
                "uiRenderedFields", List.of(
                        "section.position", "section.title", "section.visualCaption", "step.position",
                        "step.heading", "step.kind", "step.text", "step.sourcePages", "step.visualFocus"),
                "uiUnrenderedReaderFields", List.of(
                        "section.topicKey", "section.coverageTags", "section.required", "section.evidenceStatus",
                        "section.visualKind", "section.visualSourcePages", "section.visualSourceChunkIds",
                        "step.sourceChunkIds")));
        result.put("sourceCitationsPresent", published.steps().stream()
                .allMatch(step -> !step.sourceChunkIds().isEmpty() && !step.sourcePages().isEmpty()));
        result.put("expectedSourcePage", case_.expectedPage());
        result.put("expectedSourcePageUsed", usesExpectedPage(case_, published));
        result.put("initialEvidence", visibleEvidence(initial.evidence()));
        result.put("refinedEvidence", visibleEvidence(refined.evidence()));
        result.put("stepCount", published.steps().size());
        result.put("distinctTeachingMoveCount", published.steps().stream()
                .map(step -> step.kind().name())
                .distinct()
                .count());
        result.put("visibleCharacterCount", visibleText.length());
        result.put("historicalBefore", historicalCriticalPathBaseline(root, case_.caseId()));
        return Map.copyOf(result);
    }

    private Map<String, Object> historicalCriticalPathBaseline(Path root, String caseId) throws IOException {
        Path input = root.resolve(".local/agent-evaluation/teaching-tool-value-generated-sections.json");
        if (!Files.isRegularFile(input)) return Map.of("available", false);
        JsonNode result = java.util.stream.StreamSupport.stream(
                        mapper.readTree(input.toFile()).path("results").spliterator(), false)
                .filter(candidate -> caseId.equals(candidate.path("caseId").asText()))
                .findFirst()
                .orElse(null);
        if (result == null) return Map.of("available", false);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("available", true);
        baseline.put("latencyMs", result.path("withToolsLatencyMs").asLong());
        baseline.put("toolCalls", result.path("toolCalls").asInt());
        baseline.put("toolLoopModelCalls", result.path("toolLoopModelCalls").asInt());
        baseline.put("sectionModelCalls", result.path("toolSectionModelCalls").asInt());
        baseline.put("criticCalls", result.path("criticCalls").asInt());
        baseline.put("reviewCorrectionModelCalls", result.path("reviewCorrectionModelCalls").asInt());
        baseline.put("validatedCandidate", result.path("citedDraftWithTools"));
        baseline.put("publishedSection", result.path("withTools"));
        baseline.put("contentPreservedByCritic", result.path("citedDraftWithTools")
                .equals(result.path("withTools")));
        return Map.copyOf(baseline);
    }

    private boolean sameSectionContent(LessonSection candidate, LessonSection published) {
        return candidate.position() == published.position()
                && candidate.topicKey().equals(published.topicKey())
                && candidate.coverageTags().equals(published.coverageTags())
                && candidate.title().equals(published.title())
                && candidate.required() == published.required()
                && candidate.visualKind() == published.visualKind()
                && candidate.visualCaption().equals(published.visualCaption())
                && candidate.visualSourcePages().equals(published.visualSourcePages())
                && candidate.visualSourceChunkIds().equals(published.visualSourceChunkIds())
                && candidate.steps().equals(published.steps());
    }

    private Map<String, Object> fieldPreservation(
            SectionDraft raw,
            SectionDraft normalized,
            LessonSection candidate,
            LessonSection published) {
        List<Map<String, Object>> stepMappings = java.util.stream.IntStream.range(0, raw.steps().size())
                .mapToObj(index -> {
                    StepDraft rawStep = raw.steps().get(index);
                    StepDraft normalizedStep = normalized.steps().get(index);
                    var candidateStep = candidate.steps().get(index);
                    var publishedStep = published.steps().get(index);
                    Map<String, Object> mapping = new LinkedHashMap<>();
                    mapping.put("position", index + 1);
                    mapping.put("headingExact", rawStep.heading().equals(normalizedStep.heading())
                            && rawStep.heading().equals(candidateStep.heading())
                            && rawStep.heading().equals(publishedStep.heading()));
                    mapping.put("textExact", rawStep.text().equals(normalizedStep.text())
                            && rawStep.text().equals(candidateStep.text())
                            && rawStep.text().equals(publishedStep.text()));
                    mapping.put("kindExact", rawStep.kind() == normalizedStep.kind()
                            && rawStep.kind() == candidateStep.kind()
                            && rawStep.kind() == publishedStep.kind());
                    mapping.put("citationIdsExact", rawStep.citationIds().equals(normalizedStep.citationIds())
                            && rawStep.citationIds().equals(candidateStep.sourceChunkIds())
                            && rawStep.citationIds().equals(publishedStep.sourceChunkIds()));
                    mapping.put("visualFocusExact", sameVisualFocus(rawStep.visualFocus(), candidateStep.visualFocus())
                            && sameVisualFocus(rawStep.visualFocus(), publishedStep.visualFocus()));
                    mapping.put("rawTextLength", rawStep.text().length());
                    mapping.put("publishedTextLength", publishedStep.text().length());
                    return Map.copyOf(mapping);
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rawStructuredToNormalizerRecordEqual", raw.equals(normalized));
        result.put("titleExact", raw.title().equals(normalized.title())
                && raw.title().equals(candidate.title())
                && raw.title().equals(published.title()));
        result.put("captionExact", raw.visualCaption().equals(normalized.visualCaption())
                && raw.visualCaption().equals(candidate.visualCaption())
                && raw.visualCaption().equals(published.visualCaption()));
        result.put("visualKindExact", raw.visualKind() == normalized.visualKind()
                && raw.visualKind() == candidate.visualKind()
                && raw.visualKind() == published.visualKind());
        result.put("visualCitationIdsExact", raw.visualCitationIds().equals(normalized.visualCitationIds())
                && raw.visualCitationIds().equals(candidate.visualSourceChunkIds())
                && raw.visualCitationIds().equals(published.visualSourceChunkIds()));
        result.put("stepCountExact", raw.steps().size() == normalized.steps().size()
                && raw.steps().size() == candidate.steps().size()
                && raw.steps().size() == published.steps().size());
        result.put("allStepTextExactThroughPublication", stepMappings.stream()
                .allMatch(mapping -> Boolean.TRUE.equals(mapping.get("textExact"))));
        result.put("onlyLifecycleStatusChangedAtPublication", sameSectionContent(candidate, published)
                && candidate.evidenceStatus() == EvidenceStatus.CITED_DRAFT
                && published.evidenceStatus() == EvidenceStatus.SUPPORTED);
        result.put("steps", stepMappings);
        return Map.copyOf(result);
    }

    private boolean sameVisualFocus(
            com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft draft,
            com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus focus) {
        if (draft == null || focus == null) return draft == null && focus == null;
        return draft.pageNumber() == focus.pageNumber()
                && draft.label().equals(focus.label())
                && draft.visibleDescription().equals(focus.visibleDescription())
                && draft.x() == focus.x()
                && draft.y() == focus.y()
                && draft.width() == focus.width()
                && draft.height() == focus.height();
    }

    private Map<String, Object> compareTeachingSection(CaseConfiguration case_) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(("teaching-value:" + case_.caseId())
                .getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfTeachingEvidence corpus = new PdfTeachingEvidence(case_.pdf(), versionId);
        TeachingPlan plan = plan(versionId, case_.caseNode(), case_.sourcePages());
        PlannedSection planned = plan.sections().getFirst();
        RuleEvidence initial = corpus.first(case_.initialPage());
        var deterministic = new TeachingSectionEvidenceRetriever.Result(
                List.of(initial), 1, TeachingSectionEvidenceRetriever.State.VERIFIED);

        DirectAuditedInvocations baselineAudit = new DirectAuditedInvocations();
        List<String> baselineRawResponses = new ArrayList<>();
        RecordingTeachingLessonModel baselineModel = new RecordingTeachingLessonModel(
                teachingModel(case_.provider(), baselineRawResponses));
        long baselineStarted = System.nanoTime();
        TeachingSectionDraftCandidate baseline = null;
        String baselineFailure = null;
        try {
            baseline = composeSection(
                    baselineModel, baselineAudit, plan, planned, deterministic.evidence(), runId);
        } catch (IllegalArgumentException rejected) {
            baselineFailure = rejected.getMessage() == null ? "SECTION_REJECTED" : rejected.getMessage();
        }
        long baselineLatencyMs = Duration.ofNanos(System.nanoTime() - baselineStarted).toMillis();

        DirectAuditedInvocations toolAudit = new DirectAuditedInvocations();
        TeachingSourcePageEvidenceRefiner evidenceAgent = refiner(corpus, toolAudit, versionId, runId);
        long toolStarted = System.nanoTime();
        var refined = evidenceAgent.refine(plan, planned, runId, deterministic);
        int evidenceToolModelCalls = toolAudit.modelCalls.get();
        int evidenceToolCalls = toolAudit.toolCalls.get();
        DirectAuditedInvocations compositionAudit = new DirectAuditedInvocations();
        List<String> toolRawResponses = new ArrayList<>();
        RecordingTeachingLessonModel toolModel = new RecordingTeachingLessonModel(
                teachingModel(case_.provider(), toolRawResponses));
        TeachingSectionDraftComposer toolComposer = sectionComposer(toolModel, compositionAudit);
        TeachingSectionDraftCandidate withTools = null;
        String toolFailure = null;
        try {
            withTools = toolComposer.compose(
                    plan,
                    planned,
                    List.of(),
                    refined.evidence(),
                    runId,
                    planned.position() - 1,
                    false);
        } catch (IllegalArgumentException rejected) {
            toolFailure = rejected.getMessage() == null ? "SECTION_REJECTED" : rejected.getMessage();
        }
        DirectAuditedInvocations reviewAudit = new DirectAuditedInvocations();
        List<String> criticRawResponses = new ArrayList<>();
        ProviderConfiguration criticProvider = provider(
                "qwen".equals(case_.provider().provider()) ? "deepseek" : "qwen");
        LessonSection reviewedSection = null;
        if (withTools != null) {
            TeachingSectionDraftCandidate reviewCandidate = withTools;
            List<LessonSection> reviewedSections = new ArrayList<>(List.of(reviewCandidate.section()));
            new TeachingPublishedLessonReviewer(
                            contentCritic(criticProvider, criticRawResponses, reviewAudit),
                            reviewAudit,
                            toolComposer,
                            new TeachingReviewCorrectionPolicy())
                    .review(plan, List.of(reviewCandidate), reviewedSections, runId, () -> {});
            reviewedSection = reviewedSections.getFirst();
        }
        long toolLatencyMs = Duration.ofNanos(System.nanoTime() - toolStarted).toMillis();

        String toolText = reviewedSection == null ? "" : visibleSectionText(reviewedSection);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", case_.caseId());
        result.put("provider", case_.provider().provider());
        result.put("criticProvider", criticProvider.provider());
        result.put("objective", planned.objective());
        result.put("withoutTools", baseline == null
                ? Map.of("status", "REJECTED", "reason", baselineFailure)
                : visibleSection(baseline.section()));
        result.put("withoutToolsDraftAttempts", baselineModel.drafts().stream().map(this::visibleDraft).toList());
        result.put("withoutToolsRawResponses", List.copyOf(baselineRawResponses));
        result.put("withoutToolsUsesExpectedPage", baseline != null && usesExpectedPage(case_, baseline.section()));
        result.put("withoutToolsModelCalls", baselineAudit.modelCalls.get());
        result.put("withoutToolsLatencyMs", baselineLatencyMs);
        result.put("citedDraftWithTools", withTools == null
                ? Map.of("status", "REJECTED", "reason", toolFailure)
                : visibleSection(withTools.section()));
        result.put("withTools", reviewedSection == null
                ? Map.of("status", "REJECTED", "reason", toolFailure)
                : visibleSection(reviewedSection));
        result.put("reviewedEvidenceStatus", reviewedSection == null
                ? "REJECTED"
                : reviewedSection.evidenceStatus().name());
        result.put("withToolsDraftAttempts", toolModel.drafts().stream().map(this::visibleDraft).toList());
        result.put("withToolsRawResponses", List.copyOf(toolRawResponses));
        result.put("criticRawResponses", List.copyOf(criticRawResponses));
        result.put("toolSectionUsesExpectedPage", reviewedSection != null && usesExpectedPage(case_, reviewedSection));
        result.put("evidenceAdded", refined.evidence().size() - deterministic.evidence().size());
        result.put("initialEvidence", visibleEvidence(deterministic.evidence()));
        result.put("toolRefinedEvidence", visibleEvidence(refined.evidence()));
        result.put("toolCalls", evidenceToolCalls);
        result.put("toolLoopModelCalls", evidenceToolModelCalls);
        result.put("toolSectionModelCalls", compositionAudit.modelCalls.get());
        result.put("criticCalls", reviewAudit.criticCalls.get());
        result.put("criticOperations", List.copyOf(reviewAudit.criticOperations));
        result.put("blankCriticResponseAttempts", criticRawResponses.stream()
                .filter(response -> response == null || response.isBlank())
                .count());
        result.put("reviewCorrectionModelCalls", reviewAudit.modelCalls.get());
        result.put("withToolsLatencyMs", toolLatencyMs);
        result.put("withinLatencyBudget", toolLatencyMs < 180_000);
        result.put("boundedToolCalls", evidenceToolCalls == 1);
        result.put("publishableSectionProduced", reviewedSection != null
                && reviewedSection.evidenceStatus() == EvidenceStatus.SUPPORTED);
        result.put("sourceCitationsPresent", reviewedSection != null && reviewedSection.steps().stream()
                .allMatch(step -> !step.sourceChunkIds().isEmpty() && !step.sourcePages().isEmpty()));
        result.put("rawVisibleResponsesRecorded", !toolRawResponses.isEmpty()
                && toolRawResponses.stream().allMatch(java.util.Objects::nonNull)
                && toolRawResponses.stream().anyMatch(response -> !response.isBlank())
                && !criticRawResponses.isEmpty()
                && criticRawResponses.stream().allMatch(java.util.Objects::nonNull)
                && criticRawResponses.stream().anyMatch(response -> !response.isBlank()));
        result.put("completeOutcomesRecordedForInspection", (!toolText.isBlank() || !toolModel.drafts().isEmpty())
                && (baseline != null || baselineFailure != null && !baselineFailure.isBlank()));
        return Map.copyOf(result);
    }

    private JsonNode evaluationCases() throws IOException {
        var input = TeachingEvidenceAgentRealRulebookEvaluationTest.class
                .getResourceAsStream("/evaluation/teaching-agent-cases-v2.json");
        if (input == null) throw new IllegalStateException("teaching evaluation cases are missing");
        try (input) {
            return mapper.readTree(input);
        }
    }

    private void publishReleaseSummary(Path root, List<Map<String, Object>> semanticResults) throws IOException {
        Path output = root.resolve(".local/agent-evaluation/teaching-agent-real-rulebooks.json");
        JsonNode acquisition = mapper.readTree(output.toFile());
        List<Map<String, Object>> summaries = semanticResults.stream()
                .map(result -> Map.<String, Object>of(
                        "caseId", result.get("caseId"),
                        "provider", result.get("provider"),
                        "expectedCoverageAdded", result.get("toolSectionUsesExpectedPage"),
                        "citationAccepted", Boolean.TRUE.equals(result.get("publishableSectionProduced"))
                                && Boolean.TRUE.equals(result.get("sourceCitationsPresent"))
                                && Boolean.TRUE.equals(result.get("toolSectionUsesExpectedPage")),
                        "withinLatencyBudget", result.get("withinLatencyBudget"),
                        "modelCalls", (Integer) result.get("toolSectionModelCalls")
                                + (Integer) result.get("reviewCorrectionModelCalls"),
                        "toolCalls", result.get("toolCalls")))
                .toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("results", acquisition.path("results"));
        report.put("crossRulebookNegative", acquisition.path("crossRulebookNegative"));
        report.put("semanticResults", summaries);
        Files.writeString(
                output,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> visibleEvidence(List<RuleEvidence> evidence) {
        return evidence.stream()
                .map(item -> Map.<String, Object>of(
                        "pageFrom", item.pageFrom(),
                        "pageTo", item.pageTo(),
                        "heading", item.heading(),
                        "excerpt", item.excerpt()))
                .toList();
    }

    private TeachingSectionDraftCandidate composeSection(
            TeachingLessonModel model,
            DirectAuditedInvocations audited,
            TeachingPlan plan,
            PlannedSection planned,
            List<RuleEvidence> evidence,
            UUID runId) {
        return sectionComposer(model, audited).compose(
                plan,
                planned,
                List.of(),
                evidence,
                runId,
                planned.position() - 1,
                false);
    }

    private TeachingSectionDraftComposer sectionComposer(
            TeachingLessonModel model, DirectAuditedInvocations audited) {
        return new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), audited, VisualRulebookPageFacts.empty());
    }

    private Map<String, Object> visibleSection(com.rulepilot.teaching.domain.IllustratedLesson.LessonSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("position", section.position());
        result.put("topicKey", section.topicKey());
        result.put("coverageTags", section.coverageTags());
        result.put("title", section.title());
        result.put("required", section.required());
        result.put("evidenceStatus", section.evidenceStatus().name());
        result.put("visualKind", section.visualKind().name());
        result.put("visualCaption", section.visualCaption());
        result.put("visualSourcePages", section.visualSourcePages());
        result.put("visualSourceChunkIds", section.visualSourceChunkIds());
        result.put("steps", section.steps().stream().map(step -> {
            Map<String, Object> visible = new LinkedHashMap<>();
            visible.put("position", step.position());
            visible.put("heading", step.heading());
            visible.put("kind", step.kind().name());
            visible.put("text", step.text());
            visible.put("sourcePages", step.sourcePages());
            visible.put("sourceChunkIds", step.sourceChunkIds());
            visible.put("visualFocus", visibleFocus(step.visualFocus()));
            return visible;
        }).toList());
        return result;
    }

    private Map<String, Object> visibleDraft(SectionDraft draft) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", draft.title() == null ? "" : draft.title());
        result.put("visualKind", draft.visualKind() == null ? "" : draft.visualKind().name());
        result.put("visualCaption", draft.visualCaption() == null ? "" : draft.visualCaption());
        result.put("visualCitationIds", draft.visualCitationIds());
        result.put("steps", draft.steps().stream().map(step -> {
            if (step == null) return Map.<String, Object>of("invalid", true);
            Map<String, Object> visible = new LinkedHashMap<>();
            visible.put("heading", step.heading() == null ? "" : step.heading());
            visible.put("kind", step.kind() == null ? "" : step.kind().name());
            visible.put("text", step.text() == null ? "" : step.text());
            visible.put("citationIds", step.citationIds());
            visible.put("visualFocus", visibleFocus(step.visualFocus()));
            return visible;
        }).toList());
        return result;
    }

    private Map<String, Object> visibleFocus(Object focus) {
        if (focus == null) return Map.of();
        if (focus instanceof com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft draft) {
            return Map.of(
                    "pageNumber", draft.pageNumber(),
                    "label", draft.label(),
                    "visibleDescription", draft.visibleDescription(),
                    "x", draft.x(),
                    "y", draft.y(),
                    "width", draft.width(),
                    "height", draft.height());
        }
        var domain = (com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus) focus;
        return Map.of(
                "pageNumber", domain.pageNumber(),
                "label", domain.label(),
                "visibleDescription", domain.visibleDescription(),
                "x", domain.x(),
                "y", domain.y(),
                "width", domain.width(),
                "height", domain.height());
    }

    private String visibleSectionText(com.rulepilot.teaching.domain.IllustratedLesson.LessonSection section) {
        return (section.title() + " " + section.visualCaption() + " " + section.steps().stream()
                        .map(step -> step.heading() + " " + step.text())
                        .reduce("", (left, right) -> left + " " + right))
                .replaceAll("\\s+", " ")
                .strip();
    }

    private boolean usesExpectedPage(
            CaseConfiguration case_, com.rulepilot.teaching.domain.IllustratedLesson.LessonSection section) {
        return section.steps().stream().anyMatch(step -> step.sourcePages().contains(case_.expectedPage()));
    }

    private Map<String, Object> runCase(CaseConfiguration case_) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(case_.caseId().getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfTeachingEvidence corpus = new PdfTeachingEvidence(case_.pdf(), versionId);
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        TeachingSourcePageEvidenceRefiner agent = refiner(corpus, audited, versionId, runId);
        TeachingPlan plan = plan(versionId, case_.caseNode(), case_.sourcePages());
        RuleEvidence initial = corpus.first(case_.initialPage());
        var deterministic = new TeachingSectionEvidenceRetriever.Result(
                List.of(initial), 1, TeachingSectionEvidenceRetriever.State.VERIFIED);
        long started = System.nanoTime();

        var refined = agent.refine(plan, plan.sections().getFirst(), runId, deterministic);

        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        RuleEvidence expected = refined.evidence().stream()
                .filter(source -> source.pageFrom() == case_.expectedPage())
                .filter(source -> case_.expectedTerms().stream()
                        .allMatch(term -> source.excerpt().toLowerCase(Locale.ROOT).contains(term)))
                .findFirst()
                .orElse(null);
        boolean citationAccepted = expected != null && citationAccepted(plan, refined.evidence(), expected);
        int modelCallsBeforeDirect = audited.modelCalls.get();
        int toolCallsBeforeDirect = audited.toolCalls.get();
        List<String> toolOperationsBeforeDirect = List.copyOf(audited.toolOperations);
        TeachingPlan directPlan = plan(versionId, case_.caseNode(), List.of(case_.initialPage()));
        var directResult = agent.refine(directPlan, directPlan.sections().getFirst(), runId, deterministic);

        return Map.ofEntries(
                Map.entry("caseId", case_.caseId()),
                Map.entry("provider", case_.provider().provider()),
                Map.entry("toolCalls", toolCallsBeforeDirect),
                Map.entry("toolOperations", toolOperationsBeforeDirect),
                Map.entry("searchDiagnostics", corpus.searchDiagnostics(case_.expectedTerms())),
                Map.entry("modelCalls", modelCallsBeforeDirect),
                Map.entry("expectedCoverageAdded", expected != null),
                Map.entry("citationAccepted", citationAccepted),
                Map.entry("refinedEvidenceCount", refined.evidence().size()),
                Map.entry("refinedPages", refined.evidence().stream()
                        .map(RuleEvidence::pageFrom)
                        .distinct()
                        .toList()),
                Map.entry("directAdditionalModelCalls", audited.modelCalls.get() - modelCallsBeforeDirect),
                Map.entry("directAdditionalToolCalls", audited.toolCalls.get() - toolCallsBeforeDirect),
                Map.entry("directEvidenceUnchanged", directResult == deterministic),
                Map.entry("latencyMs", latencyMs),
                Map.entry("withinLatencyBudget", latencyMs < 90_000));
    }

    private boolean citationAccepted(TeachingPlan plan, List<RuleEvidence> evidence, RuleEvidence expected) {
        var modelRequest = new TeachingSectionModelRequestFactory(com.rulepilot.teaching.VisualRulebookPageFacts.empty())
                .create(
                        plan,
                        plan.sections().getFirst(),
                        List.of(),
                        evidence,
                        false,
                        false);
        var section = new TeachingSectionCandidateValidator(new PolicyEvidenceVerifier()).validate(
                plan,
                plan.sections().getFirst(),
                evidence,
                modelRequest,
                new SectionDraft(
                        "Grounded chapter",
                        VisualKind.REFERENCE_CARD,
                        "Quick procedure reference.",
                        List.of(expected.chunkId()),
                        List.of(new StepDraft(
                                "Check the rule",
                                TeachingMove.DO,
                                "Follow this procedure in order.",
                                List.of(expected.chunkId())))),
                EvidenceStatus.SUPPORTED);
        return section.steps().stream()
                .anyMatch(step -> step.sourceChunkIds().contains(expected.chunkId()));
    }

    private Map<String, Object> runNegative(CaseConfiguration case_, JsonNode node) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(case_.caseId().getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfTeachingEvidence corpus = new PdfTeachingEvidence(case_.pdf(), versionId);
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        TeachingSourcePageEvidenceRefiner agent = refiner(corpus, audited, versionId, runId);
        TeachingPlan plan = plan(versionId, node, List.of());
        var empty = new TeachingSectionEvidenceRetriever.Result(
                List.of(), 1, TeachingSectionEvidenceRetriever.State.EMPTY);

        var result = agent.refine(plan, plan.sections().getFirst(), runId, empty);

        return Map.of(
                "caseId", node.path("caseId").asText(),
                "state", result.state().name(),
                "evidenceCount", result.evidence().size(),
                "toolCalls", audited.toolCalls.get(),
                "crossScopeEvidence", result.evidence().stream()
                        .anyMatch(source -> !versionId.equals(source.documentVersionId())));
    }

    private TeachingSourcePageEvidenceRefiner refiner(
            PdfTeachingEvidence corpus,
            DirectAuditedInvocations audited,
            UUID versionId,
            UUID runId) {
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        when(scopes.create(scope.ownerUsername(), scope.documentVersionId(), scope.runId()))
                .thenReturn(java.util.Optional.of(scope));
        return new TeachingSourcePageEvidenceRefiner(
                scopes, corpus, new PolicyEvidenceVerifier(), audited);
    }

    private TeachingPlan plan(UUID versionId, JsonNode node, List<Integer> sourcePages) {
        List<String> tags = new ArrayList<>();
        node.path("coverageTags").forEach(tag -> tags.add(tag.asText()));
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Opaque evaluation game",
                "Evaluate one source-grounded chapter.",
                List.of(new PlannedSection(
                        1,
                        node.path("topicKey").asText(),
                        node.path("title").asText(),
                        node.path("objective").asText(),
                        true,
                        false,
                        List.of(node.path("objective").asText()),
                        tags,
                        sourcePages)),
                "agent-evaluation",
                Instant.now());
    }

    private SpringAiTeachingLessonModel teachingModel(
            ProviderConfiguration provider, List<String> rawResponses) {
        ChatModel chatModel = recordingChatModel(provider, Duration.ofSeconds(120), rawResponses);
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.TEACHING))
                .thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.VISUAL)).thenReturn("fake");
        when(configuration.modelFor(RuntimeModelConfiguration.Role.TEACHING, "agent-evaluation"))
                .thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.TEACHING, "agent-evaluation"))
                .thenReturn(provider.provider());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.TEACHING, "agent-evaluation"))
                .thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.TEACHING, "agent-evaluation"))
                .thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        RuntimeModelConfiguration.Role.TEACHING, "agent-evaluation"))
                .thenReturn("deepseek".equals(provider.provider()));
        return new SpringAiTeachingLessonModel(
                configuration,
                new FakeTeachingLessonModel(),
                prompts());
    }

    private GeneratedContentCritic contentCritic(
            ProviderConfiguration provider,
            List<String> rawResponses,
            DirectAuditedInvocations audited) {
        ChatModel chatModel = recordingChatModel(provider, Duration.ofSeconds(120), rawResponses);
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(chatModel);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation"))
                .thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation"))
                .thenReturn(provider.provider());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(provider.model());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation"))
                .thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(false);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation"))
                .thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.CRITIC))
                .thenReturn("deepseek".equals(provider.provider()));
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation"))
                .thenReturn("deepseek".equals(provider.provider()));
        var model = new SpringAiContentCriticModel(configuration, new FakeContentCriticModel(), prompts());
        return new ConditionalGeneratedContentCritic(model, audited, true);
    }

    private ChatModel recordingChatModel(
            ProviderConfiguration provider, Duration timeout, List<String> rawResponses) {
        ChatModel delegate = new ChatModelFactory(ObservationRegistry.NOOP, timeout)
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        return new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                var response = delegate.call(prompt);
                String responseText = response == null
                                || response.getResult() == null
                                || response.getResult().getOutput() == null
                        ? ""
                        : response.getResult().getOutput().getText();
                rawResponses.add(responseText == null ? "" : responseText);
                return response;
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                return delegate.getDefaultOptions();
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return delegate.getOptions();
            }
        };
    }

    private VersionedAgentPrompts prompts() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(VersionedAgentPrompts.class);
            context.refresh();
            return context.getBean(VersionedAgentPrompts.class);
        }
    }

    private CaseConfiguration caseFor(
            Path root,
            JsonNode manifest,
            JsonNode inventory,
            JsonNode node,
            ProviderConfiguration provider) {
        String caseId = node.path("caseId").asText();
        JsonNode manifestCase = java.util.stream.StreamSupport.stream(manifest.path("cases").spliterator(), false)
                .filter(candidate -> caseId.equals(candidate.path("caseId").asText()))
                .findFirst()
                .orElseThrow();
        String digest = manifestCase.path("sourceSha256").asText();
        JsonNode source = java.util.stream.StreamSupport.stream(
                        inventory.path("qualifiedRulebooks").spliterator(), false)
                .filter(candidate -> digest.equals(candidate.path("sha256").asText()))
                .findFirst()
                .orElseThrow();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(source.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required");
        List<Integer> pages = new ArrayList<>();
        node.path("sourcePages").forEach(page -> pages.add(page.asInt()));
        List<String> expectedTerms = new ArrayList<>();
        node.path("expectedTerms").forEach(term -> expectedTerms.add(term.asText().toLowerCase(Locale.ROOT)));
        return new CaseConfiguration(
                caseId,
                pdf,
                node,
                node.path("initialPage").asInt(),
                List.copyOf(pages),
                node.path("expectedPage").asInt(),
                List.copyOf(expectedTerms),
                provider);
    }

    private ProviderConfiguration provider(String provider) {
        String prefix = provider.toUpperCase(Locale.ROOT);
        return new ProviderConfiguration(
                provider,
                requiredEnvironment(prefix + "_API_KEY"),
                requiredEnvironment(prefix + "_BASE_URL"),
                requiredEnvironment(prefix + "_MODEL"));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private record CaseConfiguration(
            String caseId,
            Path pdf,
            JsonNode caseNode,
            int initialPage,
            List<Integer> sourcePages,
            int expectedPage,
            List<String> expectedTerms,
            ProviderConfiguration provider) {}

    private record ProviderConfiguration(String provider, String apiKey, String baseUrl, String model) {}

    private static final class RecordingTeachingLessonModel implements TeachingLessonModel {
        private final TeachingLessonModel delegate;
        private final List<SectionDraft> drafts = new ArrayList<>();

        private RecordingTeachingLessonModel(TeachingLessonModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public String providerId() {
            return delegate.providerId();
        }

        @Override
        public boolean supportsVisualEvidence(String modelConfigurationOwner) {
            return delegate.supportsVisualEvidence(modelConfigurationOwner);
        }

        @Override
        public int maxConcurrentSectionRequests(String modelConfigurationOwner) {
            return delegate.maxConcurrentSectionRequests(modelConfigurationOwner);
        }

        @Override
        public SectionDraft compose(SectionRequest request) {
            SectionDraft draft = delegate.compose(request);
            drafts.add(draft);
            return draft;
        }

        @Override
        public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
            SectionDraft draft = delegate.revise(request, previousDraft, feedback);
            drafts.add(draft);
            return draft;
        }

        private List<SectionDraft> drafts() {
            return List.copyOf(drafts);
        }
    }

    private static final class DirectAuditedInvocations implements AuditedAgentInvocations {
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger criticCalls = new AtomicInteger();
        private final List<String> criticOperations = new ArrayList<>();
        private final List<String> toolOperations = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            if (type == ActivityType.MODEL) modelCalls.incrementAndGet();
            if (type == ActivityType.TOOL) {
                toolCalls.incrementAndGet();
                toolOperations.add(operation);
            }
            if (type == ActivityType.CRITIC) {
                criticCalls.incrementAndGet();
                criticOperations.add(operation);
            }
            return invocation.get();
        }

        @Override
        public void record(UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {}
    }

    private static final class PdfTeachingEvidence implements AssistantReadTools {
        private static final Pattern TERMS = Pattern.compile("[\\p{L}\\p{N}]{3,}");
        private static final Set<String> STOP_TERMS = Set.of(
                "and", "are", "can", "does", "every", "for", "from", "how", "the", "then", "what", "when", "with");
        private final UUID versionId;
        private final List<String> pages;
        private final List<RuleEvidence> chunks;
        private final List<SearchRuleEvidence> searches = new ArrayList<>();

        private PdfTeachingEvidence(Path pdf, UUID versionId) throws IOException {
            this.versionId = versionId;
            this.pages = extractPages(pdf.toFile());
            this.chunks = buildChunks();
        }

        @Override
        public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
            if (!versionId.equals(request.documentVersionId())) throw new IllegalArgumentException("scope mismatch");
            searches.add(request);
            Set<String> terms = terms(request.query());
            if (terms.isEmpty()) return List.of();
            return chunks.stream()
                    .map(source -> new ScoredEvidence(source, score(source.excerpt(), terms)))
                    .filter(candidate -> candidate.score() > 0)
                    .sorted(Comparator.comparingInt(ScoredEvidence::score).reversed()
                            .thenComparing(candidate -> candidate.source().chunkId()))
                    .limit(request.limit())
                    .map(ScoredEvidence::source)
                    .toList();
        }

        private List<Map<String, Object>> searchDiagnostics(List<String> expectedTerms) {
            return searches.stream().map(search -> Map.<String, Object>of(
                            "queryLength", search.query().length(),
                            "containsHan", search.query().codePoints().anyMatch(codePoint ->
                                    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN),
                            "expectedTermMatches", expectedTerms.stream()
                                    .filter(term -> search.query().toLowerCase(Locale.ROOT).contains(term))
                                    .count(),
                            "sectionTypeCount", search.sectionTypes().size(),
                            "includeAdjacentContext", search.includeAdjacentContext()))
                    .toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidencePages(
                UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
            if (!versionId.equals(documentVersionId) || includePageImages) throw new IllegalArgumentException("scope mismatch");
            return chunks.stream().filter(source -> pageNumbers.contains(source.pageFrom())).toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidenceIds(UUID documentVersionId, Set<UUID> evidenceIds) {
            if (!versionId.equals(documentVersionId)) return List.of();
            return chunks.stream().filter(source -> evidenceIds.contains(source.chunkId())).toList();
        }

        private RuleEvidence first(int page) {
            return chunks.stream().filter(source -> source.pageFrom() == page).findFirst().orElseThrow();
        }

        private List<RuleEvidence> buildChunks() {
            List<RuleEvidence> sources = new ArrayList<>();
            for (int page = 1; page <= pages.size(); page++) {
                String text = pages.get(page - 1).replaceAll("\\s+", " ").strip();
                int sequence = 0;
                for (int start = 0; start < text.length(); start += 1000) {
                    int end = Math.min(text.length(), start + 1200);
                    String excerpt = text.substring(start, end).strip();
                    if (!excerpt.isBlank()) {
                        sources.add(new RuleEvidence(
                                UUID.nameUUIDFromBytes((versionId + ":" + page + ":" + sequence)
                                        .getBytes(StandardCharsets.UTF_8)),
                                versionId,
                                "PAGE",
                                "Rulebook page " + page + " segment " + (sequence + 1),
                                excerpt,
                                page,
                                page));
                    }
                    sequence++;
                }
            }
            return List.copyOf(sources);
        }

        private Set<String> terms(String query) {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            var matcher = TERMS.matcher(query.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String term = matcher.group();
                if (!STOP_TERMS.contains(term)) terms.add(term);
            }
            return Set.copyOf(terms);
        }

        private int score(String text, Set<String> terms) {
            String normalized = text.toLowerCase(Locale.ROOT);
            return (int) terms.stream().filter(normalized::contains).count();
        }

        private static List<String> extractPages(File pdf) throws IOException {
            try (PDDocument document = Loader.loadPDF(pdf)) {
                List<String> pages = new ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper();
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    pages.add(stripper.getText(document));
                }
                return List.copyOf(pages);
            }
        }

        private record ScoredEvidence(RuleEvidence source, int score) {}
    }
}
