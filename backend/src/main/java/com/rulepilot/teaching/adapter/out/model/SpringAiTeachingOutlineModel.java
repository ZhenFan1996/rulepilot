package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.ModelCall;
import com.rulepilot.teaching.TeachingOutlineModel.ModelCallExecutor;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDependencyDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** A single outline Agent that decides when to read, publish a chapter plan, or finish. */
@Component
@Primary
public class SpringAiTeachingOutlineModel implements TeachingOutlineModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTeachingOutlineModel.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String ACTION_SCHEMA = """
            One JSON object matching exactly one action:
            {"action":"read_pages","pageNumbers":[1],"reason":"..."}
            {"action":"publish_chapter","chapter":{"key":"kebab-case","title":"...","objective":"...","sourcePageNumbers":[1],"visualEvidenceRecommended":false,"afterChapterIds":[]},"reason":"..."}
            {"action":"complete","gameTitle":"printed source title","premise":"natural Simplified-Chinese orientation","coveredChapterIds":["chapter-id"],"unresolvedTopics":[],"reason":"..."}
            """;

    private final RuntimeModelConfiguration models;
    private final double temperature;
    private final String systemPrompt;
    private final String userPrompt;

    public SpringAiTeachingOutlineModel(RuntimeModelConfiguration models, VersionedAgentPrompts prompts) {
        this(models, prompts, 0.1);
    }

    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            double temperature) {
        this(
                models,
                prompts,
                temperature,
                read(new ClassPathResource("prompts/teaching-outline-v19-autonomous-units-system.txt")),
                read(new ClassPathResource("prompts/teaching-outline-v19-user.txt")));
    }

    @Autowired
    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            @Value("${rulepilot.teaching.outline-temperature:0.1}") double temperature,
            @Value("classpath:prompts/teaching-outline-v19-autonomous-units-system.txt") Resource systemPrompt,
            @Value("classpath:prompts/teaching-outline-v19-user.txt") Resource userPrompt) {
        this(models, prompts, temperature, read(systemPrompt), read(userPrompt));
    }

    SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            double temperature,
            String systemPrompt,
            String userPrompt) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("teaching outline model temperature must be between 0 and 2");
        }
        this.models = models;
        this.temperature = temperature;
        this.systemPrompt = required(systemPrompt, "teaching outline system prompt");
        this.userPrompt = required(userPrompt, "teaching outline user prompt");
    }

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        return organize(request, ModelCallExecutor.direct());
    }

    @Override
    public OutlineDraft organize(OutlineRequest request, ModelCallExecutor calls) {
        Role role = Role.TEACHING;
        String owner = request.modelConfigurationOwner();
        if (usesFake(role, owner)) {
            throw new OutlineGenerationException(
                    "teaching outline model is not configured",
                    new IllegalStateException("a real teaching model is required to organize a rulebook"));
        }
        try {
            return runAgent(request, role, owner, calls);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (OutlineProviderFailure | InvalidOutlineAction failure) {
            throw new OutlineGenerationException("teaching outline Agent returned no usable chapter plan", failure);
        }
    }

    private OutlineDraft runAgent(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls) {
        AgentState state = startState(request);
        Set<RejectionObservation> seenRejections = new LinkedHashSet<>();
        for (int turn = 1; ; turn++) {
            String stateJson = json(state.view());
            String callInput = systemPrompt + "\n" + userPrompt + "\n" + stateJson;
            String content = callProvider(
                    calls,
                    "advanceTeachingOutlineAgent|" + turn,
                    callInput,
                    "Teaching outline Agent chose its next action",
                    () -> configuredPrompt(role, owner)
                            .system(systemPrompt)
                            .user(user -> user.text(userPrompt)
                                    .param("learningGoal", request.learningGoalForPrompt())
                                    .param("state", stateJson))
                            .call()
                            .content());
            try {
                OutlineAction action = parseAction(content);
                switch (action) {
                    case ReadPagesAction read -> state.read(read);
                    case PublishChapterAction publish -> state.publish(publish);
                    case CompleteAction complete -> {
                        return state.complete(complete);
                    }
                }
                seenRejections.clear();
            } catch (ActionValidationFailure invalid) {
                RejectionObservation rejection = rejection(request, state, content, invalid);
                recordRejection(calls, turn, rejection);
                if (!seenRejections.add(rejection)) {
                    throw new InvalidOutlineAction(
                            "teaching outline Agent repeated the same rejected complete action and observation",
                            invalid);
                }
                state.observe(rejection);
            } catch (JsonProcessingException invalidJson) {
                ActionValidationFailure invalid = new ActionValidationFailure(
                        "INVALID_JSON",
                        jsonPath(invalidJson),
                        deepestMessage(invalidJson),
                        invalidJson);
                RejectionObservation rejection = rejection(request, state, content, invalid);
                recordRejection(calls, turn, rejection);
                if (!seenRejections.add(rejection)) {
                    throw new InvalidOutlineAction(
                            "teaching outline Agent repeated the same invalid JSON and observation",
                            invalidJson);
                }
                state.observe(rejection);
            }
        }
    }

    private AgentState startState(OutlineRequest request) {
        LinkedHashMap<Integer, PageInput> pages = new LinkedHashMap<>();
        for (PageInput page : request.pages()) {
            if (pages.putIfAbsent(page.pageNumber(), page) != null) {
                throw new IllegalArgumentException("teaching outline page identities must be unique");
            }
        }
        AgentState state = new AgentState(request, pages);
        pages.values().stream()
                .filter(PageInput::available)
                .findFirst()
                .ifPresent(page -> state.readPages.add(page.pageNumber()));
        state.latestObservation = Map.of(
                "code", "AGENT_STARTED",
                "reason", "The first available rule page is ready; choose the next action.");
        return state;
    }

    private OutlineAction parseAction(String content) throws JsonProcessingException {
        if (content == null || content.isBlank()) {
            throw new ActionValidationFailure("EMPTY_ACTION", "$", "model returned no JSON action", null);
        }
        JsonNode root = JSON.readTree(content);
        if (root == null || !root.isObject()) {
            throw new ActionValidationFailure("INVALID_ACTION", "$", "action must be a JSON object", null);
        }
        JsonNode action = root.get("action");
        if (action == null || !action.isTextual()) {
            throw new ActionValidationFailure("MISSING_ACTION", "$.action", "action must be a string", null);
        }
        return switch (action.textValue()) {
            case "read_pages" -> JSON.treeToValue(root, ReadPagesAction.class);
            case "publish_chapter" -> JSON.treeToValue(root, PublishChapterAction.class);
            case "complete" -> JSON.treeToValue(root, CompleteAction.class);
            default -> throw new ActionValidationFailure(
                    "UNKNOWN_ACTION",
                    "$.action",
                    "allowed actions are read_pages, publish_chapter, and complete",
                    null);
        };
    }

    private RejectionObservation rejection(
            OutlineRequest request,
            AgentState state,
            String candidate,
            ActionValidationFailure invalid) {
        return new RejectionObservation(
                invalid.code,
                invalid.path,
                invalid.getMessage(),
                ACTION_SCHEMA,
                candidate == null ? "" : candidate,
                request.pages().stream().map(page -> "page-" + page.pageNumber()).toList(),
                List.copyOf(state.chapters.keySet()));
    }

    private void recordRejection(ModelCallExecutor calls, int turn, RejectionObservation rejection) {
        try {
            calls.recordRejection(
                    "validateTeachingOutlineAction|" + turn,
                    "Teaching outline action rejected: " + rejection.code() + " at " + rejection.path());
        } catch (RuntimeException auditFailure) {
            log.warn("Could not record rejected teaching-outline action", auditFailure);
        }
    }

    private String callProvider(
            ModelCallExecutor calls,
            String operation,
            String input,
            String summary,
            Supplier<String> providerCall) {
        return calls.invoke(
                new ModelCall(operation, estimateTextTokens(input), summary),
                () -> {
                    try {
                        return providerCall.get();
                    } catch (AgentExecutionStoppedException stopped) {
                        throw stopped;
                    } catch (RuntimeException failure) {
                        if (Thread.currentThread().isInterrupted()) throw failure;
                        if (!isProviderAvailabilityFailure(failure)) throw failure;
                        throw new OutlineProviderFailure("teaching outline provider call failed", failure);
                    }
                },
                SpringAiTeachingOutlineModel::estimateTextTokens);
    }

    private ChatClient.ChatClientRequestSpec configuredPrompt(Role role, String owner) {
        RuntimeModelConfiguration.ResolvedModel selected = models.resolvedModelFor(role, owner);
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(selected.model()).prompt();
        OpenAiChatOptions.Builder options = providerOptions(selected);
        if (options == null) {
            return prompt.options(ChatOptions.builder().temperature(temperature));
        }
        options.model(selected.modelName());
        return prompt.options(options);
    }

    private OpenAiChatOptions.Builder providerOptions(RuntimeModelConfiguration.ResolvedModel selected) {
        if (selected.deepSeekNonThinkingGeneration()) {
            return OpenAiChatOptions.builder()
                    .temperature(temperature)
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                    .extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        if ("qwen".equals(selected.provider())) {
            return OpenAiChatOptions.builder()
                    .temperature(temperature)
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                    .extraBody(Map.of("enable_thinking", false));
        }
        return null;
    }

    boolean usesFake(Role role, String owner) {
        return owner == null || owner.isBlank() ? models.usesFake(role) : models.usesFake(role, owner);
    }

    static int estimateTextTokens(String value) {
        return Math.max(1, value == null ? 1 : (value.length() + 3) / 4);
    }

    static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.io.InterruptedIOException
                    || (message != null && message.toLowerCase(Locale.ROOT).contains("timeout"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProviderAvailabilityFailure(Throwable failure) {
        if (isTimeout(failure)) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientAiException) return true;
        }
        return false;
    }

    private final class AgentState {
        private final OutlineRequest request;
        private final Map<Integer, PageInput> pages;
        private final LinkedHashSet<Integer> readPages = new LinkedHashSet<>();
        private final LinkedHashMap<String, ChapterCandidate> chapters = new LinkedHashMap<>();
        private Object latestObservation;

        private AgentState(OutlineRequest request, Map<Integer, PageInput> pages) {
            this.request = request;
            this.pages = pages;
        }

        private AgentView view() {
            List<PagePreview> available = pages.values().stream()
                    .map(page -> new PagePreview(
                            page.pageNumber(),
                            page.available() ? "AVAILABLE" : "UNAVAILABLE"))
                    .toList();
            List<ReadPage> read = readPages.stream()
                    .map(pages::get)
                    .filter(java.util.Objects::nonNull)
                    .map(page -> new ReadPage(page.pageNumber(), page.text()))
                    .toList();
            return new AgentView(
                    request.learningGoalForPrompt(),
                    available,
                    read,
                    List.copyOf(chapters.values()),
                    unreadAvailablePages(),
                    uncoveredReadPages(),
                    latestObservation,
                    ACTION_SCHEMA);
        }

        private void read(ReadPagesAction action) {
            requireReason(action.reason(), "$.reason");
            if (action.pageNumbers() == null || action.pageNumbers().isEmpty()) {
                reject("EMPTY_PAGE_READ", "$.pageNumbers", "read_pages requires at least one page");
            }
            LinkedHashSet<Integer> requested = new LinkedHashSet<>(action.pageNumbers());
            if (requested.contains(null) || requested.stream().anyMatch(page -> page < 1)) {
                reject("INVALID_PAGE_ID", "$.pageNumbers", "page identities must be positive integers");
            }
            List<Integer> admitted = new ArrayList<>();
            List<Integer> unavailable = new ArrayList<>();
            for (Integer pageNumber : requested) {
                PageInput page = pages.get(pageNumber);
                if (page == null || !page.available()) {
                    unavailable.add(pageNumber);
                } else if (readPages.add(pageNumber)) {
                    admitted.add(pageNumber);
                }
            }
            if (admitted.isEmpty() && unavailable.isEmpty()) {
                reject("NO_PROGRESS", "$.pageNumbers", "every requested page was already read");
            }
            latestObservation = Map.of(
                    "code", unavailable.isEmpty() ? "PAGES_READ" : "PAGE_READ_PARTIAL",
                    "readPageIds", admitted,
                    "unavailablePageIds", unavailable,
                    "reason", "Unavailable pages are local observations; continue with readable evidence.");
        }

        private void publish(PublishChapterAction action) {
            requireReason(action.reason(), "$.reason");
            ChapterCandidate chapter = action.chapter();
            if (chapter == null) reject("MISSING_CHAPTER", "$.chapter", "publish_chapter requires a chapter");
            if (chapter.key() == null || !chapter.key().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                reject("INVALID_CHAPTER_ID", "$.chapter.key", "chapter key must be unique kebab-case");
            }
            if (chapters.containsKey(chapter.key())) {
                reject("DUPLICATE_CHAPTER", "$.chapter.key", "chapter key was already published");
            }
            requiredField(chapter.title(), "$.chapter.title");
            requiredField(chapter.objective(), "$.chapter.objective");
            if (chapter.sourcePageNumbers() == null || chapter.sourcePageNumbers().isEmpty()) {
                reject("MISSING_CHAPTER_EVIDENCE", "$.chapter.sourcePageNumbers", "chapter needs a read source page");
            }
            if (chapter.sourcePageNumbers().stream().anyMatch(page -> page == null || !readPages.contains(page))) {
                reject("UNREAD_CHAPTER_EVIDENCE", "$.chapter.sourcePageNumbers", "chapter may cite only read page IDs");
            }
            List<String> dependencies = chapter.afterChapterIds() == null ? List.of() : chapter.afterChapterIds();
            if (dependencies.stream().anyMatch(id -> id == null || !chapters.containsKey(id))) {
                reject("UNKNOWN_CHAPTER_DEPENDENCY", "$.chapter.afterChapterIds", "dependencies must name earlier published chapters");
            }
            ChapterCandidate accepted = new ChapterCandidate(
                    chapter.key(),
                    chapter.title().strip(),
                    chapter.objective().strip(),
                    chapter.sourcePageNumbers().stream().distinct().toList(),
                    Boolean.TRUE.equals(chapter.visualEvidenceRecommended()),
                    dependencies.stream().distinct().toList());
            chapters.put(accepted.key(), accepted);
            latestObservation = Map.of(
                    "code", "CHAPTER_PUBLISHED",
                    "chapterId", accepted.key(),
                    "reason", "The chapter plan passed identity and source-page validation.");
        }

        private OutlineDraft complete(CompleteAction action) {
            requireReason(action.reason(), "$.reason");
            requiredField(action.gameTitle(), "$.gameTitle");
            requiredField(action.premise(), "$.premise");
            if (chapters.isEmpty()) reject("EMPTY_LESSON", "$.coveredChapterIds", "complete requires a published chapter");
            List<String> covered = action.coveredChapterIds() == null ? List.of() : action.coveredChapterIds();
            List<String> unresolved = action.unresolvedTopics() == null ? List.of() : action.unresolvedTopics();
            if (covered.stream().anyMatch(id -> id == null || !chapters.containsKey(id))) {
                reject("UNKNOWN_COVERED_CHAPTER", "$.coveredChapterIds", "covered chapter IDs must already be published");
            }
            if (unresolved.stream().anyMatch(topic -> topic == null || topic.isBlank())) {
                reject("INVALID_UNRESOLVED_TOPIC", "$.unresolvedTopics", "unresolved topics must be non-blank descriptions");
            }
            Set<String> coveredIds = new LinkedHashSet<>(covered);
            List<Integer> unreadPages = unreadAvailablePages();
            List<Integer> unusedReadPages = uncoveredReadPages();
            List<TopicDraft> topics = chapters.values().stream()
                    .map(chapter -> new TopicDraft(
                            chapter.key(),
                            chapter.title(),
                            chapter.objective(),
                            chapter.visualEvidenceRecommended(),
                            chapter.sourcePageNumbers()))
                    .toList();
            List<TopicDependencyDraft> dependencies = chapters.values().stream()
                    .flatMap(chapter -> chapter.afterChapterIds().stream()
                            .map(prerequisite -> new TopicDependencyDraft(
                                    prerequisite,
                                    chapter.key(),
                                    "The teaching Agent published this chapter after its prerequisite.")))
                    .toList();
            List<String> durableUnresolved = new ArrayList<>(unresolved.stream().map(String::strip).toList());
            unreadPages.forEach(page -> durableUnresolved.add("Unread available rulebook page " + page));
            unusedReadPages.forEach(page -> durableUnresolved.add("Read rulebook page not used by any chapter " + page));
            chapters.keySet().stream()
                    .filter(id -> !coveredIds.contains(id))
                    .forEach(id -> durableUnresolved.add("Published chapter not acknowledged as covered: " + id));
            return new OutlineDraft(
                    action.gameTitle().strip(),
                    action.premise().strip(),
                    topics,
                    dependencies,
                    durableUnresolved.stream().distinct().toList());
        }

        private List<Integer> unreadAvailablePages() {
            return pages.values().stream()
                    .filter(PageInput::available)
                    .map(PageInput::pageNumber)
                    .filter(page -> !readPages.contains(page))
                    .toList();
        }

        private List<Integer> uncoveredReadPages() {
            LinkedHashSet<Integer> uncovered = new LinkedHashSet<>(readPages);
            chapters.values().forEach(chapter -> uncovered.removeAll(chapter.sourcePageNumbers()));
            return List.copyOf(uncovered);
        }

        private void observe(RejectionObservation observation) {
            latestObservation = observation;
        }

        private void reject(String code, String path, String reason) {
            throw new ActionValidationFailure(code, path, reason, null);
        }

        private void requireReason(String value, String path) {
            if (value == null || value.isBlank()) reject("MISSING_REASON", path, "action reason is required");
        }

        private void requiredField(String value, String path) {
            if (value == null || value.isBlank()) reject("MISSING_FIELD", path, "field must be non-blank");
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize teaching outline Agent state", impossible);
        }
    }

    private static String jsonPath(JsonProcessingException failure) {
        if (!(failure instanceof JsonMappingException mapping) || mapping.getPath().isEmpty()) return "$";
        StringBuilder path = new StringBuilder("$");
        for (JsonMappingException.Reference reference : mapping.getPath()) {
            if (reference.getFieldName() != null) path.append('.').append(reference.getFieldName());
            else path.append('[').append(reference.getIndex()).append(']');
        }
        return path.toString();
    }

    private static String deepestMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.strip();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read teaching outline prompt", failure);
        }
    }

    private sealed interface OutlineAction permits ReadPagesAction, PublishChapterAction, CompleteAction {}

    private record ReadPagesAction(String action, List<Integer> pageNumbers, String reason) implements OutlineAction {}

    private record PublishChapterAction(String action, ChapterCandidate chapter, String reason) implements OutlineAction {}

    private record CompleteAction(
            String action,
            String gameTitle,
            String premise,
            List<String> coveredChapterIds,
            List<String> unresolvedTopics,
            String reason) implements OutlineAction {}

    private record ChapterCandidate(
            String key,
            String title,
            String objective,
            List<Integer> sourcePageNumbers,
            Boolean visualEvidenceRecommended,
            List<String> afterChapterIds) {}

    private record PagePreview(int pageNumber, String availability) {}

    private record ReadPage(int pageNumber, String text) {}

    private record AgentView(
            String learningGoal,
            List<PagePreview> availablePages,
            List<ReadPage> readRulePages,
            List<ChapterCandidate> publishedChapters,
            List<Integer> unreadAvailablePageIds,
            List<Integer> readPagesNotUsedByAnyChapter,
            Object latestObservation,
            String actionSchema) {}

    private record RejectionObservation(
            String code,
            String path,
            String reason,
            String schema,
            String candidateJson,
            List<String> allowedPageIds,
            List<String> allowedChapterIds) {}

    private static final class ActionValidationFailure extends RuntimeException {
        private final String code;
        private final String path;

        private ActionValidationFailure(String code, String path, String reason, Throwable cause) {
            super(reason, cause);
            this.code = code;
            this.path = path;
        }
    }

    private static final class InvalidOutlineAction extends RuntimeException {
        private InvalidOutlineAction(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class OutlineProviderFailure extends RuntimeException {
        private OutlineProviderFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
