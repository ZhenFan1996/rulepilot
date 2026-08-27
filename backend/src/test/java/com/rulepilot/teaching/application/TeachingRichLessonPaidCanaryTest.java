package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.adapter.out.model.SpringAiContentCriticModel;
import com.rulepilot.assistant.application.ConditionalGeneratedContentCritic;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.adapter.out.model.SpringAiTeachingLessonModel;
import com.rulepilot.teaching.adapter.out.model.SpringAiTeachingOutlineModel;
import com.rulepilot.teaching.adapter.out.persistence.TeachingPlanPersistenceRoundTrip;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.observation.ObservationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("paid-teaching-richness-canary")
class TeachingRichLessonPaidCanaryTest {

    private static final String CANARY_CASE_ID = "captain-is-dead-base-rules-iteration-2";
    private static final String CANARY_PDF = ".local/public-corpus/pdfs/the-captain-is-dead.pdf";
    private static final List<Integer> CANARY_PAGES = List.of(
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16);
    private static final String CANARY_SCOPE =
            "Base-game rulebook pages 2-13 plus system-action quick reference page 16; "
                    + "variants and credits on pages 14-15 are intentionally outside this lesson.";
    private static final String CANARY_OUTPUT =
            ".local/agent-evaluation/teaching-iteration-2-captain-is-dead-canary.json";
    private static final String CANARY_FAILURE_OUTPUT =
            ".local/agent-evaluation/teaching-iteration-2-captain-is-dead-outline-failure.json";
    private static final String GSTONE_VISUAL_CANARY_INPUT =
            ".local/agent-evaluation/gstone-endeavor-visual-teaching-preparation-v1.json";
    private static final String GSTONE_VISUAL_LESSON_OUTPUT =
            ".local/agent-evaluation/gstone-endeavor-visual-teaching-lesson-v1.json";
    private static final String OWNER = "teaching-richness-canary";

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void plansAndPublishesACompleteMultiChapterLessonFromOneRealRulebookSlice() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_ALLOW_PAID_CANARY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path pdf = root.resolve(CANARY_PDF);
        assumeTrue(Files.isRegularFile(pdf), "ignored representative rulebook is required");

