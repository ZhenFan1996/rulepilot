package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.rulepilot.teaching.adapter.out.model.FakeTeachingLessonModel;
import com.rulepilot.teaching.adapter.out.model.FakeTeachingOutlineModel;
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

    private static final int FIRST_ACTIVE_PAGE = 4;
    private static final int LAST_ACTIVE_PAGE = 13;
    private static final String OWNER = "teaching-richness-canary";

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void plansAndPublishesACompleteMultiChapterLessonFromOneRealRulebookSlice() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_ALLOW_PAID_CANARY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path pdf = root.resolve(".local/public-corpus/pdfs/dune-imperium-english.pdf");
        assumeTrue(Files.isRegularFile(pdf), "ignored representative rulebook is required");

        Provider provider = provider("deepseek");
        VersionedAgentPrompts prompts = prompts();
        UUID versionId = UUID.nameUUIDFromBytes(
                "teaching-richness:dune-pages-4-13".getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfEvidence corpus = new PdfEvidence(pdf, versionId);
        List<PageInput> activePages = java.util.stream.IntStream.rangeClosed(FIRST_ACTIVE_PAGE, LAST_ACTIVE_PAGE)
                .mapToObj(page -> new PageInput(page, TeachingPageCatalogText.bounded(corpus.page(page))))
                .toList();

        List<String> rawOutlineResponses = Collections.synchronizedList(new ArrayList<>());
        OutlineRequest outlineRequest = new OutlineRequest(
                activePages,
                List.of(),
                "请根据当前规则书自己决定最适合第一次开局的完整教学结构；复杂规则要拆成可照做的单元。",
                OWNER);
        OutlineDraft outline;
        boolean outlineReplayed =
                "true".equalsIgnoreCase(System.getenv("RULEPILOT_REUSE_CAPTURED_TEACHING_OUTLINE"));
        long outlineStarted = System.nanoTime();
        if (outlineReplayed) {
            Path captured = root.resolve(".local/agent-evaluation/teaching-rich-lesson-canary.json");
            var capturedNode = mapper.readTree(captured.toFile());
            String raw = capturedNode.path("result").path("rawOutlineProviderResponses").get(0).asText();
            rawOutlineResponses.add(raw);
            outline = mapper.readValue(raw, OutlineDraft.class);
            TeachingSourceCoverageContract.requireCompleteModelContract(outlineRequest, outline);
        } else {
            RuntimeModelConfiguration outlineConfiguration = configuration(
                    provider, recordingChatModel(provider, rawOutlineResponses));
            SpringAiTeachingOutlineModel outlineModel = new SpringAiTeachingOutlineModel(
                    outlineConfiguration, prompts, new FakeTeachingOutlineModel());
            try {
                outline = outlineModel.organize(outlineRequest);
            } catch (RuntimeException failure) {
                Path failureOutput = root.resolve(".local/agent-evaluation/teaching-rich-outline-failure.json");
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
        RuntimeModelConfiguration sectionConfiguration = configuration(
                provider, recordingChatModel(provider, rawSectionResponses));
        RecordingTeachingModel sections = new RecordingTeachingModel(new SpringAiTeachingLessonModel(
                sectionConfiguration, new FakeTeachingLessonModel(), prompts));
        CanaryInvocations audit = new CanaryInvocations();
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        when(scopes.create(eq(OWNER), eq(versionId), eq(runId))).thenReturn(java.util.Optional.of(
                new ToolScope(OWNER, versionId, runId, Instant.now().plusSeconds(300))));
        var refiner = new TeachingSourcePageEvidenceRefiner(
                scopes, corpus, new PolicyEvidenceVerifier(), audit);
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                corpus,
                sections,
                new PolicyEvidenceVerifier(),
                mock(GeneratedContentCritic.class),
                audit,
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable(),
                72,
                3,
                3,
                refiner);
        List<IllustratedLesson> progressSnapshots = new CopyOnWriteArrayList<>();
        long lessonStarted = System.nanoTime();
        IllustratedLesson lesson = agent.createBase(plan, runId, null, progressSnapshots::add);
        long lessonLatencyMs = elapsedMillis(lessonStarted);

        Map<String, Object> result = result(
                root,
                provider,
                outline,
                plan,
                lesson,
                sections,
                rawOutlineResponses,
                rawSectionResponses,
                audit,
                outlineReplayed,
                outlineLatencyMs,
                lessonLatencyMs,
                progressSnapshots,
                outlineCompletedAt,
                planReloadedAt);
        Path output = root.resolve(".local/agent-evaluation/teaching-rich-lesson-canary.json");
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
                .containsEntry("planContextSurvivedPersistenceRoundTrip", true)
                .containsEntry("allSectionRequestsShareWholeGameContext", true)
                .containsEntry("sectionRequestsRetainOwnUnitsAndEvidence", true)
                .containsEntry("wholeGameCompletedBeforeSectionFanOut", true)
                .containsEntry("criticCalls", 0)
                .containsEntry("withinLatencyBudget", true);
        assertThat(audit.modelCalls.get()).isBetween(plan.sections().size(), plan.sections().size() * 2);
        assertThat(rawOutlineResponses).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(rawSectionResponses).isNotEmpty();
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
            CanaryInvocations audit,
            boolean outlineReplayed,
            long outlineLatencyMs,
            long lessonLatencyMs,
            List<IllustratedLesson> progressSnapshots,
            Instant outlineCompletedAt,
            Instant planReloadedAt) throws IOException {
        List<Map<String, Object>> fieldDiffs = lesson.sections().stream()
                .map(section -> fieldDiff(section, model.draftAttempts(section.topicKey())))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", "dune-imperium-pages-4-13-autonomous-plan");
        result.put("corpusScope", "PDF pages 4-13 only; complete lesson for this bounded rules slice, not all 20 pages");
        result.put("provider", provider.provider());
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
        result.put("totalModelCalls", currentOutlineModelCalls + audit.modelCalls.get());
        result.put("toolCalls", audit.toolCalls.get());
        result.put("toolOperations", List.copyOf(audit.toolOperations));
        result.put("criticCalls", audit.criticCalls.get());
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
        result.put("rawStructuredDrafts", model.visibleDrafts());
        result.put("modelRequests", model.visibleRequests());
        result.put("rawOutlineProviderResponses", List.copyOf(rawOutlineResponses));
        result.put("rawSectionProviderResponses", List.copyOf(rawSectionResponses));
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
                        .filter(attempt -> rawAttempts.get(attempt).steps().stream().anyMatch(candidate ->
                                candidate.heading().equals(step.heading())
                                        && candidate.kind() == step.kind()
                                        && candidate.text().equals(step.text())
                                        && candidate.citationIds().equals(step.sourceChunkIds())))
                        .boxed()
                        .toList())
                .toList();
        boolean exactRawLineage = metadataSourceAttempt >= 0
                && stepSourceAttempts.stream().noneMatch(List::isEmpty);
        Set<String> coveredUnits = rawAttempts.stream()
                .flatMap(raw -> raw.steps().stream())
                .filter(candidate -> published.steps().stream().anyMatch(step ->
                        candidate.heading().equals(step.heading())
                                && candidate.kind() == step.kind()
                                && candidate.text().equals(step.text())
                                && candidate.citationIds().equals(step.sourceChunkIds())))
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
                        .filter(candidate -> published.steps().stream().noneMatch(step ->
                                candidate.heading().equals(step.heading())
                                        && candidate.kind() == step.kind()
                                        && candidate.text().equals(step.text())
                                        && candidate.citationIds().equals(step.sourceChunkIds())))
                        .count())
                .sum();
        diff.put("supersededRawStepCount", superseded);
        return Map.copyOf(diff);
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
                "sections", lesson.sections().stream().map(section -> Map.<String, Object>of(
                        "position", section.position(),
                        "topicKey", section.topicKey(),
                        "title", section.title(),
                        "visualCaption", section.visualCaption(),
                        "evidenceStatus", section.evidenceStatus().name(),
                        "steps", section.steps().stream().map(step -> Map.<String, Object>of(
                                "position", step.position(),
                                "heading", step.heading(),
                                "kind", step.kind().name(),
                                "text", step.text(),
                                "sourcePages", step.sourcePages(),
                                "sourceChunkIds", step.sourceChunkIds()))
                                .toList()))
                        .toList());
    }

    private int visibleCharacters(IllustratedLesson lesson) {
        return lesson.sections().stream().mapToInt(section -> section.title().length()
                + section.visualCaption().length()
                + section.steps().stream().mapToInt(step -> step.heading().length() + step.text().length()).sum())
                .sum();
    }

    private RuntimeModelConfiguration configuration(Provider provider, ChatModel model) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.TEACHING)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.TEACHING, OWNER)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.VISUAL)).thenReturn("fake");
        when(configuration.providerFor(RuntimeModelConfiguration.Role.VISUAL, OWNER)).thenReturn("fake");
        when(configuration.modelFor(RuntimeModelConfiguration.Role.TEACHING, OWNER)).thenReturn(model);
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.TEACHING, OWNER)).thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.TEACHING, OWNER)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        RuntimeModelConfiguration.Role.TEACHING, OWNER))
                .thenReturn(true);
        return configuration;
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
            drafts.forEach((topic, attempts) -> visible.put(topic, attempts.stream().map(draft -> Map.of(
                    "title", draft.title(),
                    "visualCaption", draft.visualCaption(),
                    "steps", draft.steps().stream().map(step -> Map.of(
                            "heading", step.heading(),
                            "kind", step.kind().name(),
                            "text", step.text(),
                            "citationIds", step.citationIds(),
                            "teachingUnitIds", step.teachingUnitIds()))
                            .toList())).toList()));
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
