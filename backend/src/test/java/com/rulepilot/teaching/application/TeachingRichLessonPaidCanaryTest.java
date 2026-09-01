package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.adapter.out.model.SpringAiTeachingLessonModel;
import com.rulepilot.teaching.adapter.out.model.SpringAiTeachingOutlineModel;
import com.rulepilot.teaching.adapter.out.persistence.TeachingPlanPersistenceRoundTrip;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("paid-teaching-richness-canary")
class TeachingRichLessonPaidCanaryTest {

    private static final String OWNER = "teaching-agent-canary";
    private static final String DEFAULT_PDF = ".local/public-corpus/pdfs/the-captain-is-dead.pdf";
    private static final String DEFAULT_LABEL = "captain-is-dead";
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void plansAndPublishesACompleteMultiChapterLessonFromOneRealRulebookSlice() throws Exception {
        assumeTrue("true".equalsIgnoreCase(environment("RULEPILOT_ALLOW_PAID_CANARY", "false")));
        Path root = Path.of(System.getProperty("user.dir")).getParent().toAbsolutePath().normalize();
        String label = environment("RULEPILOT_TEACHING_CANARY_RUN_LABEL", DEFAULT_LABEL);
        assumeTrue(label.matches("[a-z0-9-]{1,60}"), "canary label must be filesystem safe");
        Path pdf = root.resolve(environment("RULEPILOT_TEACHING_CANARY_PDF", DEFAULT_PDF)).normalize();
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook PDF is required");
        Path output = root.resolve(".local/agent-evaluation/teaching-contract-prune-after-" + label + ".json");
        Files.createDirectories(output.getParent());

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", 2);
        artifact.put("caseLabel", label);
        artifact.put("sourceFile", pdf.getFileName().toString());
        artifact.put("startedAt", Instant.now().toString());
        List<Map<String, Object>> rawOutline = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> rawSections = new CopyOnWriteArrayList<>();
        CanaryInvocations invocations = new CanaryInvocations();
        artifact.put("rawOutlineProviderResponses", rawOutline);
        artifact.put("rawSectionProviderResponses", rawSections);
        artifact.put("activities", invocations.events);
        long totalStarted = System.nanoTime();
        Throwable failure = null;
        try {
            Provider provider = provider();
            artifact.put("provider", provider.name());
            artifact.put("model", provider.model());
            artifact.put("requestTimeoutSeconds", Long.parseLong(
                    environment("RULEPILOT_TEACHING_CANARY_REQUEST_TIMEOUT_SECONDS", "300")));
            artifact.put("deepSeekNonThinkingGeneration", "deepseek".equals(provider.name()));
            VersionedAgentPrompts prompts = prompts();
            UUID versionId = UUID.nameUUIDFromBytes(("teaching-canary:" + label)
                    .getBytes(StandardCharsets.UTF_8));
            PdfCorpus corpus = new PdfCorpus(pdf, versionId);
            artifact.put("pageCount", corpus.pages().size());

            RecordingChatModel outlineChat = recordingModel(provider, rawOutline);
            RuntimeModelConfiguration outlineConfiguration = configuration(provider, outlineChat);
            long outlineStarted = System.nanoTime();
            OutlineDraft outline = new SpringAiTeachingOutlineModel(outlineConfiguration, prompts).organize(
                    new OutlineRequest(
                            corpus.outlinePages(),
                            List.of(),
                            "请自然地阅读规则书并发布玩家第一次开局真正需要的章节；证据不足时明确保留缺口。",
                            OWNER));
            artifact.put("outlineLatencyMs", elapsedMillis(outlineStarted));
            artifact.put("outline", outline);

            TeachingPlan generated = new TeachingPlanFactory().create(
                    versionId,
                    "请自然地阅读规则书并发布玩家第一次开局真正需要的章节。",
                    OWNER,
                    outline);
            TeachingPlan plan = TeachingPlanPersistenceRoundTrip.serializeAndReload(generated);
            assertThat(plan).isEqualTo(generated);

            RecordingChatModel sectionChat = recordingModel(provider, rawSections);
            RuntimeModelConfiguration sectionConfiguration = configuration(provider, sectionChat);
            SpringAiTeachingLessonModel lessonModel = new SpringAiTeachingLessonModel(
                    sectionConfiguration,
                    prompts,
                    Double.parseDouble(environment("RULEPILOT_TEACHING_CANARY_TEMPERATURE", "0.2")));
            VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
            GroundedTeachingAgent agent = new GroundedTeachingAgent(
                    corpus,
                    lessonModel,
                    new PolicyEvidenceVerifier(),
                    invocations,
                    visualFacts,
                    VisualRulebookCatalogerTestFixture.unavailable(corpus, invocations, visualFacts));
            List<IllustratedLesson> progress = new CopyOnWriteArrayList<>();
            long lessonStarted = System.nanoTime();
            IllustratedLesson lesson = agent.createBase(plan, UUID.randomUUID(), null, progress::add);
            artifact.put("lessonLatencyMs", elapsedMillis(lessonStarted));
            artifact.put("plan", plan);
            artifact.put("lesson", lesson);
            artifact.put("progressSnapshots", progress);

            assertThat(rawOutline).isNotEmpty();
            assertThat(rawSections).isNotEmpty();
            assertThat(lesson.sections()).isNotEmpty().allSatisfy(section -> {
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
                assertThat(section.steps()).isNotEmpty().allSatisfy(step -> {
                    assertThat(step.sourcePages()).isNotEmpty();
                    assertThat(step.sourceChunkIds()).isNotEmpty();
                });
            });
            boolean hasExplicitGap = !plan.wholeGameContext().unresolvedTopics().isEmpty()
                    || lesson.sections().size() < plan.sections().size();
            if (hasExplicitGap) assertThat(lesson.status()).isEqualTo(LessonStatus.DRAFT_READY);
        } catch (Throwable thrown) {
            failure = thrown;
            artifact.put("fatalFailure", Map.of(
                    "type", thrown.getClass().getName(),
                    "message", String.valueOf(thrown.getMessage()),
                    "rootCause", rootCause(thrown)));
        } finally {
            artifact.put("calls", Map.of(
                    "outlineModel", rawOutline.size(),
                    "sectionModel", rawSections.size(),
                    "auditedModel", invocations.modelCalls.get(),
                    "tools", invocations.toolCalls.get()));
            artifact.put("finishedAt", Instant.now().toString());
            artifact.put("totalLatencyMs", elapsedMillis(totalStarted));
            Files.writeString(
                    output,
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(artifact) + "\n",
                    StandardCharsets.UTF_8);
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private RuntimeModelConfiguration configuration(Provider provider, ChatModel model) {
        return configuration(provider.name(), provider.model(), model);
    }

    static RuntimeModelConfiguration configuration(String provider, String modelName, ChatModel model) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.TEACHING)).thenReturn(provider);
        when(configuration.providerFor(Role.TEACHING, OWNER)).thenReturn(provider);
        when(configuration.modelFor(Role.TEACHING, OWNER)).thenReturn(model);
        when(configuration.modelNameFor(Role.TEACHING, OWNER)).thenReturn(modelName);
        when(configuration.resolvedModelFor(Role.TEACHING, OWNER))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        model, provider, modelName, "deepseek".equals(provider)));
        when(configuration.usesFake(Role.TEACHING)).thenReturn(false);
        when(configuration.usesFake(Role.TEACHING, OWNER)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.TEACHING, OWNER))
                .thenReturn("deepseek".equals(provider));
        return configuration;
    }

    private RecordingChatModel recordingModel(Provider provider, List<Map<String, Object>> rawResponses) {
        Duration requestTimeout = Duration.ofSeconds(Long.parseLong(
                environment("RULEPILOT_TEACHING_CANARY_REQUEST_TIMEOUT_SECONDS", "300")));
        ChatModel delegate = new ChatModelFactory(ObservationRegistry.NOOP, requestTimeout)
                .create(provider.name(), provider.apiKey(), provider.baseUrl(), provider.model());
        return new RecordingChatModel(delegate, rawResponses);
    }

    private VersionedAgentPrompts prompts() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(VersionedAgentPrompts.class);
            context.refresh();
            return context.getBean(VersionedAgentPrompts.class);
        }
    }

    private Provider provider() {
        String name = environment("RULEPILOT_TEACHING_CANARY_PROVIDER", "deepseek").toLowerCase(Locale.ROOT);
        assumeTrue(Set.of("qwen", "deepseek", "openai").contains(name), "unsupported real teaching provider");
        String prefix = name.toUpperCase(Locale.ROOT);
        return new Provider(
                name,
                requiredEnvironment(prefix + "_API_KEY"),
                requiredEnvironment(prefix + "_BASE_URL"),
                requiredEnvironment(prefix + "_MODEL"));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the real teaching canary");
        return value.strip();
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private String rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getName() + ": " + String.valueOf(current.getMessage());
    }

    private record Provider(String name, String apiKey, String baseUrl, String model) {}

    private static final class RecordingChatModel implements ChatModel {
        private final ChatModel delegate;
        private final List<Map<String, Object>> rawResponses;

        private RecordingChatModel(ChatModel delegate, List<Map<String, Object>> rawResponses) {
            this.delegate = delegate;
            this.rawResponses = rawResponses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            long started = System.nanoTime();
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("at", Instant.now().toString());
            try {
                ChatResponse response = delegate.call(prompt);
                var output = response == null || response.getResult() == null
                        ? null
                        : response.getResult().getOutput();
                raw.put("outcome", "SUCCEEDED");
                raw.put("latencyMs", elapsed(started));
                raw.put("text", output == null || output.getText() == null ? "" : output.getText());
                raw.put("assistantMetadata", output == null ? "" : String.valueOf(output.getMetadata()));
                raw.put("responseMetadata", response == null ? "" : String.valueOf(response.getMetadata()));
                raw.put("completeResponse", String.valueOf(response));
                rawResponses.add(Map.copyOf(raw));
                return response;
            } catch (RuntimeException failure) {
                raw.put("outcome", "FAILED");
                raw.put("latencyMs", elapsed(started));
                raw.put("failure", rootMessage(failure));
                rawResponses.add(Map.copyOf(raw));
                throw failure;
            }
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }

        private static long elapsed(long started) {
            return Duration.ofNanos(System.nanoTime() - started).toMillis();
        }

        private static String rootMessage(Throwable failure) {
            Throwable current = failure;
            while (current.getCause() != null) current = current.getCause();
            return current.getClass().getName() + ": " + String.valueOf(current.getMessage());
        }
    }

    private static final class CanaryInvocations implements AuditedAgentInvocations {
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

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
            if (type == ActivityType.TOOL) toolCalls.incrementAndGet();
            long started = System.nanoTime();
            event(type, operation, "STARTED", null, 0);
            try {
                T result = invocation.get();
                event(type, operation, "SUCCEEDED", successSummary, elapsed(started));
                return result;
            } catch (RuntimeException failure) {
                event(type, operation, "FAILED", rootMessage(failure), elapsed(started));
                throw failure;
            }
        }

        @Override
        public void record(
                UUID runId,
                ActivityType type,
                String operation,
                ActivityOutcome outcome,
                String summary) {
            event(type, operation, outcome.name(), summary, 0);
        }

        private void event(ActivityType type, String operation, String outcome, String summary, long latencyMs) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("at", Instant.now().toString());
            event.put("type", type.name());
            event.put("operation", operation);
            event.put("outcome", outcome);
            event.put("latencyMs", latencyMs);
            if (summary != null) event.put("summary", summary);
            events.add(Map.copyOf(event));
        }

        private long elapsed(long started) {
            return Duration.ofNanos(System.nanoTime() - started).toMillis();
        }

        private static String rootMessage(Throwable failure) {
            Throwable current = failure;
            while (current.getCause() != null) current = current.getCause();
            return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
        }
    }

    private static final class PdfCorpus implements AssistantReadTools {
        private final UUID versionId;
        private final List<String> pages;
        private final List<RuleEvidence> evidence;

        private PdfCorpus(Path pdf, UUID versionId) throws IOException {
            this.versionId = versionId;
            this.pages = extractPages(pdf);
            List<RuleEvidence> collected = new ArrayList<>();
            for (int index = 0; index < pages.size(); index++) {
                String text = pages.get(index);
                if (text.isBlank()) continue;
                int page = index + 1;
                collected.add(new RuleEvidence(
                        UUID.nameUUIDFromBytes((versionId + ":page:" + page).getBytes(StandardCharsets.UTF_8)),
                        versionId,
                        "PAGE",
                        "Rulebook page " + page,
                        text,
                        page,
                        page));
            }
            this.evidence = List.copyOf(collected);
        }

        private List<String> pages() {
            return pages;
        }

        private List<PageInput> outlinePages() {
            return java.util.stream.IntStream.range(0, pages.size())
                    .mapToObj(index -> {
                        String text = pages.get(index);
                        boolean available = !text.isBlank();
                        return new PageInput(
                                index + 1,
                                available ? text : "No readable text was available for this page.",
                                available);
                    })
                    .toList();
        }

        @Override
        public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
            if (!versionId.equals(request.documentVersionId())) return List.of();
            return evidence.stream().limit(request.limit()).toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidencePages(
                UUID documentVersionId,
                Set<Integer> pageNumbers,
                boolean includePageImages) {
            if (!versionId.equals(documentVersionId)) return List.of();
            return evidence.stream().filter(source -> pageNumbers.contains(source.pageFrom())).toList();
        }

        private static List<String> extractPages(Path pdf) throws IOException {
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                List<String> result = new ArrayList<>();
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    result.add(stripper.getText(document).strip());
                }
                return List.copyOf(result);
            }
        }
    }
}
