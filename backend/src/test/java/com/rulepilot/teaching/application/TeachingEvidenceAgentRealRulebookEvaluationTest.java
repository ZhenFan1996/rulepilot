package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.adapter.out.model.SpringAiNativeToolModel;
import com.rulepilot.assistant.application.BoundedNativeToolAgent;
import com.rulepilot.assistant.application.ExpandRuleEvidenceContextNativeTool;
import com.rulepilot.assistant.application.NativeAgentToolRegistry;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.assistant.application.ReadRulePagesNativeTool;
import com.rulepilot.assistant.application.SearchRuleEvidenceNativeTool;
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
        JsonNode evaluation = mapper.readTree(root.resolve(
                ".local/agent-evaluation/teaching-agent-cases.json").toFile());
        List<CaseConfiguration> cases = new ArrayList<>();
        for (JsonNode caseNode : evaluation.path("cases")) {
            cases.add(caseFor(
                    root,
                    manifest,
                    inventory,
                    caseNode,
                    provider("TEXT_LAYER".equals(caseNode.path("family").asText()) ? "deepseek" : "qwen")));
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
                    .containsEntry("directAdditionalModelCalls", 0)
                    .containsEntry("withinLatencyBudget", true);
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
        JsonNode evaluation = mapper.readTree(root.resolve(
                ".local/agent-evaluation/teaching-agent-cases.json").toFile());
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/teaching-tool-value-generated-sections.json");
        String selectedCase = System.getenv("RULEPILOT_TEACHING_VALUE_CASE");

        for (JsonNode caseNode : evaluation.path("cases")) {
            if (results.size() == 2) break;
            if (selectedCase != null && !selectedCase.isBlank()
                    && !selectedCase.equals(caseNode.path("caseId").asText())) continue;
            ProviderConfiguration provider = provider("TEXT_LAYER".equals(caseNode.path("family").asText())
                    ? "deepseek"
                    : "qwen");
            CaseConfiguration configured = caseFor(root, manifest, inventory, caseNode, provider);
            results.add(compareTeachingSection(configured));
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "schemaVersion", 1,
                    "generatedAt", Instant.now().toString(),
                    "results", List.copyOf(results))) + "\n", StandardCharsets.UTF_8);
        }

        assertThat(results).hasSize(selectedCase == null || selectedCase.isBlank() ? 2 : 1)
                .allSatisfy(result -> assertThat(result)
                .containsEntry("toolSectionCoversRequiredDetails", true)
                .containsEntry("toolSectionUsesExpectedPage", true)
                .containsEntry("boundedToolCalls", true)
                .containsEntry("completeOutcomesRecordedForInspection", true));
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
        TeachingEvidenceAgent evidenceAgent = agent(case_.provider(), corpus, toolAudit, versionId, runId);
        long toolStarted = System.nanoTime();
        var refined = evidenceAgent.refine(plan, planned, runId, deterministic);
        int evidenceToolModelCalls = toolAudit.modelCalls;
        int evidenceToolCalls = toolAudit.toolCalls;
        DirectAuditedInvocations compositionAudit = new DirectAuditedInvocations();
        List<String> toolRawResponses = new ArrayList<>();
        RecordingTeachingLessonModel toolModel = new RecordingTeachingLessonModel(
                teachingModel(case_.provider(), toolRawResponses));
        TeachingSectionDraftCandidate withTools = null;
        String toolFailure = null;
        try {
            withTools = composeSection(
                    toolModel, compositionAudit, plan, planned, refined.evidence(), runId);
        } catch (IllegalArgumentException rejected) {
            toolFailure = rejected.getMessage() == null ? "SECTION_REJECTED" : rejected.getMessage();
        }
        long toolLatencyMs = Duration.ofNanos(System.nanoTime() - toolStarted).toMillis();

        String baselineText = baseline == null ? "" : visibleSectionText(baseline.section());
        String toolText = withTools == null ? "" : visibleSectionText(withTools.section());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", case_.caseId());
        result.put("provider", case_.provider().provider());
        result.put("objective", planned.objective());
        result.put("withoutTools", baseline == null
                ? Map.of("status", "REJECTED", "reason", baselineFailure)
                : visibleSection(baseline.section()));
        result.put("withoutToolsDraftAttempts", baselineModel.drafts().stream().map(this::visibleDraft).toList());
        result.put("withoutToolsRawResponses", List.copyOf(baselineRawResponses));
        result.put("withoutToolsCoversRequiredDetails", covers(case_, baselineText));
        result.put("withoutToolsUsesExpectedPage", baseline != null && usesExpectedPage(case_, baseline.section()));
        result.put("withoutToolsModelCalls", baselineAudit.modelCalls);
        result.put("withoutToolsLatencyMs", baselineLatencyMs);
        result.put("withTools", withTools == null
                ? Map.of("status", "REJECTED", "reason", toolFailure)
                : visibleSection(withTools.section()));
        result.put("withToolsDraftAttempts", toolModel.drafts().stream().map(this::visibleDraft).toList());
        result.put("withToolsRawResponses", List.copyOf(toolRawResponses));
        result.put("toolSectionCoversRequiredDetails", covers(case_, toolText));
        result.put("toolSectionUsesExpectedPage", withTools != null && usesExpectedPage(case_, withTools.section()));
        result.put("evidenceAdded", refined.evidence().size() - deterministic.evidence().size());
        result.put("initialEvidence", visibleEvidence(deterministic.evidence()));
        result.put("toolRefinedEvidence", visibleEvidence(refined.evidence()));
        result.put("toolCalls", evidenceToolCalls);
        result.put("toolLoopModelCalls", evidenceToolModelCalls);
        result.put("toolSectionModelCalls", compositionAudit.modelCalls);
        result.put("withToolsLatencyMs", toolLatencyMs);
        result.put("boundedToolCalls", evidenceToolCalls == 1);
        result.put("completeOutcomesRecordedForInspection", (!toolText.isBlank() || !toolModel.drafts().isEmpty())
                && (baseline != null || baselineFailure != null && !baselineFailure.isBlank()));
        return Map.copyOf(result);
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
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), audited, VisualRulebookPageFacts.empty());
        return composer.compose(
                plan,
                planned,
                List.of(),
                evidence,
                runId,
                planned.position() - 1,
                false);
    }

    private Map<String, Object> visibleSection(com.rulepilot.teaching.domain.IllustratedLesson.LessonSection section) {
        return Map.of(
                "title", section.title(),
                "visualCaption", section.visualCaption(),
                "steps", section.steps().stream().map(step -> Map.of(
                        "heading", step.heading(),
                        "kind", step.kind().name(),
                        "text", step.text(),
                        "citationPages", step.sourcePages())).toList());
    }

    private Map<String, Object> visibleDraft(SectionDraft draft) {
        return Map.of(
                "title", draft.title() == null ? "" : draft.title(),
                "visualCaption", draft.visualCaption() == null ? "" : draft.visualCaption(),
                "visualCitationCount", draft.visualCitationIds().size(),
                "steps", draft.steps().stream().map(step -> step == null
                        ? Map.of("invalid", true)
                        : Map.of(
                                "heading", step.heading() == null ? "" : step.heading(),
                                "kind", step.kind() == null ? "" : step.kind().name(),
                                "text", step.text() == null ? "" : step.text(),
                                "citationCount", step.citationIds().size())).toList());
    }

    private String visibleSectionText(com.rulepilot.teaching.domain.IllustratedLesson.LessonSection section) {
        return (section.title() + " " + section.visualCaption() + " " + section.steps().stream()
                        .map(step -> step.heading() + " " + step.text())
                        .reduce("", (left, right) -> left + " " + right))
                .replaceAll("\\s+", " ")
                .strip();
    }

    private boolean covers(CaseConfiguration case_, String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[-‐‑‒–—]", "");
        return case_.expectedOutputTermGroups().stream().allMatch(group -> group.stream()
                .map(term -> term.toLowerCase(Locale.ROOT).replaceAll("[-‐‑‒–—]", ""))
                .anyMatch(normalized::contains));
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
        TeachingEvidenceAgent agent = agent(case_.provider(), corpus, audited, versionId, runId);
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
        int modelCallsBeforeDirect = audited.modelCalls;
        TeachingPlan directPlan = plan(versionId, case_.caseNode(), List.of(case_.initialPage()));
        var directResult = agent.refine(directPlan, directPlan.sections().getFirst(), runId, deterministic);

        return Map.ofEntries(
                Map.entry("caseId", case_.caseId()),
                Map.entry("provider", case_.provider().provider()),
                Map.entry("toolCalls", audited.toolCalls),
                Map.entry("toolObservationCodes", audited.observations.stream()
                        .map(ToolObservation::code)
                        .toList()),
                Map.entry("toolEvidenceCounts", audited.observations.stream()
                        .map(ToolObservation::evidenceCount)
                        .toList()),
                Map.entry("searchDiagnostics", corpus.searchDiagnostics(case_.expectedTerms())),
                Map.entry("modelCalls", modelCallsBeforeDirect),
                Map.entry("expectedCoverageAdded", expected != null),
                Map.entry("citationAccepted", citationAccepted),
                Map.entry("refinedEvidenceCount", refined.evidence().size()),
                Map.entry("refinedPages", refined.evidence().stream()
                        .map(RuleEvidence::pageFrom)
                        .distinct()
                        .toList()),
                Map.entry("directAdditionalModelCalls", audited.modelCalls - modelCallsBeforeDirect),
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
        TeachingEvidenceAgent agent = agent(case_.provider(), corpus, audited, versionId, runId);
        TeachingPlan plan = plan(versionId, node, List.of());
        var empty = new TeachingSectionEvidenceRetriever.Result(
                List.of(), 1, TeachingSectionEvidenceRetriever.State.EMPTY);

        var result = agent.refine(plan, plan.sections().getFirst(), runId, empty);

        return Map.of(
                "caseId", node.path("caseId").asText(),
                "state", result.state().name(),
                "evidenceCount", result.evidence().size(),
                "toolCalls", audited.toolCalls,
                "crossScopeEvidence", result.evidence().stream()
                        .anyMatch(source -> !versionId.equals(source.documentVersionId())));
    }

    private TeachingEvidenceAgent agent(
            ProviderConfiguration provider,
            PdfTeachingEvidence corpus,
            DirectAuditedInvocations audited,
            UUID versionId,
            UUID runId) {
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        NativeAgentToolRegistry registry = new NativeAgentToolRegistry(
                List.of(
                        new SearchRuleEvidenceNativeTool(corpus, mapper),
                        new ExpandRuleEvidenceContextNativeTool(corpus, mapper),
                        new ReadRulePagesNativeTool(corpus, mapper)),
                mapper,
                candidate -> candidate.ownerUsername().equals(scope.ownerUsername())
                        && candidate.documentVersionId().equals(scope.documentVersionId()));
        BoundedNativeToolAgent loop = new BoundedNativeToolAgent(
                springModel(provider), registry, mock(AgentExecutionControl.class), audited, mapper);
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        when(scopes.create(scope.ownerUsername(), scope.documentVersionId(), scope.runId()))
                .thenReturn(java.util.Optional.of(scope));
        return new TeachingEvidenceAgent(loop, scopes, corpus, new PolicyEvidenceVerifier());
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

    private NativeToolModel springModel(ProviderConfiguration provider) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(45))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(any(RuntimeModelConfiguration.Role.class), any(String.class))).thenReturn(chatModel);
        when(configuration.providerFor(any(RuntimeModelConfiguration.Role.class), any(String.class)))
                .thenReturn(provider.provider());
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        any(RuntimeModelConfiguration.Role.class), any(String.class)))
                .thenReturn("deepseek".equals(provider.provider()));
        return new SpringAiNativeToolModel(configuration);
    }

    private SpringAiTeachingLessonModel teachingModel(
            ProviderConfiguration provider, List<String> rawResponses) {
        ChatModel delegate = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        ChatModel chatModel = new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                var response = delegate.call(prompt);
                String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
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
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
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
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(VersionedAgentPrompts.class);
            context.refresh();
            return new SpringAiTeachingLessonModel(
                    configuration,
                    new FakeTeachingLessonModel(),
                    context.getBean(VersionedAgentPrompts.class));
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
        List<List<String>> expectedOutputTermGroups = new ArrayList<>();
        node.path("expectedOutputTermGroups").forEach(group -> {
            List<String> alternatives = new ArrayList<>();
            group.forEach(term -> alternatives.add(term.asText()));
            expectedOutputTermGroups.add(List.copyOf(alternatives));
        });
        if (expectedOutputTermGroups.isEmpty()) {
            expectedTerms.forEach(term -> expectedOutputTermGroups.add(List.of(term)));
        }
        return new CaseConfiguration(
                caseId,
                pdf,
                node,
                node.path("initialPage").asInt(),
                List.copyOf(pages),
                node.path("expectedPage").asInt(),
                List.copyOf(expectedTerms),
                List.copyOf(expectedOutputTermGroups),
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
            List<List<String>> expectedOutputTermGroups,
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
        private int modelCalls;
        private int toolCalls;
        private final List<ToolObservation> observations = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            if (type == ActivityType.MODEL) modelCalls++;
            if (type == ActivityType.TOOL) toolCalls++;
            T result = invocation.get();
            if (result instanceof NativeAgentToolRegistry.ToolExecution execution) {
                observations.add(execution.observation());
            }
            return result;
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
