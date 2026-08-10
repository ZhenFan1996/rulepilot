package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.application.SourceLanguageRetrievalPolicy;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Primary
public class SpringAiTeachingOutlineModel implements TeachingOutlineModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTeachingOutlineModel.class);
    private static final int MAX_OUTLINE_COMPLETION_TOKENS = 3_000;
    private static final long MAX_REPAIR_ELAPSED_NANOS = java.time.Duration.ofSeconds(45).toNanos();
    private static final long OUTLINE_DEADLINE_SECONDS = 45;

    private final RuntimeModelConfiguration models;
    private final VersionedAgentPrompts prompts;
    private final FakeTeachingOutlineModel fake;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final ExecutorService outlineCalls = Executors.newVirtualThreadPerTaskExecutor();

    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models, VersionedAgentPrompts prompts, FakeTeachingOutlineModel fake) {
        this.models = models;
        this.prompts = prompts;
        this.fake = fake;
    }

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        Role role = roleFor(request);
        String owner = request.modelConfigurationOwner();
        if (usesFake(role, owner)) return fake.organize(request);
        var call = outlineCalls.submit(() -> organizeWithRepair(request, role, owner));
        try {
            return call.get(OUTLINE_DEADLINE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            call.cancel(true);
            log.warn("Teaching-outline model exceeded {} seconds; continuing with source-derived plan", OUTLINE_DEADLINE_SECONDS);
            return fake.organize(request);
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("teaching outline interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("teaching outline failed", failed.getCause());
        }
    }

    @Override
    public OutlineDraft fallback(OutlineRequest request) {
        return fake.organize(request);
    }

    @Override
    public OutlineDraft refineChapterOwnership(OutlineRequest request, OutlineDraft current, String feedback) {
        if (current == null || feedback == null || feedback.isBlank()) return current;
        Role role = roleFor(request);
        String owner = request.modelConfigurationOwner();
        if (usesFake(role, owner)) return current;
        var call = outlineCalls.submit(() -> organizeWithRepair(
                request, role, owner, ownershipRefinementInstruction(current, feedback.strip())));
        try {
            return call.get(OUTLINE_DEADLINE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            call.cancel(true);
            log.warn(
                    "Teaching-outline ownership refinement exceeded {} seconds; retaining the original plan",
                    OUTLINE_DEADLINE_SECONDS);
            return current;
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("teaching outline ownership refinement interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AgentExecutionStoppedException stopped) throw stopped;
            log.warn("Teaching-outline ownership refinement failed; retaining the original plan", failed.getCause());
            return current;
        }
    }

    static String ownershipRefinementInstruction(OutlineDraft current, String feedback) {
        String currentTopics = java.util.stream.IntStream.range(0, current.topics().size())
                .mapToObj(index -> {
                    var topic = current.topics().get(index);
                    String objective = topic.objective().length() <= 360
                            ? topic.objective()
                            : topic.objective().substring(0, 359) + "…";
                    return (index + 1) + ". " + topic.key() + " | " + topic.title() + " | " + objective
                            + " | queries=" + topic.retrievalQueries()
                            + " | tags=" + topic.coverageTags()
                            + " | pages=" + topic.sourcePageNumbers();
                })
                .collect(java.util.stream.Collectors.joining("\n"));
        return feedback + "\nCurrent complete outline (revise this structure; do not start over):\n"
                + currentTopics
                + "\nReturn a complete replacement outline. Keep each existing learning outcome, source-page binding, "
                + "and source-language retrieval query unless moving its nested detail to its named owner. "
                + "When the feedback identifies an impossible lesson order, reorder whole topics while retaining their "
                + "coverage and evidence. Do not invent a new action, alternative, or rule relationship while separating chapters.";
    }

    private OutlineDraft organizeWithRepair(OutlineRequest request, Role role, String owner) {
        return organizeWithRepair(request, role, owner, "");
    }

    private OutlineDraft organizeWithRepair(OutlineRequest request, Role role, String owner, String initialInstruction) {
        long startedAt = System.nanoTime();
        RuntimeException firstFailure;
        try {
            return organizeOnce(request, role, owner, initialInstruction);
        } catch (RuntimeException failure) {
            if (isTimeout(failure) || System.nanoTime() - startedAt > MAX_REPAIR_ELAPSED_NANOS) throw failure;
            firstFailure = failure;
            log.warn("First teaching-outline model response failed: {}", failure.getMessage());
        }
        try {
            String correction = (initialInstruction.isBlank() ? "" : initialInstruction + "\n")
                    + "The previous outline failed schema or retrieval-language validation. "
                    + "Rebuild the complete outline. Retrieval queries must copy exact terms from the rulebook's "
                    + "source language; player-facing fields remain Simplified Chinese.\n"
                    + prompts.structuredOutputRepair();
            return organizeOnce(request, role, owner, correction);
        } catch (RuntimeException failure) {
            log.warn("Repaired teaching-outline model response failed: {}", failure.getMessage());
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    @PreDestroy
    void close() {
        outlineCalls.shutdownNow();
    }

    private OutlineDraft organizeOnce(OutlineRequest request, Role role, String owner, String repair) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(role, owner)).prompt();
        OpenAiChatOptions.Builder options = providerOptions(role, owner);
        if (options != null) {
            options.model(models.modelNameFor(role, owner));
            prompt = prompt.options(options);
        }
        OutlineDraft outline = prompt
                .system(prompts.teachingOutlineSystem())
                .user(user -> {
                    user.text(prompts.teachingOutlineUser())
                            .param("learningGoal", request.learningGoalForPrompt())
                            .param("pages", request.pages())
                            .param("visualPages", request.pageImages().stream()
                                    .map(TeachingOutlineModel.PageImageInput::pageNumber)
                                    .toList())
                            .param("repair", repair);
                    if (role == Role.VISUAL) {
                        request.pageImages().stream().map(images::prepare).forEach(image -> user.media(
                                MimeTypeUtils.parseMimeType(image.mediaType()), new ByteArrayResource(image.content())));
                    }
        })
                .call()
                .entity(OutlineDraft.class);
        if (outline == null) throw new IllegalArgumentException("teaching outline model returned no draft");
        if (!outline.topics().isEmpty()
                && outline.topics().stream().allMatch(topic -> !topic.sourcePageNumbers().isEmpty())) {
            return outline;
        }
        SourceLanguageRetrievalPolicy.validate(request, outline);
        return outline;
    }

    OpenAiChatOptions.Builder providerOptions(Role role, String owner) {
        if (models.usesDeepSeekNonThinkingGeneration(role, owner)) {
            return OpenAiChatOptions.builder()
                    .extraBody(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        }
        if (usesQwen(role, owner)) {
            return OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .maxTokens(MAX_OUTLINE_COMPLETION_TOKENS)
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                    .extraBody(java.util.Map.of("enable_thinking", false));
        }
        return null;
    }

    private boolean usesQwen(Role role, String owner) {
        String provider = owner == null || owner.isBlank()
                ? models.providerFor(role)
                : models.providerFor(role, owner);
        return "qwen".equals(provider);
    }

    static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.io.InterruptedIOException
                    || (message != null
                            && (message.toLowerCase(java.util.Locale.ROOT).contains("timeout")
                                    || message.toLowerCase(java.util.Locale.ROOT).contains("timed out")))) {
                return true;
            }
        }
        return false;
    }

    private Role roleFor(OutlineRequest request) {
        // The visual role has already converted rendered pages into a bounded factual catalog.
        // Organizing that catalog is a text-planning task and should not repeat the image upload.
        return Role.TEACHING;
    }

    boolean usesFake(Role role, String owner) {
        return owner == null || owner.isBlank() ? models.usesFake(role) : models.usesFake(role, owner);
    }

}
