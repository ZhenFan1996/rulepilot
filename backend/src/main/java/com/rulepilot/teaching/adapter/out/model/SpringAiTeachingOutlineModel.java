package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
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
    private final String canonicalLedgerSystemPrompt;
    private final String canonicalLedgerUserPrompt;
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
                read(new ClassPathResource("prompts/teaching-outline-v19-user.txt")),
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-system.txt")),
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-user.txt")));
    }

    @Autowired
    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            FakeTeachingOutlineModel fake,
            @Value("${rulepilot.teaching.outline-temperature:0.1}") double temperature,
            @Value("classpath:prompts/teaching-outline-v19-autonomous-units-system.txt") Resource outlineSystemPrompt,
            @Value("classpath:prompts/teaching-outline-v19-user.txt") Resource outlineUserPrompt,
            @Value("classpath:prompts/teaching-outline-v20-canonical-ledger-system.txt") Resource canonicalLedgerSystemPrompt,
            @Value("classpath:prompts/teaching-outline-v20-canonical-ledger-user.txt") Resource canonicalLedgerUserPrompt) {
        this(
                models,
                prompts,
                fake,
                temperature,
                read(outlineSystemPrompt),
                read(outlineUserPrompt),
                read(canonicalLedgerSystemPrompt),
                read(canonicalLedgerUserPrompt));
    }

    SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            FakeTeachingOutlineModel fake,
            double temperature,
            String outlineSystemPrompt,
            String outlineUserPrompt) {
        this(
                models,
                prompts,
                fake,
                temperature,
                outlineSystemPrompt,
                outlineUserPrompt,
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-system.txt")),
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-user.txt")));
    }

    SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            FakeTeachingOutlineModel fake,
            double temperature,
            String outlineSystemPrompt,
            String outlineUserPrompt,
            String canonicalLedgerSystemPrompt,
            String canonicalLedgerUserPrompt) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("teaching outline model temperature must be between 0 and 2");
        }
        this.models = models;
        this.prompts = prompts;
        this.fake = fake;
        this.temperature = temperature;
        this.outlineSystemPrompt = outlineSystemPrompt;
        this.outlineUserPrompt = outlineUserPrompt;
        this.canonicalLedgerSystemPrompt = canonicalLedgerSystemPrompt;
        this.canonicalLedgerUserPrompt = canonicalLedgerUserPrompt;
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
            if (!unownedExactConceptSources(request, invalidContext.outline).isEmpty()) {
                return repairConceptSourceOwnership(request, role, owner, invalidContext);
            }
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

    private OutlineDraft repairConceptSourceOwnership(
            OutlineRequest request,
            Role role,
            String owner,
            InvalidWholeGameUnderstanding failure) {
        OutlineDraft current = failure.outline;
        String missingBindings = unownedExactConceptSources(request, current).stream()
                .map(binding -> "concept=" + binding.conceptId()
                        + " | sourceIdentifier=" + binding.sourceIdentifier()
                        + " | pages=" + binding.sourcePageNumbers()
                        + " | relatedTopics=" + binding.relatedTopicKeys())
                .collect(java.util.stream.Collectors.joining("\n"));
        String feedback = """
                The shared whole-game understanding found exact rulebook sources that the chapter/source ledger did
                not assign to any plan-owned teaching unit. This is a source-ownership gap, not a prose or concept
                wording problem. Preserve the valid mental model and existing coverage. Add each listed exact source
                to an Agent-chosen teaching unit and appropriate chapter; create or reorder a chapter only when that
                is the clearest teaching structure. Do not drop the concept merely to satisfy the contract. Do not
                invent or rewrite source identifiers.
                Missing plan-owned concept sources:
                """ + missingBindings;
        return organizeOnce(request, role, owner, ownershipRefinementInstruction(current, feedback));
    }

    private List<UnownedConceptSource> unownedExactConceptSources(
            OutlineRequest request, OutlineDraft outline) {
        if (request == null || outline == null || outline.wholeGameUnderstanding() == null) return List.of();
        List<UnownedConceptSource> result = new ArrayList<>();
        for (GlobalConceptDraft concept : outline.wholeGameUnderstanding().concepts()) {
            for (String identifier : concept.sourceIdentifiers()) {
                boolean owned = outline.sourceCoverageSlots().stream()
                        .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                        .anyMatch(slot -> sourceIdentity(slot.sourceIdentifier()).equals(sourceIdentity(identifier)));
                if (!owned && exactSourceExists(request, concept.sourcePageNumbers(), identifier)) {
                    result.add(new UnownedConceptSource(
                            concept.conceptId(), identifier, concept.sourcePageNumbers(), concept.relatedTopicKeys()));
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean exactSourceExists(OutlineRequest request, List<Integer> sourcePages, String identifier) {
        String expected = sourceIdentity(identifier);
        return !expected.isBlank() && request.pages().stream()
                .filter(page -> sourcePages.contains(page.pageNumber()))
                .map(page -> sourceIdentity(page.text()))
                .anyMatch(page -> page.contains(expected));
    }

    private String sourceIdentity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
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
        if (hasCanonicalVisualLedger(request)) {
            return organizeCanonicalLedgerOnce(request, role, owner, repair);
        }
        return organizeLegacyOutlineOnce(request, role, owner, repair);
    }

    /**
     * A complete visual ledger already owns exact identifiers and page bindings. Ask the model only for the semantic
     * decisions that actually require it, then project those decisions back onto the immutable ledger. This keeps a
     * dense rulebook from repeating every source string several times in the completion and makes source fidelity an
     * application invariant rather than a copying task for the provider.
     */
    private OutlineDraft organizeCanonicalLedgerOnce(
            OutlineRequest request, Role role, String owner, String repair) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(role, owner)).prompt();
        OpenAiChatOptions.Builder options = providerOptions(role, owner);
        if (options != null) {
            options.model(models.modelNameFor(role, owner));
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder().temperature(temperature));
        }
        CompactOutlineDraft compact = prompt
                .system(canonicalLedgerSystemPrompt)
                .user(user -> user.text(canonicalLedgerUserPrompt)
                        .param("learningGoal", request.learningGoalForPrompt())
                        .param("sourceLedger", canonicalSourceLedger(request))
                        .param("repair", repair))
                .call()
                .entity(compactOutlineConverter());
        OutlineDraft outline = expandCanonicalOutline(request, compact);
        TeachingSourceCoverageContract.requireCompleteModelContract(request, outline);
        return outline;
    }

    private OutlineDraft organizeLegacyOutlineOnce(
            OutlineRequest request, Role role, String owner, String repair) {
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

    private boolean hasCanonicalVisualLedger(OutlineRequest request) {
        return request != null
                && !request.pages().isEmpty()
                && request.pages().stream().allMatch(VisualSourceRuleGroupLedger::hasCompleteExactFactLedger)
                && request.pages().stream().anyMatch(page -> !page.sourceRuleGroupIdentifiers().isEmpty());
    }

    static String canonicalSourceLedger(OutlineRequest request) {
        List<CanonicalSourceSlot> slots = canonicalSourceSlots(request);
        Map<Integer, List<CanonicalSourceSlot>> slotsByPage = slots.stream().collect(java.util.stream.Collectors.groupingBy(
                CanonicalSourceSlot::pageNumber,
                LinkedHashMap::new,
                java.util.stream.Collectors.toList()));
        StringBuilder ledger = new StringBuilder();
        for (var page : request.pages()) {
            ledger.append("PAGE ").append(page.pageNumber()).append('\n');
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
            ledger.append("PAGE_EVIDENCE_BEGIN\n")
                    .append(page.text())
                    .append("\nPAGE_EVIDENCE_END\n\n");
        }
        return ledger.toString().stripTrailing();
    }

    private static List<CanonicalSourceSlot> canonicalSourceSlots(OutlineRequest request) {
        if (request == null || request.pages() == null) {
            throw new IllegalArgumentException("canonical teaching source request is required");
        }
        List<CanonicalSourceSlot> slots = new ArrayList<>();
        for (var page : request.pages()) {
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
                    canonicalCoverageTags(ownedCanonicalSlots, sourceSlots, topic.key()),
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
                compact.premise(),
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
            String topicKey) {
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
        return List.copyOf(tags);
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

    private StructuredOutputConverter<CompactOutlineDraft> compactOutlineConverter() {
        BeanOutputConverter<CompactOutlineDraft> delegate = new BeanOutputConverter<>(CompactOutlineDraft.class);
        return new StructuredOutputConverter<>() {
            @Override
            public String getFormat() {
                return delegate.getFormat();
            }

            @Override
            public CompactOutlineDraft convert(String source) {
                try {
                    return delegate.convert(source);
                } catch (RuntimeException originalFailure) {
                    String closed = closeTruncatedRootObject(source);
                    if (closed.equals(source)) throw originalFailure;
                    try {
                        CompactOutlineDraft repaired = delegate.convert(closed);
                        log.info("Accepted compact teaching outline after closing its single truncated root JSON object");
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

    private record CanonicalSourceSlot(
            String slotId,
            String sourceIdentifier,
            int pageNumber,
            SourceCoverageAvailability availability,
            SourceCoverageRole fixedRole) {}

    private record CompactOutlineDraft(
            String gameTitle,
            String premise,
            List<CompactTopicDraft> topics,
            CompactWholeGameUnderstandingDraft wholeGameUnderstanding) {}

    private record CompactTopicDraft(
            String key,
            String objective,
            boolean required,
            boolean visualEvidenceRecommended,
            List<CompactTeachingUnitDraft> teachingUnits) {}

    private record CompactTeachingUnitDraft(
            String teachingUnitId,
            SourceCoverageRole role,
            List<String> sourceSlotIds) {}

    private record CompactWholeGameUnderstandingDraft(
            String summary,
            List<CompactGlobalConceptDraft> concepts,
            List<TopicDependencyDraft> topicDependencies) {}

    private record CompactGlobalConceptDraft(
            String conceptId,
            String label,
            String explanation,
            List<String> sourceSlotIds,
            List<String> relatedTopicKeys,
            List<String> prerequisiteConceptIds) {}

    private record WholeGameContextPatch(
            List<WholeGameConceptPatchDraft> concepts,
            List<TopicDependencyDraft> topicDependencies) {
        private WholeGameContextPatch {
            if (concepts == null || concepts.isEmpty()
                    || concepts.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("whole-game context concept patch is invalid");
            }
            concepts = List.copyOf(concepts);
            topicDependencies = topicDependencies == null ? List.of() : List.copyOf(topicDependencies);
            if (topicDependencies.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("whole-game context dependency patch is invalid");
            }
        }
    }

    private record UnownedConceptSource(
            String conceptId,
            String sourceIdentifier,
            List<Integer> sourcePageNumbers,
            List<String> relatedTopicKeys) {}

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
                    || explanation == null || explanation.isBlank()
                    || sourceSlotIds == null || sourceSlotIds.isEmpty()
                    || sourceSlotIds.stream().anyMatch(slotId -> slotId == null || slotId.isBlank())
                    || relatedTopicKeys == null || relatedTopicKeys.isEmpty()
                    || relatedTopicKeys.stream().anyMatch(topicKey -> topicKey == null || topicKey.isBlank())
                    || prerequisiteConceptIds == null
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
            if (replacements == null || replacements.isEmpty()
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
