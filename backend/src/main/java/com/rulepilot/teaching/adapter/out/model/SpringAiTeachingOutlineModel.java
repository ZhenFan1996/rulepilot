package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDependencyDraft;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;
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
    private static final int MAX_OUTLINE_COMPLETION_TOKENS = 10_000;
    private static final long MAX_REPAIR_ELAPSED_NANOS = java.time.Duration.ofSeconds(30).toNanos();
    private static final long OUTLINE_DEADLINE_SECONDS = 60;

    private final RuntimeModelConfiguration models;
    private final VersionedAgentPrompts prompts;
    private final FakeTeachingOutlineModel fake;
    private final String outlineSystemPrompt;
    private final String outlineUserPrompt;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final ExecutorService outlineCalls = Executors.newVirtualThreadPerTaskExecutor();
    private final double temperature;

    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models, VersionedAgentPrompts prompts, FakeTeachingOutlineModel fake) {
        this(models, prompts, fake, 0.1);
    }

    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            FakeTeachingOutlineModel fake,
            double temperature) {
        this(
                models,
                prompts,
                fake,
                temperature,
                read(new ClassPathResource("prompts/teaching-outline-v19-autonomous-units-system.txt")),
                read(new ClassPathResource("prompts/teaching-outline-v19-user.txt")));
    }

    @Autowired
    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            FakeTeachingOutlineModel fake,
            @Value("${rulepilot.teaching.outline-temperature:0.1}") double temperature,
            @Value("classpath:prompts/teaching-outline-v19-autonomous-units-system.txt") Resource outlineSystemPrompt,
            @Value("classpath:prompts/teaching-outline-v19-user.txt") Resource outlineUserPrompt) {
        this(models, prompts, fake, temperature, read(outlineSystemPrompt), read(outlineUserPrompt));
    }

    SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            FakeTeachingOutlineModel fake,
            double temperature,
            String outlineSystemPrompt,
            String outlineUserPrompt) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("teaching outline model temperature must be between 0 and 2");
        }
        this.models = models;
        this.prompts = prompts;
        this.fake = fake;
        this.temperature = temperature;
        this.outlineSystemPrompt = outlineSystemPrompt;
        this.outlineUserPrompt = outlineUserPrompt;
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
            log.warn(
                    "Teaching-outline model exceeded {} seconds; reporting the bounded generation failure",
                    OUTLINE_DEADLINE_SECONDS);
            throw new OutlineGenerationException(
                    "teaching outline generation did not complete",
                    planningTimeout(timeout));
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("teaching outline interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AgentExecutionStoppedException stopped) throw stopped;
            throw new OutlineGenerationException(
                    "teaching outline generation returned no valid outline",
                    failed.getCause());
        }
    }

    static IllegalStateException planningTimeout(TimeoutException timeout) {
        return new IllegalStateException(
                "teaching outline timed out before a semantic lesson plan was available; retry preparation",
                timeout);
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

    private OutlineDraft organizeWithRepair(OutlineRequest request, Role role, String owner) {
        return organizeWithRepair(request, role, owner, "");
    }

    private OutlineDraft organizeWithRepair(OutlineRequest request, Role role, String owner, String initialInstruction) {
        long startedAt = System.nanoTime();
        RuntimeException firstFailure;
        try {
            return organizeOnce(request, role, owner, initialInstruction);
        } catch (InvalidSourceCoverage invalidSource) {
            return repairSourceIdentifiers(request, role, owner, invalidSource);
        } catch (InvalidWholeGameUnderstanding invalidContext) {
            return repairWholeGameUnderstanding(request, role, owner, invalidContext);
        } catch (RuntimeException failure) {
            if (isTimeout(failure) || System.nanoTime() - startedAt > MAX_REPAIR_ELAPSED_NANOS) throw failure;
            firstFailure = failure;
            log.warn("First teaching-outline model response failed: {}", failure.getMessage());
        }
        try {
            String correction = (initialInstruction.isBlank() ? "" : initialInstruction + "\n")
                    + "The previous outline failed schema or source-contract validation. "
                    + "Rebuild the complete outline. Source identifiers must copy exact terms from the rulebook's "
                    + "source language; retrievalQueries are optional hints; player-facing fields remain Simplified Chinese.\n"
                    + prompts.structuredOutputRepair();
            return organizeOnce(request, role, owner, correction);
        } catch (RuntimeException failure) {
            log.warn("Repaired teaching-outline model response failed: {}", failure.getMessage());
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    private OutlineDraft repairWholeGameUnderstanding(
            OutlineRequest request,
            Role role,
            String owner,
            InvalidWholeGameUnderstanding failure) {
        OutlineDraft current = failure.outline;
        String topicContracts = current.topics().stream()
                .map(topic -> topic.key() + " | " + topic.title() + " | " + topic.objective()
                        + " | pages=" + topic.sourcePageNumbers())
                .collect(java.util.stream.Collectors.joining("\n"));
        String sourceSlots = current.sourceCoverageSlots().stream()
                .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                .map(slot -> slot.slotId() + " | owner=" + slot.ownerTopicKey()
                        + " | sourceIdentifier=" + slot.sourceIdentifier()
                        + " | pages=" + slot.sourcePageNumbers())
                .collect(java.util.stream.Collectors.joining("\n"));
        String existingConcepts = current.wholeGameUnderstanding().concepts().stream()
                .map(concept -> concept.conceptId() + " | " + concept.label() + " | " + concept.explanation()
                        + " | sources=" + concept.sourceIdentifiers()
                        + " | related=" + concept.relatedTopicKeys()
                        + " | prerequisites=" + concept.prerequisiteConceptIds())
                .collect(java.util.stream.Collectors.joining("\n"));

        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(role, owner)).prompt();
        OpenAiChatOptions.Builder options = providerOptions(role, owner);
        if (options != null) {
            options.model(models.modelNameFor(role, owner));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder().temperature(temperature));
        }
        WholeGameContextPatch patch = prompt
                .system("""
                        You repair only the shared source-bound mental model of an otherwise valid teaching outline.
                        Return one JSON object with exactly concepts and topicDependencies. Return the complete
                        replacement concept list, not a delta. Each concept has exactly conceptId, label, explanation,
                        sourceSlotIds, relatedTopicKeys, and prerequisiteConceptIds. Select exact sourceSlotIds from
                        Exact owned source slots; never copy, shorten, or reconstruct source text. The application will
                        bind those IDs to canonical identifiers and pages. A concept's relatedTopicKeys must contain every owner of its selected
                        slots. prerequisiteConceptIds may refer only to an earlier concept in your returned list.
                        topicDependencies use exactly prerequisiteTopicKey, dependentTopicKey, and reason; include only
                        genuine teaching prerequisites and follow the supplied topic order. Use the existing mental
                        model as a draft: preserve valid distinctions and concise Simplified Chinese explanations while
                        fixing the stated validation issue. Prefer shared concepts over mirroring every slot. Do not
                        rewrite topics, objectives, source slots, chapter order, or the whole-game summary.
                        """)
                .user("""
                        Whole-game summary:
                        %s

                        Current mental-model validation issue:
                        %s

                        Existing concepts to preserve or correct:
                        %s

                        Topic contracts in final teaching order:
                        %s

                        Exact owned source slots:
                        %s
                        """.formatted(
                        current.wholeGameUnderstanding().summary(),
                        failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage(),
                        existingConcepts,
                        topicContracts,
                        sourceSlots))
                .call()
                .entity(WholeGameContextPatch.class);
        if (patch == null || patch.concepts().isEmpty()) {
            throw new IllegalArgumentException("whole-game context repair returned no concepts", failure);
        }
        List<GlobalConceptDraft> concepts = toCanonicalConcepts(current, patch.concepts());
        OutlineDraft repaired = new OutlineDraft(
                current.gameTitle(),
                current.premise(),
                current.topics(),
                current.sourceCoverageSlots(),
                current.sourceCoverageInventoryComplete(),
                new TeachingOutlineModel.WholeGameUnderstandingDraft(
                        current.wholeGameUnderstanding().summary(),
                        concepts,
                        patch.topicDependencies()));
        TeachingSourceCoverageContract.requireCompleteModelContract(request, repaired);
        SourceLanguageRetrievalPolicy.validate(request, repaired);
        return repaired;
    }

    private OutlineDraft repairSourceIdentifiers(
            OutlineRequest request,
            Role role,
            String owner,
            InvalidSourceCoverage failure) {
        OutlineDraft current = failure.outline;
        List<SourceCoverageSlotDraft> invalidSlots =
                TeachingSourceCoverageContract.missingExactSourceSlots(request, current);
        if (invalidSlots.isEmpty()) throw failure;
        LinkedHashSet<Integer> relevantPages = invalidSlots.stream()
                .flatMap(slot -> slot.sourcePageNumbers().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String slots = invalidSlots.stream()
                .map(slot -> slot.slotId() + " | currentIdentifier=" + slot.sourceIdentifier()
                        + " | pages=" + slot.sourcePageNumbers())
                .collect(java.util.stream.Collectors.joining("\n"));
        String pages = request.pages().stream()
                .filter(page -> relevantPages.contains(page.pageNumber()))
                .map(page -> "PAGE " + page.pageNumber() + "\n" + page.text())
                .collect(java.util.stream.Collectors.joining("\n\n"));

        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(role, owner)).prompt();
        OpenAiChatOptions.Builder options = providerOptions(role, owner);
        if (options != null) {
            options.model(models.modelNameFor(role, owner)).maxTokens(1_600);
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder().temperature(temperature));
        }
        SourceIdentifierPatches patch = prompt
                .system("""
                        You repair only invalid internal source anchors in an otherwise valid teaching outline.
                        Return one JSON object with exactly replacements. Each replacement has exactly slotId and
                        sourceIdentifier. Return exactly one replacement for every supplied invalid slotId and no
                        others. Keep slotId unchanged. sourceIdentifier must be one concise, exact, contiguous
                        source-language heading, named rule step, or clause copied from that slot's supplied page,
                        maximum 160 characters. Do not translate, paraphrase, shorten a word, add prose, or rewrite
                        chapters, teaching units, objectives, dependencies, or the whole-game model.
                        """)
                .user("""
                        Invalid source slots:
                        %s

                        Exact active rulebook page text:
                        %s
                        """.formatted(slots, pages))
                .call()
                .entity(SourceIdentifierPatches.class);
        OutlineDraft repaired = applySourceIdentifierPatches(current, invalidSlots, patch);
        TeachingSourceCoverageContract.requireCompleteModelContract(request, repaired);
        SourceLanguageRetrievalPolicy.validate(request, repaired);
        log.info("Repaired {} invalid teaching source identifier(s) without regenerating the outline", invalidSlots.size());
        return repaired;
    }

    private OutlineDraft applySourceIdentifierPatches(
            OutlineDraft current,
            List<SourceCoverageSlotDraft> invalidSlots,
            SourceIdentifierPatches patch) {
        if (patch == null) throw new IllegalArgumentException("source identifier repair returned no patch");
        LinkedHashSet<String> expectedIds = invalidSlots.stream()
                .map(SourceCoverageSlotDraft::slotId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> replacements = patch.replacements().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SourceIdentifierPatch::slotId,
                        SourceIdentifierPatch::sourceIdentifier,
                        (first, duplicate) -> {
                            throw new IllegalArgumentException("source identifier repair duplicated a slot ID");
                        },
                        LinkedHashMap::new));
        if (!replacements.keySet().equals(expectedIds)) {
            throw new IllegalArgumentException("source identifier repair changed the requested slot set");
        }
        Map<String, String> conceptIdentifierReplacements = new LinkedHashMap<>();
        invalidSlots.forEach(slot -> {
            String replacement = replacements.get(slot.slotId());
            String previous = conceptIdentifierReplacements.putIfAbsent(slot.sourceIdentifier(), replacement);
            if (previous != null && !previous.equals(replacement)) {
                throw new IllegalArgumentException("source identifier repair is ambiguous across concept bindings");
            }
        });
        List<SourceCoverageSlotDraft> sourceSlots = current.sourceCoverageSlots().stream()
                .map(slot -> replacements.containsKey(slot.slotId())
                        ? new SourceCoverageSlotDraft(
                                slot.slotId(),
                                slot.role(),
                                replacements.get(slot.slotId()),
                                slot.sourcePageNumbers(),
                                slot.ownerTopicKey(),
                                slot.teachingUnitId(),
                                slot.availability())
                        : slot)
                .toList();
        List<GlobalConceptDraft> concepts = current.wholeGameUnderstanding().concepts().stream()
                .map(concept -> new GlobalConceptDraft(
                        concept.conceptId(),
                        concept.label(),
                        concept.explanation(),
                        concept.sourceIdentifiers().stream()
                                .map(identifier -> conceptIdentifierReplacements.getOrDefault(identifier, identifier))
                                .toList(),
                        concept.sourcePageNumbers(),
                        concept.relatedTopicKeys(),
                        concept.prerequisiteConceptIds()))
                .toList();
        return new OutlineDraft(
                current.gameTitle(),
                current.premise(),
                current.topics(),
                sourceSlots,
                current.sourceCoverageInventoryComplete(),
                new TeachingOutlineModel.WholeGameUnderstandingDraft(
                        current.wholeGameUnderstanding().summary(),
                        concepts,
                        current.wholeGameUnderstanding().topicDependencies()));
    }

    private List<GlobalConceptDraft> toCanonicalConcepts(
            OutlineDraft current, List<WholeGameConceptPatchDraft> patchConcepts) {
        Map<String, SourceCoverageSlotDraft> slotsById = current.sourceCoverageSlots().stream()
                .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                .collect(java.util.stream.Collectors.toMap(
                        SourceCoverageSlotDraft::slotId,
                        slot -> slot,
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        List<GlobalConceptDraft> canonical = new ArrayList<>();
        for (WholeGameConceptPatchDraft concept : patchConcepts) {
            List<SourceCoverageSlotDraft> selectedSlots = concept.sourceSlotIds().stream()
                    .map(slotsById::get)
                    .toList();
            if (selectedSlots.contains(null)
                    || selectedSlots.stream().anyMatch(slot ->
                            !concept.relatedTopicKeys().contains(slot.ownerTopicKey()))) {
                throw new IllegalArgumentException(
                        "whole-game connection repair selected a slot outside its related chapters");
            }
            LinkedHashSet<String> identifiers = selectedSlots.stream()
                    .map(SourceCoverageSlotDraft::sourceIdentifier)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            LinkedHashSet<Integer> pages = selectedSlots.stream()
                    .flatMap(slot -> slot.sourcePageNumbers().stream())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            canonical.add(new GlobalConceptDraft(
                    concept.conceptId(),
                    concept.label(),
                    concept.explanation(),
                    List.copyOf(identifiers),
                    List.copyOf(pages),
                    concept.relatedTopicKeys(),
                    concept.prerequisiteConceptIds()));
        }
        return List.copyOf(canonical);
    }

    @PreDestroy
    public void close() {
        outlineCalls.shutdownNow();
    }

    private OutlineDraft organizeOnce(OutlineRequest request, Role role, String owner, String repair) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(role, owner)).prompt();
        OpenAiChatOptions.Builder options = providerOptions(role, owner);
        if (options != null) {
            options.model(models.modelNameFor(role, owner));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(temperature));
        }
        OutlineDraft outline = prompt
                .system(outlineSystemPrompt)
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
                                MimeTypeUtils.parseMimeType(image.mediaType()), new ByteArrayResource(image.content())));
                    }
        })
                .call()
                .entity(outlineConverter());
        if (outline == null) throw new IllegalArgumentException("teaching outline model returned no draft");
        try {
            TeachingSourceCoverageContract.requireCompleteSourceContract(request, outline);
        } catch (TeachingSourceCoverageContract.MissingExactSourceIdentifierException invalidSource) {
            throw new InvalidSourceCoverage(outline, invalidSource);
        }
        try {
            TeachingSourceCoverageContract.requireCompleteWholeGameUnderstanding(outline);
        } catch (IllegalArgumentException invalidContext) {
            throw new InvalidWholeGameUnderstanding(outline, invalidContext);
        }
        if (!outline.topics().isEmpty()
                && outline.topics().stream().allMatch(topic -> !topic.sourcePageNumbers().isEmpty())) {
            return outline;
        }
        SourceLanguageRetrievalPolicy.validate(request, outline);
        return outline;
    }

    private StructuredOutputConverter<OutlineDraft> outlineConverter() {
        BeanOutputConverter<OutlineDraft> delegate = new BeanOutputConverter<>(OutlineDraft.class);
        return new StructuredOutputConverter<>() {
            @Override
            public String getFormat() {
                return delegate.getFormat();
            }

            @Override
            public OutlineDraft convert(String source) {
                try {
                    return delegate.convert(source);
                } catch (RuntimeException originalFailure) {
                    String closed = closeTruncatedRootObject(source);
                    if (closed.equals(source)) throw originalFailure;
                    try {
                        OutlineDraft repaired = delegate.convert(closed);
                        log.info("Accepted teaching outline after closing its single truncated root JSON object");
                        return repaired;
                    } catch (RuntimeException stillInvalid) {
                        stillInvalid.addSuppressed(originalFailure);
                        throw stillInvalid;
                    }
                }
            }
        };
    }

    static String closeTruncatedRootObject(String source) {
        if (source == null) return "";
        String candidate = source.stripTrailing();
        if (candidate.isEmpty() || candidate.charAt(0) != '{') return source;
        java.util.ArrayDeque<Character> expectedClosers = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                expectedClosers.push('}');
            } else if (character == '[') {
                expectedClosers.push(']');
            } else if (character == '}' || character == ']') {
                if (expectedClosers.isEmpty() || expectedClosers.pop() != character) return source;
            }
        }
        if (inString || escaped || expectedClosers.size() != 1 || expectedClosers.peek() != '}') return source;
        return candidate + '}';
    }

    private record WholeGameContextPatch(
            List<WholeGameConceptPatchDraft> concepts,
            List<TopicDependencyDraft> topicDependencies) {
        private WholeGameContextPatch {
            if (concepts == null || concepts.isEmpty() || concepts.size() > 32
                    || concepts.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("whole-game context concept patch is invalid");
            }
            concepts = List.copyOf(concepts);
            topicDependencies = topicDependencies == null ? List.of() : List.copyOf(topicDependencies);
            if (topicDependencies.size() > 32 || topicDependencies.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("whole-game context dependency patch is invalid");
            }
        }
    }

    private record WholeGameConceptPatchDraft(
            String conceptId,
            String label,
            String explanation,
            List<String> sourceSlotIds,
            List<String> relatedTopicKeys,
            List<String> prerequisiteConceptIds) {
        private WholeGameConceptPatchDraft {
            if (conceptId == null || conceptId.isBlank() || conceptId.length() > 80
                    || !conceptId.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || label == null || label.isBlank() || label.length() > 160
                    || explanation == null || explanation.isBlank() || explanation.length() > 800
                    || sourceSlotIds == null || sourceSlotIds.isEmpty() || sourceSlotIds.size() > 16
                    || sourceSlotIds.stream().anyMatch(slotId -> slotId == null || slotId.isBlank())
                    || relatedTopicKeys == null || relatedTopicKeys.isEmpty() || relatedTopicKeys.size() > 16
                    || relatedTopicKeys.stream().anyMatch(topicKey -> topicKey == null || topicKey.isBlank())
                    || prerequisiteConceptIds == null || prerequisiteConceptIds.size() > 16
                    || prerequisiteConceptIds.stream().anyMatch(concept -> concept == null || concept.isBlank())) {
                throw new IllegalArgumentException("whole-game concept connection patch is invalid");
            }
            sourceSlotIds = sourceSlotIds.stream().distinct().toList();
            relatedTopicKeys = relatedTopicKeys.stream().distinct().toList();
            prerequisiteConceptIds = prerequisiteConceptIds.stream().distinct().toList();
        }
    }

    private static final class InvalidWholeGameUnderstanding extends IllegalArgumentException {
        private final OutlineDraft outline;

        private InvalidWholeGameUnderstanding(OutlineDraft outline, IllegalArgumentException cause) {
            super("whole-game understanding failed its source-bound contract", cause);
            this.outline = outline;
        }
    }

    private static final class InvalidSourceCoverage extends IllegalArgumentException {
        private final OutlineDraft outline;

        private InvalidSourceCoverage(
                OutlineDraft outline,
                TeachingSourceCoverageContract.MissingExactSourceIdentifierException cause) {
            super("teaching source identifiers failed their exact source contract", cause);
            this.outline = outline;
        }
    }

    private record SourceIdentifierPatches(List<SourceIdentifierPatch> replacements) {
        private SourceIdentifierPatches {
            if (replacements == null || replacements.isEmpty() || replacements.size() > 128
                    || replacements.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("source identifier repair patch is invalid");
            }
            replacements = List.copyOf(replacements);
        }
    }

    private record SourceIdentifierPatch(String slotId, String sourceIdentifier) {
        private SourceIdentifierPatch {
            if (slotId == null || slotId.isBlank() || slotId.length() > 80
                    || sourceIdentifier == null || sourceIdentifier.isBlank() || sourceIdentifier.length() > 160) {
                throw new IllegalArgumentException("source identifier repair entry is invalid");
            }
            slotId = slotId.strip();
            sourceIdentifier = sourceIdentifier.strip();
        }
    }

    OpenAiChatOptions.Builder providerOptions(Role role, String owner) {
        if (models.usesDeepSeekNonThinkingGeneration(role, owner)) {
            return OpenAiChatOptions.builder()
                    .temperature(temperature)
                    .maxTokens(MAX_OUTLINE_COMPLETION_TOKENS)
                    .extraBody(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        }
        if (usesQwen(role, owner)) {
            return OpenAiChatOptions.builder()
                    .temperature(temperature)
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