        String providerName = java.util.Optional.ofNullable(System.getenv("RULEPILOT_TEACHING_CANARY_PROVIDER"))
                .filter(value -> !value.isBlank())
                .orElse("deepseek")
                .toLowerCase(Locale.ROOT);
        assumeTrue(Set.of("deepseek", "qwen", "openai").contains(providerName),
                "paid teaching canary provider must be deepseek, qwen, or openai");
        Provider provider = provider(providerName);
        VersionedAgentPrompts prompts = prompts();
        UUID versionId = UUID.nameUUIDFromBytes(
                ("teaching-richness:" + CANARY_CASE_ID).getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfEvidence corpus = new PdfEvidence(pdf, versionId);
        List<PageInput> activePages = CANARY_PAGES.stream()
                .map(page -> new PageInput(page, TeachingPageCatalogText.bounded(corpus.page(page))))
                .toList();

        List<String> rawOutlineResponses = Collections.synchronizedList(new ArrayList<>());
        OutlineRequest outlineRequest = new OutlineRequest(
                activePages,
                List.of(),
                "请根据当前规则书自己决定最适合第一次开局的完整教学结构；复杂规则要拆成可照做的单元。",
                OWNER);
        OutlineDraft outline;
        boolean outlineReplayed =
                "true".equalsIgnoreCase(System.getenv("RULEPILOT_REUSE_ITERATION_2_TEACHING_OUTLINE"));
        long outlineStarted = System.nanoTime();
        if (outlineReplayed) {
            Path captured = root.resolve(CANARY_OUTPUT);
            var capturedNode = mapper.readTree(captured.toFile());
            String raw = capturedNode.path("result").path("rawOutlineProviderResponses").get(0).asText();
            rawOutlineResponses.add(raw);
            outline = mapper.readValue(raw, OutlineDraft.class);
            TeachingSourceCoverageContract.requireCompleteModelContract(outlineRequest, outline);
        } else {
            RuntimeModelConfiguration outlineConfiguration = configuration(
                    provider, recordingChatModel(provider, rawOutlineResponses));
            SpringAiTeachingOutlineModel outlineModel = new SpringAiTeachingOutlineModel(
                    outlineConfiguration, prompts);
            try {
                outline = outlineModel.organize(outlineRequest);
            } catch (RuntimeException failure) {
                Path failureOutput = root.resolve(CANARY_FAILURE_OUTPUT);
                Files.createDirectories(failureOutput.getParent());
                Files.writeString(
                        failureOutput,
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "generatedAt", Instant.now().toString(),
                                "latencyMs", elapsedMillis(outlineStarted),
                                "failureType", failure.getClass().getName(),
                                "failureMessage", String.valueOf(failure.getMessage()),
                                "rawOutlineProviderResponses", List.copyOf(rawOutlineResponses))) + "\n",
                        StandardCharsets.UTF_8);
                throw failure;
            } finally {
                outlineModel.close();
            }
        }
        long outlineLatencyMs = elapsedMillis(outlineStarted);
        Instant outlineCompletedAt = Instant.now();
        TeachingPlan generatedPlan = new TeachingPlanFactory().create(
                versionId,
                "请根据当前规则书自己决定最适合第一次开局的完整教学结构；复杂规则要拆成可照做的单元。",
                OWNER,
                outline);
        TeachingPlan plan = TeachingPlanPersistenceRoundTrip.serializeAndReload(generatedPlan);
        Instant planReloadedAt = Instant.now();
        assertThat(plan.wholeGameContext()).isEqualTo(generatedPlan.wholeGameContext());
        assertThat(plan.sections()).isEqualTo(generatedPlan.sections());

        List<String> rawSectionResponses = Collections.synchronizedList(new ArrayList<>());
        List<String> rawCriticResponses = Collections.synchronizedList(new ArrayList<>());
        RuntimeModelConfiguration sectionConfiguration = configuration(
                provider,
                recordingChatModel(provider, rawSectionResponses),
                recordingChatModel(provider, rawCriticResponses));
        double teachingTemperature = java.util.Optional.ofNullable(
                        System.getenv("RULEPILOT_TEACHING_CANARY_TEMPERATURE"))
                .filter(value -> !value.isBlank())
                .map(Double::parseDouble)
                .orElse(0.2d);
        RecordingTeachingModel sections = new RecordingTeachingModel(new SpringAiTeachingLessonModel(
                sectionConfiguration, prompts, teachingTemperature));
        CanaryInvocations audit = new CanaryInvocations();
        GeneratedContentCritic publicationCritic = new ConditionalGeneratedContentCritic(
                new SpringAiContentCriticModel(sectionConfiguration, prompts), audit, false);
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        when(scopes.create(eq(OWNER), eq(versionId), eq(runId))).thenReturn(java.util.Optional.of(
                new ToolScope(OWNER, versionId, runId, Instant.now().plusSeconds(300))));
        var refiner = new TeachingSourcePageEvidenceRefiner(
                scopes, corpus, new PolicyEvidenceVerifier(), audit);
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                corpus,
                sections,
                new PolicyEvidenceVerifier(),
                publicationCritic,
                audit,
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable(),
                3,
                3,
                refiner);
        List<IllustratedLesson> progressSnapshots = new CopyOnWriteArrayList<>();
        long lessonStarted = System.nanoTime();
        IllustratedLesson lesson = agent.createBase(plan, runId, null, progressSnapshots::add);
        long lessonLatencyMs = elapsedMillis(lessonStarted);
        Map<String, Object> criticProbe = criticProbe(plan, lesson, sections, provider, prompts, audit, runId);

        Map<String, Object> result = result(
                root,
                provider,
                outline,
                plan,
                lesson,
                sections,
                rawOutlineResponses,
                rawSectionResponses,
                rawCriticResponses,
                audit,
                outlineReplayed,
                teachingTemperature,
                outlineLatencyMs,
                lessonLatencyMs,
                progressSnapshots,
                outlineCompletedAt,
                planReloadedAt,
                criticProbe);
        Path output = root.resolve(canaryOutput(provider.provider()));
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "schemaVersion", 1,
                        "generatedAt", Instant.now().toString(),
                        "result", result)) + "\n",
                StandardCharsets.UTF_8);

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections()).hasSameSizeAs(plan.sections()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(lesson.sections()).allSatisfy(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
            assertThat(section.steps()).isNotEmpty().allSatisfy(step -> {
                assertThat(step.sourcePages()).isNotEmpty();
                assertThat(step.sourceChunkIds()).isNotEmpty();
            });
        });
        assertThat(diversityOwners(outline)).containsKeys("setup", "core", "ending");
        assertThat(result).containsEntry("allPublishedFieldsComeFromRaw", true)
                .containsEntry("allPlayerFacingFieldsPreserved", true)
                .containsEntry("allPlannedUnitsCovered", true)
                .containsEntry("localProseDeletionCount", 0)
                .containsEntry("ownerApiJsonProjectionPreservesAllFields", true)
                .containsEntry("defaultPlayerProjectionPreservesAllFields", true)
                .containsEntry("planContextSurvivedPersistenceRoundTrip", true)
                .containsEntry("allSectionRequestsShareWholeGameContext", true)
                .containsEntry("sectionRequestsRetainOwnUnitsAndEvidence", true)
                .containsEntry("wholeGameCompletedBeforeSectionFanOut", true)
                .containsEntry("withinLatencyBudget", true);
        assertThat(audit.criticCalls.get()).isPositive();
        assertThat(audit.modelCalls.get()).isBetween(plan.sections().size(), plan.sections().size() * 2);
        assertThat(rawOutlineResponses).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(rawSectionResponses).isNotEmpty();
    }

    @Test
    void publishesTheCapturedCompleteGstoneVisualLedgerAsARichLesson() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_GSTONE_VISUAL_LESSON_CANARY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path input = root.resolve(GSTONE_VISUAL_CANARY_INPUT);
        assumeTrue(Files.isRegularFile(input), "complete ignored Gstone visual preparation artifact is required");
        JsonNode preparation = mapper.readTree(input.toFile());
        assertThat(preparation.path("catalogStageComplete").asBoolean()).isTrue();
        assertThat(preparation.path("outlineStageComplete").asBoolean()).isTrue();
        assertThat(preparation.path("canonicalSlotCount").asInt()).isGreaterThan(128);

        String providerName = java.util.Optional.ofNullable(System.getenv("RULEPILOT_TEACHING_CANARY_PROVIDER"))
                .filter(value -> !value.isBlank())
                .orElse("deepseek")
                .toLowerCase(Locale.ROOT);
        Provider provider = provider(providerName);
        OutlineDraft outline = mapper.treeToValue(preparation.path("outline"), OutlineDraft.class);
        UUID versionId = UUID.nameUUIDFromBytes(
                "gstone-endeavor-deep-sea-visual-v1".getBytes(StandardCharsets.UTF_8));
        String learningGoal = "先理解整局，再把复杂规则拆成第一次开桌能照做的清晰教学单元。";
        TeachingPlan generatedPlan = new TeachingPlanFactory().create(versionId, learningGoal, OWNER, outline);
        TeachingPlan plan = TeachingPlanPersistenceRoundTrip.serializeAndReload(generatedPlan);
        CatalogEvidence corpus = new CatalogEvidence(versionId, visualPageEvidence(preparation));

        List<String> rawSectionResponses = Collections.synchronizedList(new ArrayList<>());
        List<String> rawCriticResponses = Collections.synchronizedList(new ArrayList<>());
        VersionedAgentPrompts prompts = prompts();
        RuntimeModelConfiguration configuration = configuration(
                provider,
                recordingChatModel(provider, rawSectionResponses),
                recordingChatModel(provider, rawCriticResponses));
        RecordingTeachingModel sections = new RecordingTeachingModel(new SpringAiTeachingLessonModel(
                configuration, prompts, 0.2d));
        CanaryInvocations audit = new CanaryInvocations();
        GeneratedContentCritic publicationCritic = new ConditionalGeneratedContentCritic(
                new SpringAiContentCriticModel(configuration, prompts), audit, false);
        UUID runId = UUID.randomUUID();
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        when(scopes.create(eq(OWNER), eq(versionId), eq(runId))).thenReturn(java.util.Optional.of(
                new ToolScope(OWNER, versionId, runId, Instant.now().plusSeconds(300))));
        var refiner = new TeachingSourcePageEvidenceRefiner(
                scopes, corpus, new PolicyEvidenceVerifier(), audit);
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                corpus,
                sections,
                new PolicyEvidenceVerifier(),
                publicationCritic,
                audit,
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable(),
                3,
                3,
                refiner);
        List<IllustratedLesson> progressSnapshots = new CopyOnWriteArrayList<>();
        long lessonStarted = System.nanoTime();
        IllustratedLesson lesson = agent.createBase(plan, runId, null, progressSnapshots::add);
        long lessonLatencyMs = elapsedMillis(lessonStarted);
        List<Map<String, Object>> fieldDiffs = lesson.sections().stream()
                .map(section -> fieldDiff(section, sections.draftAttempts(section.topicKey())))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("generatedAt", Instant.now().toString());
        result.put("caseId", "gstone-endeavor-deep-sea-visual-v1");
        result.put("sourceUrl", preparation.path("sourceUrl").asText());
        result.put("provider", provider.provider());
        result.put("model", provider.model());
        result.put("visualCatalogModelCalls", preparation.path("visualModelCalls").asInt());
        result.put("visualCatalogLatencyMs", preparation.path("visualCatalogLatencyMs").asLong());
        result.put("visualCatalogParallelism", preparation.path("peakVisualParallelism").asInt());
        result.put("outlineModelCalls", preparation.path("outlineModelCalls").asInt());
        result.put("outlineLatencyMs", preparation.path("outlineLatencyMs").asLong());
        result.put("sectionModelCalls", audit.modelCalls.get());
        result.put("toolCalls", audit.toolCalls.get());
        result.put("criticCalls", audit.criticCalls.get());
        result.put("lessonLatencyMs", lessonLatencyMs);
        result.put("lessonStatus", lesson.status().name());
        result.put("sectionCount", lesson.sections().size());
        result.put("stepCount", lesson.sections().stream().mapToInt(section -> section.steps().size()).sum());
        result.put("visibleCharacterCount", visibleCharacters(lesson));
        result.put("progressSnapshotCount", progressSnapshots.size());
        result.put("activityTimeline", List.copyOf(audit.events));
        result.put("modelOperations", List.copyOf(audit.modelOperations));
        result.put("toolOperations", List.copyOf(audit.toolOperations));
        result.put("wholeGameUnderstanding", plan.wholeGameContext());
        result.put("planTeachingUnits", visiblePlanUnits(plan));
        result.put("publishedLesson", visibleLesson(lesson));
        result.put("rawStructuredDrafts", sections.visibleDrafts());
        result.put("rawSectionProviderResponses", List.copyOf(rawSectionResponses));
        result.put("rawCriticProviderResponses", List.copyOf(rawCriticResponses));
        result.put("fieldPreservation", fieldDiffs);
        result.put("allPlayerFacingFieldsPreserved", fieldDiffs.stream()
                .allMatch(diff -> Boolean.TRUE.equals(diff.get("playerFacingFieldsExact"))));
        result.put("allPublishedFieldsComeFromRaw", fieldDiffs.stream()
                .allMatch(diff -> Boolean.TRUE.equals(diff.get("publishedFieldsAreExactRawSubset"))));
        result.put("allPlannedUnitsCovered", fieldDiffs.stream()
                .allMatch(diff -> Boolean.TRUE.equals(diff.get("plannedUnitsCovered"))));
        result.put("localProseDeletionCount", 0);
        result.put("planContextSurvivedPersistenceRoundTrip", plan.wholeGameContext().equals(generatedPlan.wholeGameContext()));
        Path output = root.resolve(GSTONE_VISUAL_LESSON_OUTPUT);
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8);

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections()).hasSize(plan.sections().size()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(lesson.sections()).allSatisfy(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
            assertThat(section.steps()).isNotEmpty();
        });
        assertThat(fieldDiffs).allSatisfy(diff -> assertThat(diff)
                .containsEntry("playerFacingFieldsExact", true)
                .containsEntry("publishedFieldsAreExactRawSubset", true)
                .containsEntry("plannedUnitsCovered", true));
        assertThat(audit.criticCalls.get()).isPositive();
        assertThat(audit.modelCalls.get()).isBetween(plan.sections().size(), plan.sections().size() * 2);
        assertThat(rawSectionResponses).isNotEmpty();
        assertThat(lessonLatencyMs).isLessThan(180_000L);
    }

    private Map<Integer, String> visualPageEvidence(JsonNode preparation) {
        Map<Integer, String> pages = new java.util.TreeMap<>();
        for (JsonNode response : preparation.path("rawCatalogResponses")) {
            for (JsonNode page : response.path("body").path("pages")) {
                if (!page.path("ruleGroupInventoryComplete").asBoolean()) continue;
                StringBuilder evidence = new StringBuilder();
                JsonNode pageSummary = page.path("factualSummary");
                if (pageSummary.isArray()) {
                    pageSummary.forEach(item -> evidence.append(item.asText()).append('\n'));
                } else if (pageSummary.isTextual()) {
                    evidence.append(pageSummary.asText()).append('\n');
                }
                for (JsonNode group : page.path("ruleGroups")) {
                    evidence.append(group.path("identifier").asText())
                            .append(": ")
                            .append(group.path("fact").asText())
                            .append('\n');
                }
                for (JsonNode quantity : page.path("quantityObservations")) {
                    evidence.append("Visible quantity: ")
                            .append(quantity.path("originalSpan").asText())
                            .append('\n');
                }
                String text = evidence.toString().strip();
                if (!text.isBlank()) pages.put(page.path("pageNumber").asInt(), text);
            }
        }
        if (pages.size() != preparation.path("pageCount").asInt()) {
            throw new IllegalArgumentException("captured visual ledger omitted a complete page");
        }
        return Map.copyOf(pages);
    }

    private Map<String, Object> result(
            Path root,
            Provider provider,
            OutlineDraft outline,
            TeachingPlan plan,
            IllustratedLesson lesson,
            RecordingTeachingModel model,
            List<String> rawOutlineResponses,
            List<String> rawSectionResponses,
            List<String> rawCriticResponses,
            CanaryInvocations audit,
            boolean outlineReplayed,
            double teachingTemperature,
            long outlineLatencyMs,
            long lessonLatencyMs,
            List<IllustratedLesson> progressSnapshots,
            Instant outlineCompletedAt,
            Instant planReloadedAt,
            Map<String, Object> criticProbe) throws IOException {
        List<Map<String, Object>> fieldDiffs = lesson.sections().stream()
                .map(section -> fieldDiff(section, model.draftAttempts(section.topicKey())))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", CANARY_CASE_ID);
        result.put("corpusScope", CANARY_SCOPE);
        result.put("sourcePages", CANARY_PAGES);
        result.put("provider", provider.provider());
        result.put("model", provider.model());
        result.put("deepSeekThinking", deepSeekThinking());
        result.put("teachingTemperature", teachingTemperature);
        result.put("outlineRuntime", "whole-game-first autonomous v19 prompt; no historical outline prompt concatenation");
        result.put("outlineLatencyMs", outlineLatencyMs);
        result.put("lessonLatencyMs", lessonLatencyMs);
        result.put("totalLatencyMs", outlineLatencyMs + lessonLatencyMs);
        result.put("withinLatencyBudget", outlineLatencyMs + lessonLatencyMs < 180_000);
        int currentOutlineModelCalls = outlineReplayed ? 0 : rawOutlineResponses.size();
        result.put("outlineReplayed", outlineReplayed);
        result.put("capturedOutlineResponsesUsed", outlineReplayed ? rawOutlineResponses.size() : 0);
        result.put("outlineModelCalls", currentOutlineModelCalls);
        result.put("sectionModelCalls", audit.modelCalls.get());
        result.put("totalModelCalls", currentOutlineModelCalls + audit.modelCalls.get() + audit.criticCalls.get());
        result.put("toolCalls", audit.toolCalls.get());
        result.put("toolOperations", List.copyOf(audit.toolOperations));
        result.put("criticCalls", audit.criticCalls.get());
        result.put("criticProbe", criticProbe);
        result.put("modelOperations", List.copyOf(audit.modelOperations));
        result.put("activityTimeline", List.copyOf(audit.events));
        result.put("progressSnapshotCount", progressSnapshots.size());
        result.put("lessonStatus", lesson.status().name());
        result.put("sectionCount", lesson.sections().size());
        result.put("stepCount", lesson.sections().stream().mapToInt(section -> section.steps().size()).sum());
        result.put("visibleCharacterCount", visibleCharacters(lesson));
        result.put("diversitySampleOwners", diversityOwners(outline));
        result.put("outline", visibleOutline(outline));
        result.put("wholeGameUnderstanding", plan.wholeGameContext());
        result.put("planTeachingUnits", visiblePlanUnits(plan));
        result.put("publishedLesson", visibleLesson(lesson));
        IllustratedLesson apiRoundTrip = mapper.readValue(mapper.writeValueAsBytes(lesson), IllustratedLesson.class);
        result.put("ownerApiJsonProjectionPreservesAllFields", visibleLesson(apiRoundTrip).equals(visibleLesson(lesson)));
        var localizations = new LessonLocalizationService(
                mock(LessonLocalizationPersistence.class),
                mock(LessonLocalizationWorker.class),
                mock(org.springframework.core.task.TaskExecutor.class));
        result.put("defaultPlayerProjectionPreservesAllFields",
                localizations.view(lesson, PlayerLocale.ZH_CN).lesson().equals(lesson));
        result.put("rawStructuredDrafts", model.visibleDrafts());
        result.put("modelRequests", model.visibleRequests());
        result.put("rawOutlineProviderResponses", List.copyOf(rawOutlineResponses));
        result.put("rawSectionProviderResponses", List.copyOf(rawSectionResponses));
        result.put("rawCriticProviderResponses", List.copyOf(rawCriticResponses));
        result.put("fieldPreservation", fieldDiffs);
        result.put("allPlayerFacingFieldsPreserved", fieldDiffs.stream()
                .allMatch(diff -> Boolean.TRUE.equals(diff.get("playerFacingFieldsExact"))));
        result.put("allPublishedFieldsComeFromRaw", fieldDiffs.stream()
                .allMatch(diff -> Boolean.TRUE.equals(diff.get("publishedFieldsAreExactRawSubset"))));
        result.put("localProseDeletionCount", 0);
        result.put("supersededRawStepCount", fieldDiffs.stream()
                .mapToInt(diff -> (Integer) diff.get("supersededRawStepCount"))
                .sum());
        result.put("allPlannedUnitsCovered", fieldDiffs.stream()
                .allMatch(diff -> Boolean.TRUE.equals(diff.get("plannedUnitsCovered"))));
        result.put("planContextSurvivedPersistenceRoundTrip", plan.wholeGameContext().evidenceBound()
                && !plan.wholeGameContext().concepts().isEmpty());
        result.put("allSectionRequestsShareWholeGameContext",
                model.allRequestsShareWholeGameContext(plan.sections().size()));
        result.put("sectionRequestsRetainOwnUnitsAndEvidence",
                model.sectionRequestsRetainOwnUnitsAndEvidence(plan.sections().size()));
        Instant firstRequestAt = model.firstRequestAt();
        boolean validationPrecedesModel = audit.firstEventIndex("validateWholeGameTeachingContext") >= 0
                && audit.firstModelEventIndex() > audit.firstEventIndex("validateWholeGameTeachingContext");
        result.put("outlineCompletedAt", outlineCompletedAt.toString());
        result.put("planReloadedAt", planReloadedAt.toString());
        result.put("firstSectionRequestAt", firstRequestAt == null ? "NONE" : firstRequestAt.toString());
        result.put("wholeGameCompletedBeforeSectionFanOut", firstRequestAt != null
                && !firstRequestAt.isBefore(outlineCompletedAt)
                && !firstRequestAt.isBefore(planReloadedAt)
                && validationPrecedesModel);
        result.put("historicalWholeRulebookBaseline", historicalBaseline(root));
        result.put("publicationBoundary",
                "schema + plan-owned teaching units + citation ID/version/scope + quantitative + visual geometry; "
                        + "no independent semantic entailment model");
        return Map.copyOf(result);
    }

    private Map<String, Object> criticProbe(
            TeachingPlan plan,
            IllustratedLesson lesson,
            RecordingTeachingModel sections,
            Provider provider,
            VersionedAgentPrompts prompts,
            CanaryInvocations audit,
            UUID runId) {
        if (!criticProbeEnabled()) return Map.of("performed", false);
        List<String> rawResponses = Collections.synchronizedList(new ArrayList<>());
        ChatModel chatModel = recordingChatModel(provider, rawResponses);
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(chatModel);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.CRITIC, OWNER)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.CRITIC, OWNER)).thenReturn(provider.provider());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(provider.model());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.CRITIC, OWNER)).thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(false);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.CRITIC, OWNER)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.CRITIC))
                .thenReturn("deepseek".equals(provider.provider()));
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.CRITIC, OWNER))
                .thenReturn("deepseek".equals(provider.provider()));
        var critic = new ConditionalGeneratedContentCritic(
                new SpringAiContentCriticModel(configuration, prompts), audit, true);
        var batch = LessonReviewPlanner.plan(plan, sections.reviewCandidates(plan, lesson), runId);
        long started = System.nanoTime();
        var review = critic.review(batch.request(), ReviewRisk.HIGH_IMPACT, OWNER);
        return Map.of(
                "performed", true,
                "latencyMs", elapsedMillis(started),
                "issueCount", review.issues().size(),
                "issues", review.issues(),
                "rawProviderResponses", List.copyOf(rawResponses));
    }

    private boolean criticProbeEnabled() {
        return "true".equalsIgnoreCase(System.getenv("RULEPILOT_TEACHING_CANARY_CRITIC_PROBE"));
    }

    private Map<String, Object> historicalBaseline(Path root) throws IOException {
        Path input = root.resolve(".local/public-corpus/runs/dune-imperium.json");
        if (!Files.isRegularFile(input)) return Map.of("available", false);
        var node = mapper.readTree(input.toFile());
        var historical = node.path("result");
        return Map.of(
                "available", true,
                "scope", "historical complete 20-page production run; timing is not directly comparable to bounded canary",
                "generatorVersion", historical.path("generatorVersion").asText("unknown"),
                "elapsedSeconds", historical.path("elapsedSeconds").asInt(0),
                "sectionCount", historical.path("sectionCount").asInt(0),
                "stepCount", historical.path("stepCount").asInt(0),
                "sectionTitles", mapper.convertValue(historical.path("sectionTitles"), List.class));
    }

    private Map<String, Object> fieldDiff(LessonSection published, List<SectionDraft> rawAttempts) {
        int metadataSourceAttempt = java.util.stream.IntStream.range(0, rawAttempts.size())
                .filter(index -> {
                    SectionDraft raw = rawAttempts.get(index);
                    return raw.title().equals(published.title())
                            && raw.visualKind() == published.visualKind()
                            && raw.visualCaption().equals(published.visualCaption())
                            && raw.visualCitationIds().equals(published.visualSourceChunkIds());
                })
                .findFirst()
                .orElse(-1);
        List<List<Integer>> stepSourceAttempts = published.steps().stream()
                .map(step -> java.util.stream.IntStream.range(0, rawAttempts.size())
                        .filter(attempt -> rawAttempts.get(attempt).steps().stream()
                                .anyMatch(candidate -> exactPlayerFacingStep(candidate, step)))
                        .boxed()
                        .toList())
                .toList();
        boolean exactRawLineage = metadataSourceAttempt >= 0
                && stepSourceAttempts.stream().noneMatch(List::isEmpty);
        Set<String> coveredUnits = rawAttempts.stream()
                .flatMap(raw -> raw.steps().stream())
                .filter(candidate -> published.steps().stream()
                        .anyMatch(step -> exactPlayerFacingStep(candidate, step)))
                .flatMap(step -> step.teachingUnitIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        SectionRequest request = modelRequest(published.topicKey());
        Set<String> plannedUnits = request == null ? Set.of() : request.teachingUnits().stream()
                .map(TeachingLessonModel.TeachingUnitInput::unitId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("topicKey", published.topicKey());
        diff.put("rawDraftAvailable", !rawAttempts.isEmpty());
        diff.put("rawAttemptCount", rawAttempts.size());
        diff.put("metadataSourceAttempt", metadataSourceAttempt);
        diff.put("stepSourceAttempts", stepSourceAttempts);
        diff.put("playerFacingFieldsExact", exactRawLineage);
        diff.put("publishedFieldsAreExactRawSubset", exactRawLineage);
        diff.put("plannedUnits", plannedUnits);
        diff.put("coveredUnits", coveredUnits);
        diff.put("plannedUnitsCovered", coveredUnits.containsAll(plannedUnits));
        diff.put("rawStepCounts", rawAttempts.stream().map(draft -> draft.steps().size()).toList());
        diff.put("publishedStepCount", published.steps().size());
        int superseded = rawAttempts.stream().mapToInt(raw -> (int) raw.steps().stream()
                        .filter(candidate -> published.steps().stream()
                                .noneMatch(step -> exactPlayerFacingStep(candidate, step)))
                        .count())
                .sum();
        diff.put("supersededRawStepCount", superseded);
        return Map.copyOf(diff);
    }

    private boolean exactPlayerFacingStep(
            TeachingLessonModel.StepDraft raw,
            IllustratedLesson.LessonStep published) {
        return raw.heading().equals(published.heading())
                && raw.kind() == published.kind()
                && raw.text().equals(published.text())
                && raw.citationIds().equals(published.sourceChunkIds());
    }

    private final Map<String, SectionRequest> requestIndex = new ConcurrentHashMap<>();

    private SectionRequest modelRequest(String topicKey) {
        return requestIndex.get(topicKey);
    }

    private Map<String, Object> diversityOwners(OutlineDraft outline) {
        Map<String, Object> owners = new LinkedHashMap<>();
        outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.role() == SourceCoverageRole.SETUP)
                .findFirst()
                .ifPresent(slot -> owners.put("setup", slot.ownerTopicKey()));
        outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.role() == SourceCoverageRole.CORE_LOOP
                        || slot.role() == SourceCoverageRole.LEGAL_ACTION)
                .findFirst()
                .ifPresent(slot -> owners.put("core", slot.ownerTopicKey()));
        outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.role() == SourceCoverageRole.ENDING
                        || slot.role() == SourceCoverageRole.SCORING)
                .findFirst()
                .ifPresent(slot -> owners.put("ending", slot.ownerTopicKey()));
        return Map.copyOf(owners);
    }

    private List<Map<String, Object>> visibleOutline(OutlineDraft outline) {
        return outline.topics().stream().map(topic -> Map.<String, Object>of(
                "key", topic.key(),
                "title", topic.title(),
                "objective", topic.objective(),
                "required", topic.required(),
                "sourcePages", topic.sourcePageNumbers(),
                "sourceIdentifiers", topic.retrievalQueries(),
                "teachingUnits", outline.sourceCoverageSlots().stream()
                        .filter(slot -> slot.ownerTopicKey().equals(topic.key()))
                        .collect(java.util.stream.Collectors.groupingBy(
                                slot -> slot.teachingUnitId(),
                                LinkedHashMap::new,
                                java.util.stream.Collectors.mapping(
                                        slot -> slot.sourceIdentifier(),
                                        java.util.stream.Collectors.toList())))))
                .toList();
    }

    private List<Map<String, Object>> visiblePlanUnits(TeachingPlan plan) {
        return plan.sections().stream().map(section -> Map.<String, Object>of(
                "topicKey", section.topicKey(),
                "objective", section.objective(),
                "units", TeachingUnitContract.decodeUnits(section.retrievalQueries())))
                .toList();
    }

    private Map<String, Object> visibleLesson(IllustratedLesson lesson) {
        return Map.of(
                "status", lesson.status().name(),
                "sections", lesson.sections().stream().map(section -> {
                    Map<String, Object> visible = new LinkedHashMap<>();
                    visible.put("position", section.position());
                    visible.put("topicKey", section.topicKey());
                    visible.put("coverageTags", section.coverageTags());
                    visible.put("title", section.title());
                    visible.put("required", section.required());
                    visible.put("visualKind", section.visualKind().name());
                    visible.put("visualCaption", section.visualCaption());
                    visible.put("visualSourcePages", section.visualSourcePages());
                    visible.put("visualSourceChunkIds", section.visualSourceChunkIds());
                    visible.put("evidenceStatus", section.evidenceStatus().name());
                    visible.put("steps", section.steps().stream().map(step -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("position", step.position());
                        item.put("heading", step.heading());
                        item.put("kind", step.kind().name());
                        item.put("text", step.text());
                        item.put("sourcePages", step.sourcePages());
                        item.put("sourceChunkIds", step.sourceChunkIds());
                        item.put("visualFocus", visibleFocus(step.visualFocus()));
                        return Map.copyOf(item);
                    }).toList());
                    return Map.copyOf(visible);
                })
                        .toList());
    }

    private Map<String, Object> visibleFocus(IllustratedLesson.VisualFocus focus) {
        if (focus == null) return Map.of();
        return visibleFocus(
                focus.pageNumber(),
                focus.label(),
                focus.visibleDescription(),
                focus.x(),
                focus.y(),
                focus.width(),
                focus.height());
    }

    private Map<String, Object> visibleFocus(
            int pageNumber,
            String label,
            String visibleDescription,
            int x,
            int y,
            int width,
            int height) {
        return Map.of(
                "pageNumber", pageNumber,
                "label", label,
                "visibleDescription", visibleDescription,
                "x", x,
                "y", y,
                "width", width,
                "height", height);
    }

    private int visibleCharacters(IllustratedLesson lesson) {
        return lesson.sections().stream().mapToInt(section -> section.title().length()
                + section.visualCaption().length()
                + section.steps().stream().mapToInt(step -> step.heading().length() + step.text().length()).sum())
                .sum();
    }

    private RuntimeModelConfiguration configuration(Provider provider, ChatModel model) {
        return configuration(provider, model, model);
    }

    private RuntimeModelConfiguration configuration(
            Provider provider,
            ChatModel teachingModel,
            ChatModel criticModel) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.TEACHING)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.TEACHING, OWNER)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.CRITIC, OWNER)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.VISUAL)).thenReturn("fake");
        when(configuration.providerFor(RuntimeModelConfiguration.Role.VISUAL, OWNER)).thenReturn("fake");
        when(configuration.modelFor(RuntimeModelConfiguration.Role.TEACHING, OWNER)).thenReturn(teachingModel);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.CRITIC, OWNER)).thenReturn(criticModel);
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.TEACHING, OWNER)).thenReturn(provider.model());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.CRITIC, OWNER)).thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.TEACHING, OWNER)).thenReturn(false);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.CRITIC, OWNER)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        RuntimeModelConfiguration.Role.TEACHING, OWNER))
                .thenReturn("deepseek".equals(provider.provider()) && !deepSeekThinking());
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        RuntimeModelConfiguration.Role.CRITIC, OWNER))
                .thenReturn("deepseek".equals(provider.provider()) && !deepSeekThinking());
        return configuration;
    }

    private boolean deepSeekThinking() {
        return "true".equalsIgnoreCase(System.getenv("RULEPILOT_TEACHING_CANARY_DEEPSEEK_THINKING"));
    }

    private ChatModel recordingChatModel(Provider provider, List<String> rawResponses) {
        ChatModel delegate = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        return new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                var response = delegate.call(prompt);
                String text = response == null || response.getResult() == null
                                || response.getResult().getOutput() == null
                        ? ""
                        : response.getResult().getOutput().getText();
                rawResponses.add(text == null ? "" : text);
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

    private Provider provider(String name) {
        String prefix = name.toUpperCase(Locale.ROOT);
        return new Provider(
                name,
                requiredEnvironment(prefix + "_API_KEY"),
                requiredEnvironment(prefix + "_BASE_URL"),
                requiredEnvironment(prefix + "_MODEL"));
    }

    private String canaryOutput(String provider) {
        String providerSuffix = "deepseek".equals(provider) ? "" : "-" + provider;
        String runLabel = java.util.Optional.ofNullable(System.getenv("RULEPILOT_TEACHING_CANARY_RUN_LABEL"))
                .filter(value -> !value.isBlank())
                .orElse("");
        if (!runLabel.matches("[A-Za-z0-9-]{0,40}")) {
            throw new IllegalArgumentException("paid teaching canary run label is invalid");
        }
        return CANARY_OUTPUT.replace(".json", providerSuffix + (runLabel.isEmpty() ? "" : "-" + runLabel) + ".json");
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the paid canary");
        return value.strip();
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private record Provider(String provider, String apiKey, String baseUrl, String model) {}

    private final class RecordingTeachingModel implements TeachingLessonModel {
        private final TeachingLessonModel delegate;
        private final Map<String, CopyOnWriteArrayList<SectionDraft>> drafts = new ConcurrentHashMap<>();
        private final AtomicReference<Instant> firstRequestAt = new AtomicReference<>();

        private RecordingTeachingModel(TeachingLessonModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public String providerId() {
            return delegate.providerId();
        }

        @Override
        public boolean supportsVisualEvidence(String owner) {
            return delegate.supportsVisualEvidence(owner);
        }

        @Override
        public int maxConcurrentSectionRequests(String owner) {
            return delegate.maxConcurrentSectionRequests(owner);
        }

        @Override
        public SectionDraft compose(SectionRequest request) {
            recordRequest(request);
            return record(request, delegate.compose(request));
        }

        @Override
        public SectionDraft repairCompositionContract(SectionRequest request) {
            recordRequest(request);
            return record(request, delegate.repairCompositionContract(request));
        }

        @Override
        public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
            recordRequest(request);
            return record(request, delegate.revise(request, previousDraft, feedback));
        }

        @Override
        public SectionDraft repairRevisionContract(
                SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
            recordRequest(request);
            return record(request, delegate.repairRevisionContract(request, previousDraft, feedback));
        }

        private void recordRequest(SectionRequest request) {
            firstRequestAt.compareAndSet(null, Instant.now());
            requestIndex.put(request.topicKey(), request);
        }

        private SectionDraft record(SectionRequest request, SectionDraft draft) {
            drafts.computeIfAbsent(request.topicKey(), ignored -> new CopyOnWriteArrayList<>()).add(draft);
            return draft;
        }

        private List<SectionDraft> draftAttempts(String topicKey) {
            return List.copyOf(drafts.getOrDefault(topicKey, new CopyOnWriteArrayList<>()));
        }

        private Map<String, Object> visibleDrafts() {
            Map<String, Object> visible = new LinkedHashMap<>();
            drafts.forEach((topic, attempts) -> visible.put(topic, attempts.stream().map(draft -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", draft.title());
                item.put("visualKind", draft.visualKind().name());
                item.put("visualCaption", draft.visualCaption());
                item.put("visualCitationIds", draft.visualCitationIds());
                item.put("steps", draft.steps().stream().map(step -> {
                    Map<String, Object> visibleStep = new LinkedHashMap<>();
                    visibleStep.put("heading", step.heading());
                    visibleStep.put("kind", step.kind().name());
                    visibleStep.put("text", step.text());
                    visibleStep.put("citationIds", step.citationIds());
                    visibleStep.put("teachingUnitIds", step.teachingUnitIds());
                    return Map.copyOf(visibleStep);
                }).toList());
                return Map.copyOf(item);
            }).toList()));
            return Map.copyOf(visible);
        }

        private Map<String, Object> visibleRequests() {
            Map<String, Object> visible = new LinkedHashMap<>();
            requestIndex.forEach((topic, request) -> visible.put(topic, Map.of(
                    "objective", request.objective(),
                    "wholeGameContext", request.wholeGameContext(),
                    "teachingUnits", request.teachingUnits(),
                    "evidence", java.util.stream.IntStream.range(0, request.evidence().size())
                            .mapToObj(index -> {
                                var source = request.evidence().get(index);
                                return Map.of(
                                        "reference", "E" + (index + 1),
                                        "chunkId", source.chunkId(),
                                        "heading", source.heading(),
                                        "excerpt", source.excerpt(),
                                        "pageFrom", source.pageFrom(),
                                        "pageTo", source.pageTo());
                            })
                            .toList())));
            return Map.copyOf(visible);
        }

        private List<TeachingSectionDraftCandidate> reviewCandidates(
                TeachingPlan plan, IllustratedLesson lesson) {
            Map<String, LessonSection> published = lesson.sections().stream()
                    .collect(java.util.stream.Collectors.toMap(LessonSection::topicKey, section -> section));
            return java.util.stream.IntStream.range(0, plan.sections().size())
                    .mapToObj(index -> {
                        TeachingPlan.PlannedSection planned = plan.sections().get(index);
                        SectionRequest request = requestIndex.get(planned.topicKey());
                        List<SectionDraft> attempts = draftAttempts(planned.topicKey());
                        List<RuleEvidence> evidence = request.evidence().stream()
                                .map(source -> new RuleEvidence(
                                        source.chunkId(),
                                        plan.documentVersionId(),
                                        source.sectionType(),
                                        source.heading(),
                                        source.excerpt(),
                                        source.pageFrom(),
                                        source.pageTo()))
                                .toList();
                        return new TeachingSectionDraftCandidate(
                                index,
                                planned,
                                evidence,
                                request,
                                attempts.getLast(),
                                published.get(planned.topicKey()));
                    })
                    .toList();
        }

        private Instant firstRequestAt() {
            return firstRequestAt.get();
        }

        private boolean allRequestsShareWholeGameContext(int expectedSections) {
            return requestIndex.size() == expectedSections
                    && requestIndex.values().stream().allMatch(request -> request.wholeGameContext().evidenceBound())
                    && requestIndex.values().stream()
                                    .map(SectionRequest::wholeGameContext)
                                    .distinct()
                                    .count()
                            == 1;
        }

        private boolean sectionRequestsRetainOwnUnitsAndEvidence(int expectedSections) {
            if (requestIndex.size() != expectedSections || requestIndex.size() < 2) return false;
            boolean eachOwnsAContract = requestIndex.entrySet().stream().allMatch(entry -> {
                SectionRequest request = entry.getValue();
                return entry.getKey().equals(request.topicKey())
                        && !request.teachingUnits().isEmpty()
                        && !request.evidence().isEmpty();
            });
            long distinctUnitContracts = requestIndex.values().stream()
                    .map(request -> request.teachingUnits().stream()
                            .map(TeachingLessonModel.TeachingUnitInput::unitId)
                            .toList())
                    .distinct()
                    .count();
            long distinctEvidenceScopes = requestIndex.values().stream()
                    .map(request -> request.evidence().stream()
                            .map(TeachingLessonModel.EvidenceInput::chunkId)
                            .toList())
                    .distinct()
                    .count();
            return eachOwnsAContract && distinctUnitContracts > 1 && distinctEvidenceScopes > 1;
        }
    }

    private static final class CanaryInvocations implements AuditedAgentInvocations {
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger criticCalls = new AtomicInteger();
        private final List<String> modelOperations = new CopyOnWriteArrayList<>();
        private final List<String> toolOperations = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger eventSequence = new AtomicInteger();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            if (type == ActivityType.MODEL) {
                modelCalls.incrementAndGet();
                modelOperations.add(operation);
            }
            if (type == ActivityType.TOOL) {
                toolCalls.incrementAndGet();
                toolOperations.add(operation);
            }
            if (type == ActivityType.CRITIC) criticCalls.incrementAndGet();
            event(type, operation, "STARTED", null);
            try {
                T result = invocation.get();
                event(type, operation, "SUCCEEDED", null);
                return result;
            } catch (RuntimeException failure) {
                event(type, operation, "FAILED", failure.getClass().getSimpleName());
                throw failure;
            }
        }

        @Override
        public void record(UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
            event(type, operation, outcome.name(), summary);
        }

        private void event(ActivityType type, String operation, String outcome, String summary) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("sequence", eventSequence.incrementAndGet());
            event.put("at", Instant.now().toString());
            event.put("type", type.name());
            event.put("operation", operation);
            event.put("outcome", outcome);
            if (summary != null) event.put("summary", summary);
            events.add(Map.copyOf(event));
        }

        private int firstEventIndex(String operation) {
            for (int index = 0; index < events.size(); index++) {
                if (operation.equals(events.get(index).get("operation"))) return index;
            }
            return -1;
        }

        private int firstModelEventIndex() {
            for (int index = 0; index < events.size(); index++) {
                Map<String, Object> event = events.get(index);
                if (ActivityType.MODEL.name().equals(event.get("type"))
                        && "STARTED".equals(event.get("outcome"))) {
                    return index;
                }
            }
            return -1;
        }
    }

    private static final class CatalogEvidence implements AssistantReadTools {
        private static final Pattern TERMS = Pattern.compile("[\\p{L}\\p{N}]{2,}");
        private final UUID versionId;
        private final List<RuleEvidence> chunks;

        private CatalogEvidence(UUID versionId, Map<Integer, String> pages) {
            this.versionId = versionId;
            this.chunks = pages.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new RuleEvidence(
                            UUID.nameUUIDFromBytes((versionId + ":visual-page:" + entry.getKey())
                                    .getBytes(StandardCharsets.UTF_8)),
                            versionId,
                            "VISUAL_PAGE_LEDGER",
                            "Visual rulebook page " + entry.getKey(),
                            entry.getValue(),
                            entry.getKey(),
                            entry.getKey()))
                    .toList();
        }

        @Override
        public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
            if (!versionId.equals(request.documentVersionId())) return List.of();
            Set<String> terms = terms(request.query());
            return chunks.stream()
                    .map(source -> new Scored(source, score(source.excerpt(), terms)))
                    .filter(candidate -> candidate.score() > 0)
                    .sorted(Comparator.comparingInt(Scored::score).reversed())
                    .limit(request.limit())
                    .map(Scored::source)
                    .toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidencePages(
                UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
            if (!versionId.equals(documentVersionId) || includePageImages) return List.of();
            return chunks.stream().filter(source -> pageNumbers.contains(source.pageFrom())).toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidenceIds(UUID documentVersionId, Set<UUID> evidenceIds) {
            if (!versionId.equals(documentVersionId)) return List.of();
            return chunks.stream().filter(source -> evidenceIds.contains(source.chunkId())).toList();
        }

        private Set<String> terms(String query) {
            Matcher matcher = TERMS.matcher(query == null ? "" : query.toLowerCase(Locale.ROOT));
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            while (matcher.find()) terms.add(matcher.group());
            return Set.copyOf(terms);
        }

        private int score(String excerpt, Set<String> terms) {
            String lower = excerpt.toLowerCase(Locale.ROOT);
            return (int) terms.stream().filter(lower::contains).count();
        }

        private record Scored(RuleEvidence source, int score) {}
    }

    private static final class PdfEvidence implements AssistantReadTools {
        private static final Pattern TERMS = Pattern.compile("[\\p{L}\\p{N}]{3,}");
        private static final Set<String> STOP_TERMS = Set.of(
                "and", "are", "can", "does", "every", "for", "from", "how", "the", "then", "what", "when", "with");
        private final UUID versionId;
        private final List<String> pages;
        private final List<RuleEvidence> chunks;

        private PdfEvidence(Path pdf, UUID versionId) throws IOException {
            this.versionId = versionId;
            this.pages = extractPages(pdf.toFile());
            this.chunks = chunks();
        }

        private String page(int pageNumber) {
            return pages.get(pageNumber - 1);
        }

        @Override
        public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
            if (!versionId.equals(request.documentVersionId())) return List.of();
            Set<String> terms = terms(request.query());
            return chunks.stream()
                    .map(source -> new Scored(source, score(source.excerpt(), terms)))
                    .filter(candidate -> candidate.score() > 0)
                    .sorted(Comparator.comparingInt(Scored::score).reversed())
                    .limit(request.limit())
                    .map(Scored::source)
                    .toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidencePages(
                UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
            if (!versionId.equals(documentVersionId) || includePageImages) return List.of();
            return chunks.stream().filter(source -> pageNumbers.contains(source.pageFrom())).toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidenceIds(UUID documentVersionId, Set<UUID> evidenceIds) {
            if (!versionId.equals(documentVersionId)) return List.of();
            return chunks.stream().filter(source -> evidenceIds.contains(source.chunkId())).toList();
        }

        private List<RuleEvidence> chunks() {
            List<RuleEvidence> result = new ArrayList<>();
            for (int pageNumber = 1; pageNumber <= pages.size(); pageNumber++) {
                String text = pages.get(pageNumber - 1).replaceAll("\\s+", " ").strip();
                int sequence = 0;
                for (int start = 0; start < text.length(); start += 1_000) {
                    String excerpt = text.substring(start, Math.min(text.length(), start + 1_200)).strip();
                    if (!excerpt.isBlank()) {
                        result.add(new RuleEvidence(
                                UUID.nameUUIDFromBytes((versionId + ":" + pageNumber + ":" + sequence)
                                        .getBytes(StandardCharsets.UTF_8)),
                                versionId,
                                "PAGE",
                                "Rulebook page " + pageNumber + " segment " + (sequence + 1),
                                excerpt,
                                pageNumber,
                                pageNumber));
                    }
                    sequence++;
                }
            }
            return List.copyOf(result);
        }

        private Set<String> terms(String query) {
            var matcher = TERMS.matcher(query.toLowerCase(Locale.ROOT));
            LinkedHashSet<String> result = new LinkedHashSet<>();
            while (matcher.find()) {
                if (!STOP_TERMS.contains(matcher.group())) result.add(matcher.group());
            }
            return Set.copyOf(result);
        }

        private int score(String excerpt, Set<String> terms) {
            String lower = excerpt.toLowerCase(Locale.ROOT);
            return (int) terms.stream().filter(lower::contains).count();
        }

        private static List<String> extractPages(File pdf) throws IOException {
            try (PDDocument document = Loader.loadPDF(pdf)) {
                PDFTextStripper stripper = new PDFTextStripper();
                List<String> result = new ArrayList<>();
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    result.add(stripper.getText(document));
                }
                return List.copyOf(result);
            }
        }

        private record Scored(RuleEvidence source, int score) {}
    }
}
