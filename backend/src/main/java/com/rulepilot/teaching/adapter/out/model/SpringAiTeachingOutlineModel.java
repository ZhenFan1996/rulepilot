package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.shared.AsyncContextPropagation;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.ModelCall;
import com.rulepilot.teaching.TeachingOutlineModel.ModelCallExecutor;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineCapacityExceededException;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDependencyDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.TeachingOutlineModel.WholeGameUnderstandingDraft;
import com.rulepilot.teaching.VisualSourceRuleGroupLedger;
import com.rulepilot.teaching.application.SourceLanguageRetrievalPolicy;
import com.rulepilot.teaching.application.TeachingSourceCoverageContract;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Primary
public class SpringAiTeachingOutlineModel implements TeachingOutlineModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTeachingOutlineModel.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_OUTLINE_COMPLETION_TOKENS = 16_000;
    private static final long OUTLINE_ATTEMPT_DEADLINE_SECONDS = 60;
    private static final int MAX_TRANSIENT_OUTLINE_ATTEMPTS = 2;
    private static final int DEFAULT_OUTLINE_SHARD_PARALLELISM = 10;
    static final int MAX_HIERARCHICAL_INPUT_TOKENS = 64_000;
    private static final int LOCAL_OWNERSHIP_OUTPUT_TOKENS = 4_000;
    private static final int LOCAL_OWNERSHIP_OUTPUT_RESERVE_TOKENS = 512;
    private static final int GLOBAL_ORDERING_OUTPUT_RESERVE_TOKENS = 4_096;
    private static final int MAX_LOCAL_TEACHING_UNIT_ID_CHARACTERS = 80;
    static final int MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS = 32_000;
    private static final String LOCAL_OWNERSHIP_SYSTEM_PROMPT = """
            You assign immutable canonical source slots to bounded teaching units.
            Return JSON only: {"teachingUnits":[{"teachingUnitId":"kebab-case","role":"SETUP|CORE_LOOP|LEGAL_ACTION|ENDING|SCORING|NECESSARY_EXCEPTION|SUPPORTING_RULE","sourceSlotIds":["exact-slot-id"]}]}.
            Every supplied slot must appear exactly once. Do not write player-facing rule prose, topics, examples, or facts.
            A unit may group only closely related slots with the same availability. Copy slot IDs exactly.
            """;
    private static final String LOCAL_OWNERSHIP_USER_PROMPT = """
            Learning goal: {learningGoal}

            Canonical ledger shard:
            {sourceLedger}

            {repair}
            """;
    private static final String GLOBAL_ORDERING_SYSTEM_PROMPT = """
            You organize already source-owned teaching units into a coherent whole-game lesson.
            Return JSON only with gameTitle, premise, topics, and wholeGameUnderstanding.
            Each topic has key, objective, required, visualEvidenceRecommended, and teachingUnitIds.
            Every supplied teachingUnitId must appear in exactly one topic. Do not change units, slot ownership, roles,
            identifiers, or page bindings. The input contains typed unit summaries, not rulebook prose; do not invent
            omitted rules. Whole-game concepts must cite exact supplied sourceSlotIds and related topic keys.
            Player-facing fields are Simplified Chinese; machine IDs remain kebab-case.
            """;
    private static final String GLOBAL_ORDERING_USER_PROMPT = """
            Learning goal: {learningGoal}

            Source-owned teaching units:
            {teachingUnits}

            Source catalog state: {sourceCatalogState}

            {repair}
            """;

    private final RuntimeModelConfiguration models;
    private final VersionedAgentPrompts prompts;
    private final String outlineSystemPrompt;
    private final String outlineUserPrompt;
    private final String canonicalLedgerSystemPrompt;
    private final String canonicalLedgerUserPrompt;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final ExecutorService outlineCalls;
    private final double temperature;
    private final int outlineShardParallelism;

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
                read(new ClassPathResource("prompts/teaching-outline-v19-user.txt")),
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-system.txt")),
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-user.txt")),
                DEFAULT_OUTLINE_SHARD_PARALLELISM);
    }

    @Autowired
    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            @Value("${rulepilot.teaching.outline-temperature:0.1}") double temperature,
            @Value("${rulepilot.teaching.outline-shard-parallelism:10}") int outlineShardParallelism,
            @Value("classpath:prompts/teaching-outline-v19-autonomous-units-system.txt") Resource outlineSystemPrompt,
            @Value("classpath:prompts/teaching-outline-v19-user.txt") Resource outlineUserPrompt,
            @Value("classpath:prompts/teaching-outline-v20-canonical-ledger-system.txt") Resource canonicalLedgerSystemPrompt,
            @Value("classpath:prompts/teaching-outline-v20-canonical-ledger-user.txt") Resource canonicalLedgerUserPrompt) {
        this(
                models,
                prompts,
                temperature,
                read(outlineSystemPrompt),
                read(outlineUserPrompt),
                read(canonicalLedgerSystemPrompt),
                read(canonicalLedgerUserPrompt),
                outlineShardParallelism);
    }

    SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            double temperature,
            String outlineSystemPrompt,
            String outlineUserPrompt) {
        this(
                models,
                prompts,
                temperature,
                outlineSystemPrompt,
                outlineUserPrompt,
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-system.txt")),
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-user.txt")),
                DEFAULT_OUTLINE_SHARD_PARALLELISM);
    }

    SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            double temperature,
            String outlineSystemPrompt,
            String outlineUserPrompt,
            String canonicalLedgerSystemPrompt,
            String canonicalLedgerUserPrompt) {
        this(
                models,
                prompts,
                temperature,
                outlineSystemPrompt,
                outlineUserPrompt,
                canonicalLedgerSystemPrompt,
                canonicalLedgerUserPrompt,
                DEFAULT_OUTLINE_SHARD_PARALLELISM);
    }

    SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            double temperature,
            String outlineSystemPrompt,
            String outlineUserPrompt,
            String canonicalLedgerSystemPrompt,
            String canonicalLedgerUserPrompt,
            int outlineShardParallelism) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("teaching outline model temperature must be between 0 and 2");
        }
        if (outlineShardParallelism < 1 || outlineShardParallelism > 10) {
            throw new IllegalArgumentException("teaching outline shard parallelism must be between one and ten");
        }
        this.models = models;
        this.prompts = prompts;
        this.temperature = temperature;
        this.outlineSystemPrompt = outlineSystemPrompt;
        this.outlineUserPrompt = outlineUserPrompt;
        this.canonicalLedgerSystemPrompt = canonicalLedgerSystemPrompt;
        this.canonicalLedgerUserPrompt = canonicalLedgerUserPrompt;
        this.outlineShardParallelism = outlineShardParallelism;
        this.outlineCalls = AsyncContextPropagation.executorService(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        return organize(request, ModelCallExecutor.direct());
    }

    @Override
    public OutlineDraft organize(OutlineRequest request, ModelCallExecutor calls) {
        if (VisualSourceRuleGroupLedger.usesTypedVisualPageProtocol(request.pages())
                && !VisualSourceRuleGroupLedger.supportsTypedCanonicalOutline(request.pages())) {
            throw new OutlineGenerationException(
                    "visual rulebook has no safe canonical source ledger to plan",
                    new IllegalArgumentException(
                            "typed visual pages require exact internal bindings, at least one admitted rule anchor, and no legacy page state"));
        }
        Role role = roleFor(request);
        String owner = request.modelConfigurationOwner();
        if (usesFake(role, owner)) {
            throw new OutlineGenerationException(
                    "teaching outline model is not configured",
                    new IllegalStateException("a real teaching model is required to organize a rulebook"));
        }
        try {
            return organizeWithRepair(request, role, owner, calls, "organizeTeachingOutline");
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            throw new OutlineGenerationException(
                    "teaching outline generation returned no valid outline",
                    failure);
        }
    }

    static IllegalStateException planningTimeout(TimeoutException timeout) {
        return new IllegalStateException(
                "teaching outline timed out before a semantic lesson plan was available; retry preparation",
                timeout);
    }

    @Override
    public OutlineDraft refineChapterOwnership(OutlineRequest request, OutlineDraft current, String feedback) {
        return refineChapterOwnership(request, current, feedback, ModelCallExecutor.direct());
    }

    @Override
    public OutlineDraft refineChapterOwnership(
            OutlineRequest request,
            OutlineDraft current,
            String feedback,
            ModelCallExecutor calls) {
        if (current == null || feedback == null || feedback.isBlank()) return current;
        Role role = roleFor(request);
        String owner = request.modelConfigurationOwner();
        if (usesFake(role, owner)) return current;
        try {
            if (hasCanonicalVisualLedger(request) && requiresHierarchicalPlanning(request)) {
                return organizeHierarchicalGlobal(
                        request,
                        role,
                        owner,
                        calls,
                        "refineTeachingOutlineOwnership",
                        unitsFromOutline(request, current),
                        ownershipRefinementInstruction(current, feedback.strip()));
            }
            return organizeWithRepair(
                    request,
                    role,
                    owner,
                    calls,
                    "refineTeachingOutlineOwnership",
                    ownershipRefinementInstruction(current, feedback.strip()));
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException failed) {
            log.warn("Teaching-outline ownership refinement failed; retaining the original plan", failed);
            return current;
        }
    }

    static String ownershipRefinementInstruction(OutlineDraft current, String feedback) {
        String currentTopics = java.util.stream.IntStream.range(0, current.topics().size())
                .mapToObj(index -> {
                    var topic = current.topics().get(index);
                    return (index + 1) + ". " + topic.key() + " | " + topic.title() + " | " + topic.objective()
                            + " | queries=" + topic.retrievalQueries()
                            + " | tags=" + topic.coverageTags()
                            + " | pages=" + topic.sourcePageNumbers();
                })
                .collect(java.util.stream.Collectors.joining("\n"));
        String currentWholeGameUnderstanding = "summary=" + current.wholeGameUnderstanding().summary()
                + "\nconcepts=" + current.wholeGameUnderstanding().concepts()
                + "\ntopicDependencies=" + current.wholeGameUnderstanding().topicDependencies();
        return feedback + "\nCurrent complete outline (revise this structure; do not start over):\n"
                + currentTopics
                + "\nCurrent source-bound whole-game understanding (preserve or update it consistently with the revised owners):\n"
                + currentWholeGameUnderstanding
                + "\nReturn a complete replacement outline. Keep each existing learning outcome, source-page binding, "
                + "and source-language retrieval query unless moving its nested detail to its named owner. "
                + "When the feedback identifies an impossible lesson order, reorder whole topics while retaining their "
                + "coverage and evidence. Do not invent a new action, alternative, or rule relationship while separating chapters.";
    }

    private OutlineDraft organizeWithRepair(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls,
            String operationPrefix) {
        return organizeWithRepair(request, role, owner, calls, operationPrefix, "");
    }

    private OutlineDraft organizeWithRepair(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls,
            String operationPrefix,
            String initialInstruction) {
        if (hasCanonicalVisualLedger(request) && requiresHierarchicalPlanning(request)) {
            return organizeHierarchicalOutline(
                    request, role, owner, calls, operationPrefix, initialInstruction);
        }
        InvalidOutlineOutput firstFailure;
        try {
            return organizeOnce(request, role, owner, initialInstruction, calls, operationPrefix);
        } catch (InvalidOutlineOutput failure) {
            firstFailure = failure;
            log.warn("First teaching-outline model response failed: {}", failure.getMessage());
        }
        try {
            String correction = (initialInstruction.isBlank() ? "" : initialInstruction + "\n")
                    + "The previous outline failed schema or source-contract validation. "
                    + "Rebuild the complete outline. Source identifiers must copy exact terms from the rulebook's "
                    + "source language; retrievalQueries are optional hints; player-facing fields remain Simplified Chinese.\n"
                    + prompts.structuredOutputRepair();
            return organizeOnce(
                    request,
                    role,
                    owner,
                    correction,
                    calls,
                    operationPrefix + "|complete-replacement");
        } catch (RuntimeException failure) {
            log.warn("Repaired teaching-outline model response failed: {}", failure.getMessage());
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    @PreDestroy
    public void close() {
        outlineCalls.shutdownNow();
    }

    private OutlineDraft organizeOnce(
            OutlineRequest request,
            Role role,
            String owner,
            String repair,
            ModelCallExecutor calls,
            String operation) {
        if (hasCanonicalVisualLedger(request)) {
            return organizeCanonicalLedgerOnce(request, role, owner, repair, calls, operation);
        }
        return organizeLegacyOutlineOnce(request, role, owner, repair, calls, operation);
    }

    /**
     * A typed visual ledger already owns exact identifiers and page bindings, even when a page's wider inventory is
     * incomplete. Ask the model only for the semantic decisions that require it, then project those decisions back
     * onto the immutable ledger. This keeps a dense rulebook from repeating every source string several times in the
     * completion and makes source fidelity an application invariant rather than a copying task for the provider.
     */
    private OutlineDraft organizeCanonicalLedgerOnce(
            OutlineRequest request,
            Role role,
            String owner,
            String repair,
            ModelCallExecutor calls,
            String operation) {
        String sourceLedger = canonicalSourceLedger(request);
        ChatClient.ChatClientRequestSpec configuredPrompt = configuredPrompt(role, owner, 0);
        String callInput = canonicalLedgerSystemPrompt
                + "\n"
                + canonicalLedgerUserPrompt
                + "\n"
                + request.learningGoalForPrompt()
                + "\n"
                + sourceLedger
                + "\n"
                + repair;
        String content = callProvider(
                calls,
                operation,
                callInput,
                "Rulebook lesson topics organized",
                () -> configuredPrompt.system(canonicalLedgerSystemPrompt)
                        .user(user -> user.text(canonicalLedgerUserPrompt)
                                .param("learningGoal", request.learningGoalForPrompt())
                                .param("sourceLedger", sourceLedger)
                                .param("repair", repair))
                        .call()
                        .content());
        return requireValidOutlineOutput("canonical-ledger teaching outline", () -> {
            CompactOutlineDraft compact = requireExactJson(
                    "canonical-ledger teaching outline",
                    () -> parseCompactOutlineDraft(content));
            OutlineDraft outline = expandCanonicalOutline(request, compact);
            TeachingSourceCoverageContract.requireCompleteModelContract(request, outline);
            return outline;
        });
    }

    private OutlineDraft organizeLegacyOutlineOnce(
            OutlineRequest request,
            Role role,
            String owner,
            String repair,
            ModelCallExecutor calls,
            String operation) {
        ChatClient.ChatClientRequestSpec configuredPrompt = configuredPrompt(role, owner, 0);
        String callInput = outlineSystemPrompt
                + "\n"
                + outlineUserPrompt
                + "\n"
                + request.learningGoalForPrompt()
                + "\n"
                + request.pages()
                + "\n"
                + repair;
        String content = callProvider(
                calls,
                operation,
                callInput,
                "Rulebook lesson topics organized",
                () -> configuredPrompt.system(outlineSystemPrompt)
                        .user(user -> {
                            user.text(outlineUserPrompt)
                                    .param("learningGoal", request.learningGoalForPrompt())
                                    .param("pages", request.pages())
                                    .param("visualPages", request.pageImages().stream()
                                            .map(TeachingOutlineModel.PageImageInput::pageNumber)
                                            .toList())
                                    .param("repair", repair);
                            if (role == Role.VISUAL) {
                                request.pageImages().stream().map(images::prepare).forEach(image -> user.media(
                                        MimeTypeUtils.parseMimeType(image.mediaType()),
                                        new ByteArrayResource(image.content())));
                            }
                        })
                        .call()
                        .content());
        return requireValidOutlineOutput("teaching outline", () -> {
            OutlineDraft outline = requireExactJson(
                    "teaching outline",
                    () -> parseOutlineDraft(content));
            if (outline == null) throw new IllegalArgumentException("teaching outline model returned no draft");
            outline = bindLegacySourceOwnership(outline);
            TeachingSourceCoverageContract.requireCompleteSourceContract(request, outline);
            TeachingSourceCoverageContract.requireCompleteWholeGameUnderstanding(outline);
            if (outline.topics().isEmpty()
                    || outline.topics().stream().anyMatch(topic -> topic.sourcePageNumbers().isEmpty())) {
                SourceLanguageRetrievalPolicy.validate(request, outline);
            }
            return outline;
        });
    }

    /**
     * Dense typed ledgers are planned as page-owned source shards followed by one global ordering call. Every page's
     * slots stay together so related rules are classified with their shared page context. Independent page shards run
     * in bounded parallel windows; the global call still receives identifiers, roles, pages, and immutable slot
     * ownership only, so page facts never re-enter the prompt after their local unit has been formed.
     */
    private OutlineDraft organizeHierarchicalOutline(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls,
            String operationPrefix,
            String globalInstruction) {
        List<CanonicalSlotRecord> records = canonicalSlotRecords(request);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("canonical teaching ledger has no admitted source anchor");
        }
        List<List<CanonicalSlotRecord>> shards = canonicalShards(records);
        requireHierarchicalCapacity(request, shards);
        List<CompactTeachingUnitDraft> units = organizeLocalShards(
                request, role, owner, calls, operationPrefix, shards);
        return organizeHierarchicalGlobal(
                request,
                role,
                owner,
                calls,
                operationPrefix,
                List.copyOf(units),
                globalInstruction);
    }

    private List<CompactTeachingUnitDraft> organizeLocalShards(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls,
            String operationPrefix,
            List<List<CanonicalSlotRecord>> shards) {
        List<CompactTeachingUnitDraft> units = new ArrayList<>();
        for (int windowStart = 0; windowStart < shards.size(); windowStart += outlineShardParallelism) {
            int windowEnd = Math.min(windowStart + outlineShardParallelism, shards.size());
            var completed = new ExecutorCompletionService<LocalShardResult>(outlineCalls);
            List<Future<LocalShardResult>> pending = new ArrayList<>();
            for (int index = windowStart; index < windowEnd; index++) {
                int shardIndex = index;
                int shardNumber = index + 1;
                List<CanonicalSlotRecord> shard = shards.get(index);
                pending.add(completed.submit(() -> new LocalShardResult(
                        shardIndex,
                        organizeLocalShard(
                                request,
                                role,
                                owner,
                                calls,
                                operationPrefix,
                                shardNumber,
                                shard))));
            }
            Map<Integer, List<CompactTeachingUnitDraft>> unitsByShard = new LinkedHashMap<>();
            try {
                for (int completedCount = 0; completedCount < pending.size(); completedCount++) {
                    LocalShardResult shard = completed.take().get();
                    unitsByShard.put(shard.index(), shard.units());
                }
            } catch (InterruptedException interrupted) {
                pending.forEach(task -> task.cancel(true));
                Thread.currentThread().interrupt();
                throw new IllegalStateException("teaching outline shard planning was interrupted", interrupted);
            } catch (ExecutionException failed) {
                pending.forEach(task -> task.cancel(true));
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failed.getCause() instanceof Error error) throw error;
                throw new IllegalStateException("teaching outline shard planning failed", failed.getCause());
            }
            for (int index = windowStart; index < windowEnd; index++) {
                units.addAll(unitsByShard.get(index));
            }
        }
        return List.copyOf(units);
    }

    /**
     * A page shard may legitimately yield more than one teaching unit, so capacity is checked against the most
     * expensive valid ownership shape: one maximum-length unit ID per canonical slot. Doing this before the first
     * shard starts avoids spending a partial paid call graph that can never reach the required exact global output.
     */
    private static void requireHierarchicalCapacity(
            OutlineRequest request,
            List<List<CanonicalSlotRecord>> shards) {
        List<CompactTeachingUnitDraft> maximumUnits = new ArrayList<>();
        for (int shardIndex = 0; shardIndex < shards.size(); shardIndex++) {
            List<CanonicalSlotRecord> shard = shards.get(shardIndex);
            String sourceLedger = shard.stream()
                    .map(CanonicalSlotRecord::promptRecord)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            String localInput = LOCAL_OWNERSHIP_SYSTEM_PROMPT
                    + "\n"
                    + LOCAL_OWNERSHIP_USER_PROMPT
                    + "\n"
                    + request.learningGoalForPrompt()
                    + "\n"
                    + sourceLedger;
            List<CompactTeachingUnitDraft> localUnits = maximumLocalUnits(shard);
            int localInputTokens = estimateTextTokens(localInput);
            int localOutputTokens = estimateJsonTokens(new LocalOwnershipDraft(localUnits));
            if (localInputTokens > MAX_HIERARCHICAL_INPUT_TOKENS
                    || localOutputTokens + LOCAL_OWNERSHIP_OUTPUT_RESERVE_TOKENS
                            > LOCAL_OWNERSHIP_OUTPUT_TOKENS) {
                throw new OutlineCapacityExceededException(
                        "canonical source page shard exceeds the bounded ownership context");
            }
            int shardNumber = shardIndex + 1;
            localUnits.forEach(unit -> maximumUnits.add(new CompactTeachingUnitDraft(
                    "shard-" + shardNumber + "-" + unit.teachingUnitId(),
                    unit.role(),
                    unit.sourceSlotIds())));
        }

        String unitSummaries = globalUnitSummaries(request, maximumUnits);
        String sourceCatalogState = sourceCatalogState(request);
        String globalInput = GLOBAL_ORDERING_SYSTEM_PROMPT
                + "\n"
                + GLOBAL_ORDERING_USER_PROMPT
                + "\n"
                + request.learningGoalForPrompt()
                + "\n"
                + unitSummaries
                + "\n"
                + sourceCatalogState;
        int globalInputTokens = estimateTextTokens(globalInput);
        int minimumGlobalOutputTokens = estimateJsonTokens(minimumGlobalOrdering(maximumUnits));
        if (globalInputTokens > MAX_HIERARCHICAL_INPUT_TOKENS
                || minimumGlobalOutputTokens + GLOBAL_ORDERING_OUTPUT_RESERVE_TOKENS
                        > MAX_OUTLINE_COMPLETION_TOKENS) {
            throw new OutlineCapacityExceededException(
                    "canonical teaching units exceed the bounded global ordering context");
        }
    }

    private static List<CompactTeachingUnitDraft> maximumLocalUnits(List<CanonicalSlotRecord> shard) {
        List<CompactTeachingUnitDraft> units = new ArrayList<>();
        for (int index = 0; index < shard.size(); index++) {
            String ordinal = Integer.toString(index + 1);
            String localId = "u".repeat(MAX_LOCAL_TEACHING_UNIT_ID_CHARACTERS - ordinal.length()) + ordinal;
            CanonicalSourceSlot slot = shard.get(index).slot();
            units.add(new CompactTeachingUnitDraft(
                    localId,
                    slot.fixedRole() == null ? SourceCoverageRole.SUPPORTING_RULE : slot.fixedRole(),
                    List.of(slot.slotId())));
        }
        return List.copyOf(units);
    }

    private static GlobalOrderingDraft minimumGlobalOrdering(List<CompactTeachingUnitDraft> units) {
        List<String> unitIds = units.stream().map(CompactTeachingUnitDraft::teachingUnitId).toList();
        List<String> sourceSlotIds = units.isEmpty() ? List.of("source-slot") : units.getFirst().sourceSlotIds();
        return new GlobalOrderingDraft(
                "game",
                "premise",
                List.of(new GlobalTopicDraft("lesson", "objective", true, false, unitIds)),
                new CompactWholeGameUnderstandingDraft(
                        "summary",
                        List.of(new CompactGlobalConceptDraft(
                                "concept",
                                "label",
                                "explanation",
                                sourceSlotIds,
                                List.of("lesson"),
                                List.of())),
                        List.of()));
    }

    private static int estimateJsonTokens(Object value) {
        try {
            return estimateTextTokens(JSON.writeValueAsString(value));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot estimate hierarchical outline capacity", impossible);
        }
    }

    private List<CompactTeachingUnitDraft> organizeLocalShard(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls,
            String operationPrefix,
            int shardNumber,
            List<CanonicalSlotRecord> shard) {
        InvalidOutlineOutput firstFailure;
        try {
            return organizeLocalShardOnce(
                    request, role, owner, calls, operationPrefix, shardNumber, shard, "");
        } catch (InvalidOutlineOutput failure) {
            firstFailure = failure;
        }
        try {
            return organizeLocalShardOnce(
                    request,
                    role,
                    owner,
                    calls,
                    operationPrefix + "|complete-replacement",
                    shardNumber,
                    shard,
                    "The previous response failed the exact slot-ownership contract. Return a complete replacement. "
                            + structuredOutputRepair());
        } catch (RuntimeException failure) {
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    private List<CompactTeachingUnitDraft> organizeLocalShardOnce(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls,
            String operationPrefix,
            int shardNumber,
            List<CanonicalSlotRecord> shard,
            String repair) {
        String sourceLedger = shard.stream()
                .map(CanonicalSlotRecord::promptRecord)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        ChatClient.ChatClientRequestSpec configuredPrompt = configuredPrompt(role, owner, 4_000);
        String operation = operationPrefix + "|canonical-shard-" + shardNumber;
        String callInput = LOCAL_OWNERSHIP_SYSTEM_PROMPT
                + "\n"
                + LOCAL_OWNERSHIP_USER_PROMPT
                + "\n"
                + request.learningGoalForPrompt()
                + "\n"
                + sourceLedger
                + "\n"
                + repair;
        String content = callProvider(
                calls,
                operation,
                callInput,
                "Canonical source shard " + shardNumber + " assigned to teaching units",
                () -> configuredPrompt.system(LOCAL_OWNERSHIP_SYSTEM_PROMPT)
                        .user(user -> user.text(LOCAL_OWNERSHIP_USER_PROMPT)
                                .param("learningGoal", request.learningGoalForPrompt())
                                .param("sourceLedger", sourceLedger)
                                .param("repair", repair))
                        .call()
                        .content());
        return requireValidOutlineOutput("canonical source-shard ownership", () -> {
            LocalOwnershipDraft draft = requireExactJson(
                    "canonical source-shard ownership",
                    () -> parseLocalOwnershipDraft(content));
            return validateAndNamespaceLocalOwnership(shardNumber, shard, draft);
        });
    }

    private OutlineDraft organizeHierarchicalGlobal(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls,
            String operationPrefix,
            List<CompactTeachingUnitDraft> units,
            String instruction) {
        InvalidOutlineOutput firstFailure;
        try {
            return organizeHierarchicalGlobalOnce(
                    request, role, owner, calls, operationPrefix, units, instruction);
        } catch (InvalidOutlineOutput failure) {
            firstFailure = failure;
        }
        try {
            String repair = (instruction == null || instruction.isBlank() ? "" : instruction.strip() + "\n")
                    + "The previous global response failed exact unit coverage or whole-game validation. "
                    + "Return a complete replacement without changing any supplied unit or source slot. "
                    + structuredOutputRepair();
            return organizeHierarchicalGlobalOnce(
                    request,
                    role,
                    owner,
                    calls,
                    operationPrefix + "|complete-replacement",
                    units,
                    repair);
        } catch (RuntimeException failure) {
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    private OutlineDraft organizeHierarchicalGlobalOnce(
            OutlineRequest request,
            Role role,
            String owner,
            ModelCallExecutor calls,
            String operationPrefix,
            List<CompactTeachingUnitDraft> units,
            String instruction) {
        String unitSummaries = globalUnitSummaries(request, units);
        String sourceCatalogState = sourceCatalogState(request);
        String repair = instruction == null ? "" : instruction;
        ChatClient.ChatClientRequestSpec configuredPrompt = configuredPrompt(role, owner, 0);
        String callInput = GLOBAL_ORDERING_SYSTEM_PROMPT
                + "\n"
                + GLOBAL_ORDERING_USER_PROMPT
                + "\n"
                + request.learningGoalForPrompt()
                + "\n"
                + unitSummaries
                + "\n"
                + sourceCatalogState
                + "\n"
                + repair;
        String content = callProvider(
                calls,
                operationPrefix + "|canonical-global-ordering",
                callInput,
                "Source-owned teaching units ordered into a whole-game lesson",
                () -> configuredPrompt.system(GLOBAL_ORDERING_SYSTEM_PROMPT)
                        .user(user -> user.text(GLOBAL_ORDERING_USER_PROMPT)
                                .param("learningGoal", request.learningGoalForPrompt())
                                .param("teachingUnits", unitSummaries)
                                .param("sourceCatalogState", sourceCatalogState)
                                .param("repair", repair))
                        .call()
                        .content());
        return requireValidOutlineOutput("canonical global lesson ordering", () -> {
            GlobalOrderingDraft global = requireExactJson(
                    "canonical global lesson ordering",
                    () -> parseGlobalOrderingDraft(content));
            CompactOutlineDraft compact = assembleGlobalOutline(units, global);
            OutlineDraft outline = expandCanonicalOutline(request, compact);
            TeachingSourceCoverageContract.requireCompleteModelContract(request, outline);
            return outline;
        });
    }

    private String callProvider(
            ModelCallExecutor calls,
            String operation,
            String input,
            String successSummary,
            Supplier<String> providerCall) {
        RuntimeException firstTransientFailure = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_OUTLINE_ATTEMPTS; attempt++) {
            String attemptOperation = attempt == 1 ? operation : operation + "|transient-retry";
            try {
                return calls.invoke(
                        new ModelCall(
                                attemptOperation,
                                estimateTextTokens(input),
                                successSummary),
                        () -> awaitProviderCall(providerCall),
                        SpringAiTeachingOutlineModel::estimateTextTokens);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                if (attempt == MAX_TRANSIENT_OUTLINE_ATTEMPTS || !isTimeout(failure)) {
                    if (firstTransientFailure != null) failure.addSuppressed(firstTransientFailure);
                    throw failure;
                }
                firstTransientFailure = failure;
                log.warn("Teaching-outline provider call {} timed out; retrying once", operation);
            }
        }
        throw new IllegalStateException("teaching outline retry budget ended without a result");
    }

    private String awaitProviderCall(Supplier<String> providerCall) {
        var task = outlineCalls.submit(providerCall::get);
        try {
            return task.get(OUTLINE_ATTEMPT_DEADLINE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw planningTimeout(timeout);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("teaching outline interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AgentExecutionStoppedException stopped) throw stopped;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("teaching outline provider call failed", failed.getCause());
        }
    }

    static int estimateTextTokens(String value) {
        if (value == null || value.isEmpty()) return 1;
        long tokens = 0;
        int asciiRun = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint <= 0x7f) {
                asciiRun++;
                continue;
            }
            tokens += (asciiRun + 3L) / 4L;
            asciiRun = 0;
            // CJK and other BMP text commonly consume about one token per code point; supplementary symbols can
            // consume two. This is deliberately conservative when the provider tokenizer is not locally available.
            tokens += codePoint <= 0xffff ? 1 : 2;
            if (tokens >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        tokens += (asciiRun + 3L) / 4L;
        return (int) Math.max(1L, Math.min(tokens, Integer.MAX_VALUE));
    }

    private String structuredOutputRepair() {
        String repair = prompts.structuredOutputRepair();
        return repair == null ? "Return exact JSON matching the supplied schema." : repair;
    }

    static boolean requiresHierarchicalPlanning(OutlineRequest request) {
        if (!hasCanonicalVisualLedger(request)) return false;
        long typedEvidenceCharacters = request.pages().stream()
                .filter(SpringAiTeachingOutlineModel::hasCanonicalPlanningEvidence)
                .mapToLong(page -> canonicalTypedFactEvidence(page).length())
                .sum();
        return typedEvidenceCharacters > MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS;
    }

    private static List<List<CanonicalSlotRecord>> canonicalShards(List<CanonicalSlotRecord> records) {
        if (records == null || records.isEmpty()) return List.of();
        // The durable typed catalog creates one page from one bounded model completion. Keep that source boundary
        // intact here: a shard can never accumulate facts from several pages, and one dense page can never expand
        // into dozens of serial ownership calls merely because it contains many canonical slots.
        List<List<CanonicalSlotRecord>> shards = new ArrayList<>();
        List<CanonicalSlotRecord> current = new ArrayList<>();
        int currentPage = records.getFirst().slot().pageNumber();
        for (CanonicalSlotRecord record : records) {
            boolean pageChanged = !current.isEmpty() && record.slot().pageNumber() != currentPage;
            if (pageChanged) {
                shards.add(List.copyOf(current));
                current.clear();
                currentPage = record.slot().pageNumber();
            }
            current.add(record);
        }
        if (!current.isEmpty()) shards.add(List.copyOf(current));
        return List.copyOf(shards);
    }

    private static List<CompactTeachingUnitDraft> validateAndNamespaceLocalOwnership(
            int shardNumber,
            List<CanonicalSlotRecord> shard,
            LocalOwnershipDraft draft) {
        if (draft == null || draft.teachingUnits() == null || draft.teachingUnits().isEmpty()
                || draft.teachingUnits().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("canonical source shard returned no teaching units");
        }
        Map<String, CanonicalSourceSlot> expectedById = shard.stream()
                .map(CanonicalSlotRecord::slot)
                .collect(java.util.stream.Collectors.toMap(
                        CanonicalSourceSlot::slotId,
                        slot -> slot,
                        (first, duplicate) -> {
                            throw new IllegalArgumentException("canonical source shard duplicated a slot");
                        },
                        LinkedHashMap::new));
        LinkedHashSet<String> assigned = new LinkedHashSet<>();
        LinkedHashSet<String> localUnitIds = new LinkedHashSet<>();
        List<CompactTeachingUnitDraft> normalized = new ArrayList<>();
        for (CompactTeachingUnitDraft unit : draft.teachingUnits()) {
            if (unit.teachingUnitId() == null
                    || !unit.teachingUnitId().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || unit.teachingUnitId().length() > MAX_LOCAL_TEACHING_UNIT_ID_CHARACTERS
                    || !localUnitIds.add(unit.teachingUnitId())
                    || unit.role() == null
                    || unit.sourceSlotIds() == null
                    || unit.sourceSlotIds().isEmpty()) {
                throw new IllegalArgumentException("canonical source shard returned an invalid teaching unit");
            }
            List<CanonicalSourceSlot> selected = unit.sourceSlotIds().stream()
                    .map(expectedById::get)
                    .toList();
            if (selected.contains(null)
                    || selected.stream().map(CanonicalSourceSlot::availability).distinct().count() != 1
                    || selected.stream().anyMatch(slot -> slot.fixedRole() != null && slot.fixedRole() != unit.role())) {
                throw new IllegalArgumentException("canonical source shard changed a slot boundary");
            }
            for (CanonicalSourceSlot slot : selected) {
                if (!assigned.add(slot.slotId())) {
                    throw new IllegalArgumentException("canonical source shard assigned a slot more than once");
                }
            }
            normalized.add(new CompactTeachingUnitDraft(
                    "shard-" + shardNumber + "-" + unit.teachingUnitId(),
                    unit.role(),
                    List.copyOf(unit.sourceSlotIds())));
        }
        if (!assigned.equals(expectedById.keySet())) {
            throw new IllegalArgumentException("canonical source shard did not assign every supplied slot exactly once");
        }
        return List.copyOf(normalized);
    }

    private static CompactOutlineDraft assembleGlobalOutline(
            List<CompactTeachingUnitDraft> units,
            GlobalOrderingDraft global) {
        if (global == null
                || global.gameTitle() == null || global.gameTitle().isBlank()
                || global.premise() == null || global.premise().isBlank()
                || global.topics() == null || global.topics().isEmpty()
                || global.wholeGameUnderstanding() == null) {
            throw new IllegalArgumentException("canonical global ordering is incomplete");
        }
        Map<String, CompactTeachingUnitDraft> unitById = units.stream()
                .collect(java.util.stream.Collectors.toMap(
                        CompactTeachingUnitDraft::teachingUnitId,
                        unit -> unit,
                        (first, duplicate) -> {
                            throw new IllegalArgumentException("canonical teaching unit IDs are not unique");
                        },
                        LinkedHashMap::new));
        LinkedHashSet<String> assignedUnits = new LinkedHashSet<>();
        LinkedHashSet<String> topicKeys = new LinkedHashSet<>();
        List<CompactTopicDraft> topics = new ArrayList<>();
        for (GlobalTopicDraft topic : global.topics()) {
            if (topic == null
                    || topic.key() == null
                    || !topic.key().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || !topicKeys.add(topic.key())
                    || topic.objective() == null || topic.objective().isBlank()
                    || topic.teachingUnitIds() == null || topic.teachingUnitIds().isEmpty()) {
                throw new IllegalArgumentException("canonical global ordering returned an invalid topic");
            }
            List<CompactTeachingUnitDraft> selected = topic.teachingUnitIds().stream()
                    .map(unitById::get)
                    .toList();
            if (selected.contains(null)) {
                throw new IllegalArgumentException("canonical global ordering selected an unknown teaching unit");
            }
            for (CompactTeachingUnitDraft unit : selected) {
                if (!assignedUnits.add(unit.teachingUnitId())) {
                    throw new IllegalArgumentException("canonical global ordering assigned a teaching unit more than once");
                }
            }
            topics.add(new CompactTopicDraft(
                    topic.key(),
                    topic.objective(),
                    topic.required(),
                    topic.visualEvidenceRecommended(),
                    List.copyOf(selected)));
        }
        if (!assignedUnits.equals(unitById.keySet())) {
            throw new IllegalArgumentException("canonical global ordering did not assign every teaching unit exactly once");
        }
        return new CompactOutlineDraft(
                global.gameTitle(),
                global.premise(),
                List.copyOf(topics),
                global.wholeGameUnderstanding());
    }

    private static List<CompactTeachingUnitDraft> unitsFromOutline(
            OutlineRequest request,
            OutlineDraft current) {
        Map<String, CanonicalSourceSlot> canonicalById = canonicalSourceSlots(request).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CanonicalSourceSlot::slotId,
                        slot -> slot,
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        LinkedHashMap<String, List<SourceCoverageSlotDraft>> byUnit = current.sourceCoverageSlots().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        SourceCoverageSlotDraft::teachingUnitId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        LinkedHashSet<String> suppliedSlotIds = current.sourceCoverageSlots().stream()
                .map(SourceCoverageSlotDraft::slotId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!suppliedSlotIds.equals(canonicalById.keySet())) {
            throw new IllegalArgumentException("existing outline no longer matches the canonical source ledger");
        }
        List<CompactTeachingUnitDraft> units = new ArrayList<>();
        byUnit.forEach((unitId, slots) -> {
            SourceCoverageRole role = slots.getFirst().role();
            if (slots.stream().anyMatch(slot -> slot.role() != role)
                    || slots.stream().map(SourceCoverageSlotDraft::availability).distinct().count() != 1) {
                throw new IllegalArgumentException("existing teaching unit crossed a canonical source boundary");
            }
            units.add(new CompactTeachingUnitDraft(
                    unitId,
                    role,
                    slots.stream().map(SourceCoverageSlotDraft::slotId).toList()));
        });
        return List.copyOf(units);
    }

    private static String globalUnitSummaries(
            OutlineRequest request,
            List<CompactTeachingUnitDraft> units) {
        Map<String, CanonicalSourceSlot> slots = canonicalSourceSlots(request).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CanonicalSourceSlot::slotId,
                        slot -> slot,
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        return units.stream().map(unit -> {
            List<CanonicalSourceSlot> selected = unit.sourceSlotIds().stream().map(slots::get).toList();
            if (selected.contains(null)) {
                throw new IllegalArgumentException("teaching unit summary selected an unknown source slot");
            }
            return "TEACHING_UNIT_ID: " + unit.teachingUnitId()
                    + " | ROLE: " + unit.role()
                    + " | AVAILABILITY: " + selected.getFirst().availability()
                    + " | PAGES: " + selected.stream().map(CanonicalSourceSlot::pageNumber).distinct().toList()
                    + " | SOURCE_SLOT_IDS: " + unit.sourceSlotIds()
                    + " | SOURCE_IDENTIFIERS: "
                    + selected.stream().map(CanonicalSourceSlot::sourceIdentifier).toList();
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String sourceCatalogState(OutlineRequest request) {
        GlobalSourceCatalogState state = new GlobalSourceCatalogState(
                canonicalSourceSlots(request).size(),
                partialSourcePages(request),
                request.pages().stream()
                        .filter(page -> page.pageLedgerState()
                                == TeachingOutlineModel.PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE)
                        .map(page -> new UnavailablePageSlot(
                                "unavailable-page-" + page.pageNumber(),
                                page.pageNumber(),
                                page.pageLedgerState()))
                        .toList());
        try {
            return JSON.writeValueAsString(state);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize typed source catalog state", impossible);
        }
    }

    /**
     * Legacy text planning used to ask the model to repeat page bindings on source slots, topics, and global concepts.
     * Source slots are the auditable identity; derive the two projections from them so one omitted duplicate page does
     * not trigger an expensive full-outline rewrite or leave a broader unsupported chapter scope.
     */
    static OutlineDraft bindLegacySourceOwnership(OutlineDraft outline) {
        if (outline == null) return null;
        Map<String, LinkedHashSet<Integer>> pagesByTopic = new LinkedHashMap<>();
        Map<String, LinkedHashSet<Integer>> pagesByIdentifier = new LinkedHashMap<>();
        for (SourceCoverageSlotDraft slot : outline.sourceCoverageSlots()) {
            pagesByTopic.computeIfAbsent(slot.ownerTopicKey(), ignored -> new LinkedHashSet<>())
                    .addAll(slot.sourcePageNumbers());
            pagesByIdentifier.computeIfAbsent(slot.sourceIdentifier(), ignored -> new LinkedHashSet<>())
                    .addAll(slot.sourcePageNumbers());
        }
        List<TopicDraft> topics = outline.topics().stream()
                .map(topic -> new TopicDraft(
                        topic.key(),
                        topic.title(),
                        topic.objective(),
                        topic.required(),
                        topic.visualEvidenceRecommended(),
                        topic.retrievalQueries(),
                        topic.coverageTags(),
                        pagesByTopic.containsKey(topic.key())
                                ? List.copyOf(pagesByTopic.get(topic.key()))
                                : topic.sourcePageNumbers()))
                .toList();
        WholeGameUnderstandingDraft understanding = outline.wholeGameUnderstanding();
        List<GlobalConceptDraft> concepts = understanding.concepts().stream()
                .map(concept -> {
                    LinkedHashSet<Integer> sourcePages = concept.sourceIdentifiers().stream()
                            .flatMap(identifier -> pagesByIdentifier
                                    .getOrDefault(identifier, new LinkedHashSet<>())
                                    .stream())
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                    return new GlobalConceptDraft(
                            concept.conceptId(),
                            concept.label(),
                            concept.explanation(),
                            concept.sourceIdentifiers(),
                            sourcePages.isEmpty() ? concept.sourcePageNumbers() : List.copyOf(sourcePages),
                            concept.relatedTopicKeys(),
                            concept.prerequisiteConceptIds());
                })
                .toList();
        return new OutlineDraft(
                outline.gameTitle(),
                outline.premise(),
                topics,
                outline.sourceCoverageSlots(),
                outline.sourceCoverageInventoryComplete(),
                new WholeGameUnderstandingDraft(understanding.summary(), concepts, understanding.topicDependencies()));
    }

    static boolean hasCanonicalVisualLedger(OutlineRequest request) {
        return request != null && VisualSourceRuleGroupLedger.supportsTypedCanonicalOutline(request.pages());
    }

    static String canonicalSourceLedger(OutlineRequest request) {
        List<CanonicalSourceSlot> slots = canonicalSourceSlots(request);
        Map<Integer, List<CanonicalSourceSlot>> slotsByPage = slots.stream().collect(java.util.stream.Collectors.groupingBy(
                CanonicalSourceSlot::pageNumber,
                LinkedHashMap::new,
                java.util.stream.Collectors.toList()));
        Map<Integer, String> typedEvidenceByPage = canonicalTypedEvidenceByPage(request.pages());
        StringBuilder ledger = new StringBuilder();
        for (var page : request.pages()) {
            ledger.append("PAGE ").append(page.pageNumber()).append('\n');
            if (page.pageLedgerState()
                    == TeachingOutlineModel.PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE) {
                ledger.append("PAGE_LEDGER_STATE: VISUAL_EXPLICITLY_UNAVAILABLE\n")
                        .append("CANONICAL_SLOTS: unavailable (source obligations for this page are unknown)\n\n");
                continue;
            }
            ledger.append("PAGE_LEDGER_STATE: ").append(page.pageLedgerState()).append('\n');
            if (page.pageLedgerState() == TeachingOutlineModel.PageLedgerState.VISUAL_PARTIAL) {
                ledger.append("SOURCE_INVENTORY: incomplete; unlisted source obligations are unknown\n");
            } else {
                ledger.append("SOURCE_INVENTORY: complete\n");
            }
            List<CanonicalSourceSlot> pageSlots = slotsByPage.getOrDefault(page.pageNumber(), List.of());
            if (pageSlots.isEmpty()) {
                ledger.append("CANONICAL_SLOTS: none (no independently teachable rule anchor)\n");
            } else {
                ledger.append("CANONICAL_SLOTS:\n");
                for (CanonicalSourceSlot slot : pageSlots) {
                    ledger.append("- ")
                            .append(slot.slotId())
                            .append(" | availability=")
                            .append(slot.availability());
                    if (slot.fixedRole() != null) {
                        ledger.append(" | fixedRole=").append(slot.fixedRole());
                    }
                    ledger.append(" | identifier=").append(slot.sourceIdentifier()).append('\n');
                }
            }
            if (!hasCanonicalPlanningEvidence(page)) {
                ledger.append("PAGE_EVIDENCE: none (no admitted typed rule fact)\n\n");
                continue;
            }
            ledger.append("PAGE_EVIDENCE_BEGIN\n")
                    .append(typedEvidenceByPage.get(page.pageNumber()))
                    .append("\nPAGE_EVIDENCE_END\n\n");
        }
        return ledger.toString().stripTrailing();
    }

    private static Map<Integer, String> canonicalTypedEvidenceByPage(List<PageInput> pages) {
        Map<Integer, String> typedEvidenceByPage = pages.stream()
                .filter(SpringAiTeachingOutlineModel::hasCanonicalPlanningEvidence)
                .collect(java.util.stream.Collectors.toMap(
                        PageInput::pageNumber,
                        page -> canonicalPlanningEvidence(page, MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS),
                        (first, duplicate) -> {
                            throw new IllegalArgumentException("canonical teaching page numbers are not unique");
                        },
                        LinkedHashMap::new));
        int typedEvidenceCharacters = typedEvidenceByPage.values().stream().mapToInt(String::length).sum();
        if (typedEvidenceCharacters > MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS) {
            throw new IllegalArgumentException("typed rule facts exceed the canonical input budget");
        }
        return typedEvidenceByPage;
    }

    private static boolean hasCanonicalPlanningEvidence(PageInput page) {
        return (page.pageLedgerState() == TeachingOutlineModel.PageLedgerState.VISUAL_EXACT_COMPLETE
                        || page.pageLedgerState() == TeachingOutlineModel.PageLedgerState.VISUAL_PARTIAL)
                && !page.sourceRuleGroupFacts().isEmpty();
    }

    static String canonicalPlanningEvidence(PageInput page, int maximumCharacters) {
        if (page == null || maximumCharacters < 1) {
            throw new IllegalArgumentException("canonical page evidence boundary is invalid");
        }
        if (page.pageLedgerState() != TeachingOutlineModel.PageLedgerState.VISUAL_EXACT_COMPLETE
                && page.pageLedgerState() != TeachingOutlineModel.PageLedgerState.VISUAL_PARTIAL) {
            throw new IllegalArgumentException("canonical page has no admitted typed fact ledger");
        }
        if (!VisualSourceRuleGroupLedger.hasExactFactBindings(
                page.sourceRuleGroupIdentifiers(), page.sourceRuleGroupFacts())) {
            throw new IllegalArgumentException("canonical page evidence has no exact typed fact ledger");
        }
        String typedFacts = canonicalTypedFactEvidence(page);
        if (typedFacts.isBlank()) return "";
        if (typedFacts.length() > maximumCharacters) {
            throw new IllegalArgumentException("typed rule facts exceed their canonical evidence budget");
        }
        return typedFacts;
    }

    private static String canonicalTypedFactEvidence(PageInput page) {
        List<String> records = new ArrayList<>();
        for (int index = 0; index < page.sourceRuleGroupIdentifiers().size(); index++) {
            String identifier = page.sourceRuleGroupIdentifiers().get(index);
            var fact = page.sourceRuleGroupFacts().stream()
                    .filter(candidate -> identifier.equals(candidate.identifier()))
                    .findFirst()
                    .orElseThrow();
            records.add("SOURCE_SLOT: page-" + page.pageNumber() + "-rule-" + (index + 1)
                    + " | RULE_GROUP_IDENTIFIER: " + fact.identifier()
                    + " | RULE_GROUP_LABEL: " + fact.label()
                    + " | RULE_GROUP_FACT: " + fact.fact());
        }
        return String.join("\n", records);
    }

    private static List<CanonicalSourceSlot> canonicalSourceSlots(OutlineRequest request) {
        if (request == null || request.pages() == null) {
            throw new IllegalArgumentException("canonical teaching source request is required");
        }
        List<CanonicalSourceSlot> slots = new ArrayList<>();
        for (var page : request.pages()) {
            if (page.pageLedgerState()
                    == TeachingOutlineModel.PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE) {
                continue;
            }
            boolean safeTypedLedger = switch (page.pageLedgerState()) {
                case VISUAL_EXACT_COMPLETE -> VisualSourceRuleGroupLedger.hasCompleteExactFactLedger(page);
                case VISUAL_PARTIAL -> VisualSourceRuleGroupLedger.hasExactFactBindings(
                        page.sourceRuleGroupIdentifiers(), page.sourceRuleGroupFacts());
                case LEGACY_TEXT, VISUAL_EXPLICITLY_UNAVAILABLE -> false;
            };
            if (!safeTypedLedger) {
                throw new IllegalArgumentException("canonical teaching source page has no safe typed fact ledger");
            }
            for (int index = 0; index < page.sourceRuleGroupIdentifiers().size(); index++) {
                slots.add(new CanonicalSourceSlot(
                        "page-" + page.pageNumber() + "-rule-" + (index + 1),
                        page.sourceRuleGroupIdentifiers().get(index),
                        page.pageNumber(),
                        SourceCoverageAvailability.SOURCED,
                        null));
            }
            for (int dependencyIndex = 0; dependencyIndex < page.sourceDependencies().size(); dependencyIndex++) {
                var dependency = page.sourceDependencies().get(dependencyIndex);
                for (String missingTag : dependency.missingCoverageTags()) {
                    slots.add(new CanonicalSourceSlot(
                            "page-" + page.pageNumber() + "-dependency-" + (dependencyIndex + 1) + "-" + missingTag.replace('_', '-'),
                            dependency.title(),
                            page.pageNumber(),
                            SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE,
                            roleForMissingCoverage(missingTag)));
                }
            }
        }
        return List.copyOf(slots);
    }

    private static List<CanonicalSlotRecord> canonicalSlotRecords(OutlineRequest request) {
        Map<Integer, PageInput> pagesByNumber = request.pages().stream()
                .collect(java.util.stream.Collectors.toMap(
                        PageInput::pageNumber,
                        page -> page,
                        (first, duplicate) -> {
                            throw new IllegalArgumentException("canonical teaching page numbers are not unique");
                        },
                        LinkedHashMap::new));
        List<CanonicalSlotRecord> records = new ArrayList<>();
        for (CanonicalSourceSlot slot : canonicalSourceSlots(request)) {
            PageInput page = pagesByNumber.get(slot.pageNumber());
            if (page == null) {
                throw new IllegalArgumentException("canonical teaching slot references an unknown page");
            }
            StringBuilder record = new StringBuilder()
                    .append("SOURCE_SLOT_ID: ").append(slot.slotId()).append('\n')
                    .append("PAGE_NUMBER: ").append(slot.pageNumber()).append('\n')
                    .append("PAGE_LEDGER_STATE: ").append(page.pageLedgerState()).append('\n')
                    .append("SOURCE_INVENTORY: ")
                    .append(page.sourceRuleGroupInventoryComplete() ? "complete" : "incomplete")
                    .append('\n')
                    .append("AVAILABILITY: ").append(slot.availability()).append('\n')
                    .append("FIXED_ROLE: ").append(slot.fixedRole() == null ? "NONE" : slot.fixedRole()).append('\n')
                    .append("SOURCE_IDENTIFIER: ").append(slot.sourceIdentifier());
            if (slot.availability() == SourceCoverageAvailability.SOURCED) {
                var fact = page.sourceRuleGroupFacts().stream()
                        .filter(candidate -> slot.sourceIdentifier().equals(candidate.identifier()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "canonical source slot has no exact typed fact"));
                record.append('\n')
                        .append("RULE_GROUP_LABEL: ").append(fact.label()).append('\n')
                        .append("RULE_GROUP_FACT: ").append(fact.fact());
            } else {
                record.append('\n').append("RULE_GROUP_FACT: unavailable in the active source");
            }
            records.add(new CanonicalSlotRecord(slot, record.toString()));
        }
        return List.copyOf(records);
    }

    private OutlineDraft expandCanonicalOutline(OutlineRequest request, CompactOutlineDraft compact) {
        if (compact == null || compact.topics() == null || compact.topics().isEmpty()
                || compact.wholeGameUnderstanding() == null) {
            throw new IllegalArgumentException("compact teaching outline is incomplete");
        }
        List<CanonicalSourceSlot> canonicalSlots = canonicalSourceSlots(request);
        Map<String, CanonicalSourceSlot> canonicalById = canonicalSlots.stream().collect(java.util.stream.Collectors.toMap(
                CanonicalSourceSlot::slotId,
                slot -> slot,
                (first, duplicate) -> {
                    throw new IllegalArgumentException("canonical teaching source slot IDs are not unique");
                },
                LinkedHashMap::new));
        LinkedHashSet<String> assignedSlotIds = new LinkedHashSet<>();
        LinkedHashSet<String> topicKeys = new LinkedHashSet<>();
        LinkedHashSet<String> teachingUnitIds = new LinkedHashSet<>();
        List<TopicDraft> topics = new ArrayList<>();
        List<SourceCoverageSlotDraft> sourceSlots = new ArrayList<>();
        List<Integer> partialSourcePages = partialSourcePages(request);
        List<Integer> unavailableSourcePages = unavailableSourcePages(request);
        boolean partialSourcePageCatalog = !partialSourcePages.isEmpty() || !unavailableSourcePages.isEmpty();
        String premise = compact.premise();

        for (CompactTopicDraft topic : compact.topics()) {
            if (topic == null || topic.key() == null || !topic.key().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || !topicKeys.add(topic.key()) || topic.teachingUnits() == null
                    || topic.teachingUnits().isEmpty()) {
                throw new IllegalArgumentException("compact teaching topic is invalid");
            }
            List<CanonicalSourceSlot> ownedCanonicalSlots = new ArrayList<>();
            for (CompactTeachingUnitDraft unit : topic.teachingUnits()) {
                if (unit == null || unit.teachingUnitId() == null
                        || !unit.teachingUnitId().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                        || !teachingUnitIds.add(unit.teachingUnitId())
                        || unit.role() == null || unit.sourceSlotIds() == null || unit.sourceSlotIds().isEmpty()) {
                    throw new IllegalArgumentException("compact teaching unit is invalid");
                }
                List<CanonicalSourceSlot> unitSlots = unit.sourceSlotIds().stream()
                        .map(canonicalById::get)
                        .toList();
                if (unitSlots.contains(null)
                        || unitSlots.stream().map(CanonicalSourceSlot::availability).distinct().count() != 1) {
                    throw new IllegalArgumentException("compact teaching unit selected an unknown or mixed-availability source slot");
                }
                for (CanonicalSourceSlot canonical : unitSlots) {
                    if (!assignedSlotIds.add(canonical.slotId())) {
                        throw new IllegalArgumentException("compact teaching outline assigned a source slot more than once");
                    }
                    SourceCoverageRole role = canonical.fixedRole() == null ? unit.role() : canonical.fixedRole();
                    if (canonical.fixedRole() != null && canonical.fixedRole() != unit.role()) {
                        throw new IllegalArgumentException("compact teaching outline changed a missing-source role");
                    }
                    sourceSlots.add(new SourceCoverageSlotDraft(
                            canonical.slotId(),
                            role,
                            canonical.sourceIdentifier(),
                            List.of(canonical.pageNumber()),
                            topic.key(),
                            unit.teachingUnitId(),
                            canonical.availability()));
                    ownedCanonicalSlots.add(canonical);
                }
            }
            List<Integer> sourcePages = ownedCanonicalSlots.stream()
                    .map(CanonicalSourceSlot::pageNumber)
                    .distinct()
                    .toList();
            topics.add(new TopicDraft(
                    topic.key(),
                    topic.objective(),
                    topic.objective(),
                    topic.required(),
                    topic.visualEvidenceRecommended(),
                    List.of(),
                    canonicalCoverageTags(
                            ownedCanonicalSlots,
                            sourceSlots,
                            topic.key(),
                            partialSourcePageCatalog),
                    sourcePages));
        }
        if (!assignedSlotIds.equals(canonicalById.keySet())) {
            LinkedHashSet<String> missing = new LinkedHashSet<>(canonicalById.keySet());
            missing.removeAll(assignedSlotIds);
            throw new IllegalArgumentException("compact teaching outline omitted canonical source slots: " + missing);
        }

        Map<String, SourceCoverageSlotDraft> expandedSlotsById = sourceSlots.stream().collect(java.util.stream.Collectors.toMap(
                SourceCoverageSlotDraft::slotId, slot -> slot, (first, duplicate) -> first, LinkedHashMap::new));
        List<GlobalConceptDraft> concepts = canonicalConcepts(
                compact.wholeGameUnderstanding().concepts(), expandedSlotsById, topicKeys);
        OutlineDraft outline = new OutlineDraft(
                compact.gameTitle(),
                premise,
                List.copyOf(topics),
                List.copyOf(sourceSlots),
                true,
                new WholeGameUnderstandingDraft(
                        compact.wholeGameUnderstanding().summary(),
                        concepts,
                        compact.wholeGameUnderstanding().topicDependencies()));
        return outline;
    }

    private static List<String> canonicalCoverageTags(
            List<CanonicalSourceSlot> canonicalSlots,
            List<SourceCoverageSlotDraft> expandedSlots,
            String topicKey,
            boolean partialSourcePageCatalog) {
        boolean hasSourced = canonicalSlots.stream()
                .anyMatch(slot -> slot.availability() == SourceCoverageAvailability.SOURCED);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (hasSourced) {
            tags.add("source_coverage");
            expandedSlots.stream()
                    .filter(slot -> slot.ownerTopicKey().equals(topicKey))
                    .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                    .map(SourceCoverageSlotDraft::role)
                    .map(SpringAiTeachingOutlineModel::coverageTag)
                    .filter(java.util.Objects::nonNull)
                    .forEach(tags::add);
        } else {
            tags.add("source_dependency");
            expandedSlots.stream()
                    .filter(slot -> slot.ownerTopicKey().equals(topicKey))
                    .map(SourceCoverageSlotDraft::role)
                    .map(SpringAiTeachingOutlineModel::missingSourceTag)
                    .filter(java.util.Objects::nonNull)
                    .forEach(tags::add);
        }
        if (partialSourcePageCatalog) {
            tags.add(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG);
        }
        return List.copyOf(tags);
    }

    private static List<Integer> unavailableSourcePages(OutlineRequest request) {
        return request.pages().stream()
                .filter(page -> page.pageLedgerState()
                        == TeachingOutlineModel.PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE)
                .map(TeachingOutlineModel.PageInput::pageNumber)
                .distinct()
                .toList();
    }

    private static List<Integer> partialSourcePages(OutlineRequest request) {
        return request.pages().stream()
                .filter(page -> page.pageLedgerState() == TeachingOutlineModel.PageLedgerState.VISUAL_PARTIAL)
                .map(TeachingOutlineModel.PageInput::pageNumber)
                .distinct()
                .toList();
    }

    private static List<GlobalConceptDraft> canonicalConcepts(
            List<CompactGlobalConceptDraft> drafts,
            Map<String, SourceCoverageSlotDraft> slotsById,
            Set<String> topicKeys) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("compact whole-game understanding has no concepts");
        }
        List<GlobalConceptDraft> concepts = new ArrayList<>();
        for (CompactGlobalConceptDraft draft : drafts) {
            if (draft == null || draft.sourceSlotIds() == null || draft.sourceSlotIds().isEmpty()
                    || draft.relatedTopicKeys() == null
                    || draft.relatedTopicKeys().isEmpty() || !topicKeys.containsAll(draft.relatedTopicKeys())) {
                throw new IllegalArgumentException("compact whole-game concept is invalid");
            }
            List<SourceCoverageSlotDraft> selected = draft.sourceSlotIds().stream().map(slotsById::get).toList();
            if (selected.contains(null)
                    || selected.stream().anyMatch(slot -> slot.availability() != SourceCoverageAvailability.SOURCED)
                    || selected.stream().anyMatch(slot -> !draft.relatedTopicKeys().contains(slot.ownerTopicKey()))) {
                throw new IllegalArgumentException("compact whole-game concept selected an unavailable or unrelated source slot");
            }
            concepts.add(new GlobalConceptDraft(
                    draft.conceptId(),
                    draft.label(),
                    draft.explanation(),
                    selected.stream().map(SourceCoverageSlotDraft::sourceIdentifier).distinct().toList(),
                    selected.stream().flatMap(slot -> slot.sourcePageNumbers().stream()).distinct().toList(),
                    draft.relatedTopicKeys(),
                    draft.prerequisiteConceptIds()));
        }
        return List.copyOf(concepts);
    }

    private static SourceCoverageRole roleForMissingCoverage(String coverageTag) {
        return switch (coverageTag) {
            case "setup" -> SourceCoverageRole.SETUP;
            case "core_loop" -> SourceCoverageRole.CORE_LOOP;
            case "end" -> SourceCoverageRole.ENDING;
            case "scoring" -> SourceCoverageRole.SCORING;
            default -> throw new IllegalArgumentException("unknown missing teaching source role");
        };
    }

    private static String coverageTag(SourceCoverageRole role) {
        return switch (role) {
            case SETUP -> "setup";
            case CORE_LOOP -> "core_loop";
            case LEGAL_ACTION -> "legal_action";
            case ENDING -> "end";
            case SCORING -> "scoring";
            case NECESSARY_EXCEPTION -> "necessary_exception";
            case SUPPORTING_RULE -> null;
        };
    }

    private static String missingSourceTag(SourceCoverageRole role) {
        String coverage = coverageTag(role);
        return coverage == null ? null : "missing_" + coverage + "_source";
    }

    static CompactOutlineDraft parseCompactOutlineDraft(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        JsonNode topics = requireArray(root, "topics", "compact outline");
        for (JsonNode topic : topics) {
            JsonNode units = requireArray(topic, "teachingUnits", "compact outline topic");
            for (JsonNode unit : units) requireArray(unit, "sourceSlotIds", "compact teaching unit");
        }
        JsonNode understanding = requireObject(root, "wholeGameUnderstanding", "compact outline");
        JsonNode concepts = requireArray(understanding, "concepts", "compact whole-game understanding");
        requireArray(understanding, "topicDependencies", "compact whole-game understanding");
        for (JsonNode concept : concepts) requireConceptArrays(concept, "compact whole-game concept");
        rejectDuplicateArrayItems(root, "compact outline");
        return JSON.readValue(content, CompactOutlineDraft.class);
    }

    static LocalOwnershipDraft parseLocalOwnershipDraft(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        JsonNode units = requireArray(root, "teachingUnits", "canonical source-shard ownership");
        for (JsonNode unit : units) {
            requireArray(unit, "sourceSlotIds", "canonical source-shard teaching unit");
        }
        rejectDuplicateArrayItems(root, "canonical source-shard ownership");
        return JSON.readValue(content, LocalOwnershipDraft.class);
    }

    static GlobalOrderingDraft parseGlobalOrderingDraft(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        JsonNode topics = requireArray(root, "topics", "canonical global ordering");
        for (JsonNode topic : topics) {
            requireArray(topic, "teachingUnitIds", "canonical global topic");
        }
        JsonNode understanding = requireObject(root, "wholeGameUnderstanding", "canonical global ordering");
        JsonNode concepts = requireArray(
                understanding, "concepts", "canonical global whole-game understanding");
        requireArray(
                understanding, "topicDependencies", "canonical global whole-game understanding");
        for (JsonNode concept : concepts) {
            requireConceptArrays(concept, "canonical global whole-game concept");
        }
        rejectDuplicateArrayItems(root, "canonical global ordering");
        return JSON.readValue(content, GlobalOrderingDraft.class);
    }

    static OutlineDraft parseOutlineDraft(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        JsonNode topics = requireArray(root, "topics", "teaching outline");
        for (JsonNode topic : topics) {
            requireArray(topic, "retrievalQueries", "teaching outline topic");
            requireArray(topic, "coverageTags", "teaching outline topic");
            requireArray(topic, "sourcePageNumbers", "teaching outline topic");
        }
        JsonNode sourceSlots = requireArray(root, "sourceCoverageSlots", "teaching outline");
        for (JsonNode sourceSlot : sourceSlots) {
            requireArray(sourceSlot, "sourcePageNumbers", "teaching source coverage slot");
        }
        JsonNode understanding = requireObject(root, "wholeGameUnderstanding", "teaching outline");
        JsonNode concepts = requireArray(understanding, "concepts", "whole-game understanding");
        requireArray(understanding, "topicDependencies", "whole-game understanding");
        for (JsonNode concept : concepts) {
            requireArray(concept, "sourceIdentifiers", "whole-game concept");
            requireArray(concept, "sourcePageNumbers", "whole-game concept");
            requireArray(concept, "relatedTopicKeys", "whole-game concept");
            requireArray(concept, "prerequisiteConceptIds", "whole-game concept");
        }
        rejectDuplicateArrayItems(root, "teaching outline");
        return JSON.readValue(content, OutlineDraft.class);
    }

    private static <T> T requireExactJson(String contract, ExactJsonModelOutput<T> output) {
        try {
            return output.parse();
        } catch (JsonProcessingException invalid) {
            throw new InvalidOutlineOutput(contract + " returned invalid structured output", invalid);
        }
    }

    private static <T> T requireValidOutlineOutput(String contract, Supplier<T> validation) {
        try {
            return validation.get();
        } catch (InvalidOutlineOutput invalid) {
            throw invalid;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidOutlineOutput(contract + " violated its typed output contract", invalid);
        }
    }

    private static final class InvalidOutlineOutput extends RuntimeException {
        private InvalidOutlineOutput(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface ExactJsonModelOutput<T> {
        T parse() throws JsonProcessingException;
    }

    private static void requireConceptArrays(JsonNode concept, String contract) throws JsonMappingException {
        requireArray(concept, "sourceSlotIds", contract);
        requireArray(concept, "relatedTopicKeys", contract);
        requireArray(concept, "prerequisiteConceptIds", contract);
    }

    private static JsonNode requireObject(JsonNode owner, String field, String contract) throws JsonMappingException {
        if (owner == null || !owner.isObject() || !owner.has(field) || !owner.get(field).isObject()) {
            throw JsonMappingException.from(
                    (JsonParser) null, contract + " field " + field + " must be an object");
        }
        return owner.get(field);
    }

    private static JsonNode requireArray(JsonNode owner, String field, String contract) throws JsonMappingException {
        if (owner == null || !owner.isObject() || !owner.has(field) || !owner.get(field).isArray()) {
            throw JsonMappingException.from(
                    (JsonParser) null, contract + " field " + field + " must be an array");
        }
        return owner.get(field);
    }

    private static void rejectDuplicateArrayItems(JsonNode node, String path) throws JsonMappingException {
        if (node.isArray()) {
            LinkedHashSet<JsonNode> unique = new LinkedHashSet<>();
            int index = 0;
            for (JsonNode item : node) {
                if (!unique.add(item)) {
                    throw JsonMappingException.from(
                            (JsonParser) null, path + " contains a duplicate array item at index " + index);
                }
                rejectDuplicateArrayItems(item, path + "[" + index + "]");
                index++;
            }
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                rejectDuplicateArrayItems(field.getValue(), path + "." + field.getKey());
            }
        }
    }

    private record CanonicalSourceSlot(
            String slotId,
            String sourceIdentifier,
            int pageNumber,
            SourceCoverageAvailability availability,
            SourceCoverageRole fixedRole) {}

    private record CanonicalSlotRecord(CanonicalSourceSlot slot, String promptRecord) {}

    private record LocalShardResult(int index, List<CompactTeachingUnitDraft> units) {}

    record LocalOwnershipDraft(List<CompactTeachingUnitDraft> teachingUnits) {}

    record GlobalOrderingDraft(
            String gameTitle,
            String premise,
            List<GlobalTopicDraft> topics,
            CompactWholeGameUnderstandingDraft wholeGameUnderstanding) {}

    record GlobalTopicDraft(
            String key,
            String objective,
            boolean required,
            boolean visualEvidenceRecommended,
            List<String> teachingUnitIds) {}

    private record GlobalSourceCatalogState(
            int admittedSourceSlotCount,
            List<Integer> partialPageNumbers,
            List<UnavailablePageSlot> unavailablePageSlots) {}

    private record UnavailablePageSlot(
            String slotId,
            int pageNumber,
            TeachingOutlineModel.PageLedgerState state) {}

    record CompactOutlineDraft(
            String gameTitle,
            String premise,
            List<CompactTopicDraft> topics,
            CompactWholeGameUnderstandingDraft wholeGameUnderstanding) {}

    record CompactTopicDraft(
            String key,
            String objective,
            boolean required,
            boolean visualEvidenceRecommended,
            List<CompactTeachingUnitDraft> teachingUnits) {}

    record CompactTeachingUnitDraft(
            String teachingUnitId,
            SourceCoverageRole role,
            List<String> sourceSlotIds) {}

    record CompactWholeGameUnderstandingDraft(
            String summary,
            List<CompactGlobalConceptDraft> concepts,
            List<TopicDependencyDraft> topicDependencies) {}

    record CompactGlobalConceptDraft(
            String conceptId,
            String label,
            String explanation,
            List<String> sourceSlotIds,
            List<String> relatedTopicKeys,
            List<String> prerequisiteConceptIds) {}

    private ChatClient.ChatClientRequestSpec configuredPrompt(Role role, String owner, int maxTokens) {
        RuntimeModelConfiguration.ResolvedModel selected = models.resolvedModelFor(role, owner);
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(selected.model()).prompt();
        OpenAiChatOptions.Builder options = providerOptions(selected);
        if (options == null) {
            return prompt.options(ChatOptions.builder().temperature(temperature));
        }
        options.model(selected.modelName());
        if (maxTokens > 0) options.maxTokens(maxTokens);
        return prompt.options(options);
    }

    private OpenAiChatOptions.Builder providerOptions(RuntimeModelConfiguration.ResolvedModel selected) {
        if (selected.deepSeekNonThinkingGeneration()) {
            return OpenAiChatOptions.builder()
                    .temperature(temperature)
                    .maxTokens(MAX_OUTLINE_COMPLETION_TOKENS)
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                    .extraBody(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        }
        if ("qwen".equals(selected.provider())) {
            return OpenAiChatOptions.builder()
                    .temperature(temperature)
                    .maxTokens(MAX_OUTLINE_COMPLETION_TOKENS)
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                    .extraBody(java.util.Map.of("enable_thinking", false));
        }
        return null;
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
        // VisualRulebookCataloger converts required rendered pages into a bounded factual catalog before this boundary.
        // Organizing page text or that catalog is a text-planning task and must not repeat raw page-image uploads.
        return Role.TEACHING;
    }

    boolean usesFake(Role role, String owner) {
        return owner == null || owner.isBlank() ? models.usesFake(role) : models.usesFake(role, owner);
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read autonomous teaching outline prompt", failure);
        }
    }

}
