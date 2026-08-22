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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static final long OUTLINE_DEADLINE_SECONDS = 120;
    static final int MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS = 32_000;
    private static final int MAX_CANONICAL_PAGE_EVIDENCE_CHARACTERS = 2_800;

    private final RuntimeModelConfiguration models;
    private final VersionedAgentPrompts prompts;
    private final String outlineSystemPrompt;
    private final String outlineUserPrompt;
    private final String canonicalLedgerSystemPrompt;
    private final String canonicalLedgerUserPrompt;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final ExecutorService outlineCalls = Executors.newVirtualThreadPerTaskExecutor();
    private final double temperature;

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
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-user.txt")));
    }

    @Autowired
    public SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
            @Value("${rulepilot.teaching.outline-temperature:0.1}") double temperature,
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
                read(canonicalLedgerUserPrompt));
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
                read(new ClassPathResource("prompts/teaching-outline-v20-canonical-ledger-user.txt")));
    }

    SpringAiTeachingOutlineModel(
            RuntimeModelConfiguration models,
            VersionedAgentPrompts prompts,
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
        if (usesFake(role, owner)) {
            throw new OutlineGenerationException(
                    "teaching outline model is not configured",
                    new IllegalStateException("a real teaching model is required to organize a rulebook"));
        }
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

    private OutlineDraft organizeWithRepair(OutlineRequest request, Role role, String owner) {
        return organizeWithRepair(request, role, owner, "");
    }

    private OutlineDraft organizeWithRepair(OutlineRequest request, Role role, String owner, String initialInstruction) {
        RuntimeException firstFailure;
        try {
            return organizeOnce(request, role, owner, initialInstruction);
        } catch (IncompleteCanonicalSlotOwnership incompleteOwnership) {
            return repairMissingCanonicalSlotOwners(request, role, owner, incompleteOwnership);
        } catch (InvalidSourceCoverage invalidSource) {
            return repairSourceIdentifiers(request, role, owner, invalidSource);
        } catch (InvalidWholeGameUnderstanding invalidContext) {
            return repairWholeGameUnderstanding(request, role, owner, invalidContext);
        } catch (RuntimeException failure) {
            if (isTimeout(failure)) throw failure;
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

    private OutlineDraft repairMissingCanonicalSlotOwners(
            OutlineRequest request,
            Role role,
            String owner,
            IncompleteCanonicalSlotOwnership failure) {
        CompactOutlineDraft current = failure.compact;
        Map<String, CompactTeachingUnitDraft> units = current.topics().stream()
                .flatMap(topic -> topic.teachingUnits().stream())
                .collect(java.util.stream.Collectors.toMap(
                        CompactTeachingUnitDraft::teachingUnitId,
                        unit -> unit,
                        (first, duplicate) -> {
                            throw new IllegalArgumentException("compact teaching unit IDs are not unique");
                        },
                        LinkedHashMap::new));
        String frozenUnits = current.topics().stream()
                .flatMap(topic -> topic.teachingUnits().stream().map(unit ->
                        "topic=" + topic.key()
                                + " | objective=" + topic.objective()
                                + " | teachingUnitId=" + unit.teachingUnitId()
                                + " | role=" + unit.role()
                                + " | currentSourceSlotIds=" + unit.sourceSlotIds()))
                .collect(java.util.stream.Collectors.joining("\n"));
        Map<String, CanonicalSourceSlot> canonicalById = canonicalSourceSlots(request).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CanonicalSourceSlot::slotId, slot -> slot, (first, duplicate) -> first, LinkedHashMap::new));
        String missingSlots = failure.missingSlotIds.stream()
                .map(canonicalById::get)
                .map(slot -> slot.slotId()
                        + " | page=" + slot.pageNumber()
                        + " | identifier=" + slot.sourceIdentifier()
                        + " | availability=" + slot.availability())
                .collect(java.util.stream.Collectors.joining("\n"));
        LinkedHashSet<Integer> missingPages = failure.missingSlotIds.stream()
                .map(canonicalById::get)
                .map(CanonicalSourceSlot::pageNumber)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String pageEvidence = request.pages().stream()
                .filter(page -> missingPages.contains(page.pageNumber()))
                .map(page -> "PAGE " + page.pageNumber() + "\n" + page.text())
                .collect(java.util.stream.Collectors.joining("\n\n"));

        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(role, owner)).prompt();
        OpenAiChatOptions.Builder options = providerOptions(role, owner);
        if (options != null) {
            options.model(models.modelNameFor(role, owner)).maxTokens(1_200);
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder().temperature(temperature));
        }
        ChatClient.ChatClientRequestSpec configuredPrompt = prompt;
        MissingSlotOwnershipPatch patch = requireExactJson(
                "missing source-slot ownership repair",
                () -> parseMissingSlotOwnershipPatch(configuredPrompt.system("""
                        You assign only source slots omitted from an otherwise frozen teaching plan. Return one JSON
                        object with exactly assignments. Each assignment has exactly sourceSlotId and teachingUnitId.
                        Return every supplied missing sourceSlotId exactly once and no other slot. Select one existing
                        teachingUnitId whose objective and role can teach that source fact. Do not create, rename,
                        reorder, or rewrite a topic, teaching unit, concept, objective, source slot, or rule fact.
                        """)
                .user("""
                        Frozen teaching units:
                        %s

                        Missing source slots:
                        %s

                        Evidence for the missing slots:
                        %s
                        """.formatted(frozenUnits, missingSlots, pageEvidence))
                        .call()
                        .content()));
        CompactOutlineDraft repaired = applyMissingSlotOwnershipPatch(current, units, failure.missingSlotIds, patch);
        OutlineDraft outline = expandCanonicalOutline(request, repaired);
        TeachingSourceCoverageContract.requireCompleteModelContract(request, outline);
        log.info(
                "Assigned {} omitted canonical teaching source slot(s) without regenerating the outline",
                failure.missingSlotIds.size());
        return outline;
    }

    private CompactOutlineDraft applyMissingSlotOwnershipPatch(
            CompactOutlineDraft current,
            Map<String, CompactTeachingUnitDraft> units,
            List<String> missingSlotIds,
            MissingSlotOwnershipPatch patch) {
        if (patch == null || patch.assignments() == null) {
            throw new IllegalArgumentException("missing source-slot ownership repair returned no patch");
        }
        LinkedHashSet<String> expected = new LinkedHashSet<>(missingSlotIds);
        Map<String, String> ownerBySlot = patch.assignments().stream()
                .collect(java.util.stream.Collectors.toMap(
                        MissingSlotOwnershipAssignment::sourceSlotId,
                        MissingSlotOwnershipAssignment::teachingUnitId,
                        (first, duplicate) -> {
                            throw new IllegalArgumentException("missing source-slot ownership repair duplicated a slot");
                        },
                        LinkedHashMap::new));
        if (!ownerBySlot.keySet().equals(expected) || ownerBySlot.values().stream().anyMatch(owner -> !units.containsKey(owner))) {
            throw new IllegalArgumentException("missing source-slot ownership repair changed the requested boundary");
        }
        List<CompactTopicDraft> topics = current.topics().stream()
                .map(topic -> new CompactTopicDraft(
                        topic.key(),
                        topic.objective(),
                        topic.required(),
                        topic.visualEvidenceRecommended(),
                        topic.teachingUnits().stream()
                                .map(unit -> {
                                    List<String> additions = ownerBySlot.entrySet().stream()
                                            .filter(entry -> entry.getValue().equals(unit.teachingUnitId()))
                                            .map(Map.Entry::getKey)
                                            .toList();
                                    if (additions.isEmpty()) return unit;
                                    List<String> slots = new ArrayList<>(unit.sourceSlotIds());
                                    slots.addAll(additions);
                                    return new CompactTeachingUnitDraft(unit.teachingUnitId(), unit.role(), List.copyOf(slots));
                                })
                                .toList()))
                .toList();
        return new CompactOutlineDraft(
                current.gameTitle(), current.premise(), topics, current.wholeGameUnderstanding());
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
        ChatClient.ChatClientRequestSpec configuredPrompt = prompt;
        WholeGameContextPatch patch = requireExactJson(
                "whole-game context repair",
                () -> parseWholeGameContextPatch(configuredPrompt.system("""
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
                        .content()));
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
        ChatClient.ChatClientRequestSpec configuredPrompt = prompt;
        SourceIdentifierPatches patch = requireExactJson(
                "source-identifier repair",
                () -> parseSourceIdentifierPatches(configuredPrompt.system("""
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
                        .content()));
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
        ChatClient.ChatClientRequestSpec configuredPrompt = prompt;
        CompactOutlineDraft compact = requireExactJson(
                "canonical-ledger teaching outline",
                () -> parseCompactOutlineDraft(configuredPrompt.system(canonicalLedgerSystemPrompt)
                .user(user -> user.text(canonicalLedgerUserPrompt)
                        .param("learningGoal", request.learningGoalForPrompt())
                        .param("sourceLedger", canonicalSourceLedger(request))
                        .param("repair", repair))
                        .call()
                        .content()));
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
        ChatClient.ChatClientRequestSpec configuredPrompt = prompt;
        OutlineDraft outline = requireExactJson(
                "teaching outline",
                () -> parseOutlineDraft(configuredPrompt.system(outlineSystemPrompt)
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
                        .content()));
        if (outline == null) throw new IllegalArgumentException("teaching outline model returned no draft");
        outline = bindLegacySourceOwnership(outline);
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
        int remainingEvidenceCharacters = MAX_CANONICAL_LEDGER_EVIDENCE_CHARACTERS;
        int remainingPages = request.pages().size();
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
            int pageEvidenceBudget = Math.min(
                    MAX_CANONICAL_PAGE_EVIDENCE_CHARACTERS,
                    Math.max(1, remainingEvidenceCharacters / Math.max(1, remainingPages)));
            String pageEvidence = canonicalPageEvidence(page, pageEvidenceBudget);
            remainingEvidenceCharacters -= pageEvidence.length();
            remainingPages--;
            ledger.append("PAGE_EVIDENCE_BEGIN\n")
                    .append(pageEvidence)
                    .append("\nPAGE_EVIDENCE_END\n\n");
        }
        return ledger.toString().stripTrailing();
    }

    /**
     * The immutable slot ledger already preserves every auditable source identity. Planning needs the relationship
     * around those identities, not another full copy of every page. Source-centred excerpts keep middle and tail
     * rules visible while bounding the model context for long rulebooks.
     */
    static String canonicalPageEvidence(PageInput page, int maximumCharacters) {
        if (page == null || maximumCharacters < 1) {
            throw new IllegalArgumentException("canonical page evidence boundary is invalid");
        }
        String text = page.text().strip().replaceAll("[\\t\\x0B\\f\\r ]+", " ");
        if (text.length() <= maximumCharacters) return text;

        List<String> identifiers = page.sourceRuleGroupIdentifiers();
        if (identifiers.isEmpty()) return boundedAcrossPage(text, maximumCharacters);
        int separatorCharacters = Math.max(0, identifiers.size() - 1) * 5;
        int excerptBudget = Math.max(48, (maximumCharacters - separatorCharacters) / identifiers.size());
        String searchable = text.toLowerCase(Locale.ROOT);
        List<String> excerpts = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String identifier : identifiers) {
            int index = searchable.indexOf(identifier.toLowerCase(Locale.ROOT));
            if (index < 0) continue;
            int usable = Math.min(excerptBudget, maximumCharacters);
            int context = Math.max(0, usable - identifier.length());
            int start = Math.max(0, index - context / 2);
            int end = Math.min(text.length(), start + usable);
            start = Math.max(0, end - usable);
            String excerpt = text.substring(start, end).strip();
            if (!excerpt.isBlank() && seen.add(excerpt)) excerpts.add(excerpt);
        }
        if (excerpts.isEmpty()) return boundedAcrossPage(text, maximumCharacters);
        String joined = String.join("\n…\n", excerpts);
        return joined.length() <= maximumCharacters
                ? joined
                : joined.substring(0, maximumCharacters).stripTrailing();
    }

    private static String boundedAcrossPage(String text, int maximumCharacters) {
        if (text.length() <= maximumCharacters) return text;
        String gap = "\n…\n";
        if (maximumCharacters <= gap.length() * 2) return text.substring(0, maximumCharacters);
        int content = maximumCharacters - gap.length() * 2;
        int head = content / 3;
        int middle = content / 3;
        int tail = content - head - middle;
        int middleStart = Math.max(head, (text.length() - middle) / 2);
        return text.substring(0, head)
                + gap
                + text.substring(middleStart, middleStart + middle)
                + gap
                + text.substring(text.length() - tail);
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
            throw new IncompleteCanonicalSlotOwnership(compact, List.copyOf(missing));
        }

        Map<String, SourceCoverageSlotDraft> expandedSlotsById = sourceSlots.stream().collect(java.util.stream.Collectors.toMap(
                SourceCoverageSlotDraft::slotId, slot -> slot, (first, duplicate) -> first, LinkedHashMap::new));
        List<GlobalConceptDraft> concepts;
        try {
            concepts = canonicalConcepts(
                    compact.wholeGameUnderstanding().concepts(), expandedSlotsById, topicKeys);
        } catch (IllegalArgumentException invalidContext) {
            OutlineDraft sourceOwnedOutline = new OutlineDraft(
                    compact.gameTitle(),
                    compact.premise(),
                    List.copyOf(topics),
                    List.copyOf(sourceSlots),
                    true,
                    new WholeGameUnderstandingDraft(
                            compact.wholeGameUnderstanding().summary(),
                            List.of(),
                            compact.wholeGameUnderstanding().topicDependencies()));
            throw new InvalidWholeGameUnderstanding(sourceOwnedOutline, invalidContext);
        }
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

    static MissingSlotOwnershipPatch parseMissingSlotOwnershipPatch(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        requireArray(root, "assignments", "missing-slot ownership patch");
        rejectDuplicateArrayItems(root, "missing-slot ownership patch");
        return JSON.readValue(content, MissingSlotOwnershipPatch.class);
    }

    static WholeGameContextPatch parseWholeGameContextPatch(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        JsonNode concepts = requireArray(root, "concepts", "whole-game context patch");
        requireArray(root, "topicDependencies", "whole-game context patch");
        for (JsonNode concept : concepts) requireConceptArrays(concept, "whole-game context concept patch");
        rejectDuplicateArrayItems(root, "whole-game context patch");
        return JSON.readValue(content, WholeGameContextPatch.class);
    }

    static SourceIdentifierPatches parseSourceIdentifierPatches(String content) throws JsonProcessingException {
        JsonNode root = JSON.readTree(content);
        requireArray(root, "replacements", "source-identifier patch");
        rejectDuplicateArrayItems(root, "source-identifier patch");
        return JSON.readValue(content, SourceIdentifierPatches.class);
    }

    private static <T> T requireExactJson(String contract, ExactJsonModelOutput<T> output) {
        try {
            return output.parse();
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException(contract + " returned invalid structured output", invalid);
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

    record MissingSlotOwnershipPatch(List<MissingSlotOwnershipAssignment> assignments) {}

    record MissingSlotOwnershipAssignment(String sourceSlotId, String teachingUnitId) {}

    record CompactGlobalConceptDraft(
            String conceptId,
            String label,
            String explanation,
            List<String> sourceSlotIds,
            List<String> relatedTopicKeys,
            List<String> prerequisiteConceptIds) {}

    record WholeGameContextPatch(
            List<WholeGameConceptPatchDraft> concepts,
            List<TopicDependencyDraft> topicDependencies) {
        WholeGameContextPatch {
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

    record WholeGameConceptPatchDraft(
            String conceptId,
            String label,
            String explanation,
            List<String> sourceSlotIds,
            List<String> relatedTopicKeys,
            List<String> prerequisiteConceptIds) {
        WholeGameConceptPatchDraft {
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

    private static final class IncompleteCanonicalSlotOwnership extends IllegalArgumentException {
        private final CompactOutlineDraft compact;
        private final List<String> missingSlotIds;

        private IncompleteCanonicalSlotOwnership(CompactOutlineDraft compact, List<String> missingSlotIds) {
            super("compact teaching outline omitted canonical source slots: " + missingSlotIds);
            this.compact = compact;
            this.missingSlotIds = missingSlotIds;
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

    record SourceIdentifierPatches(List<SourceIdentifierPatch> replacements) {
        SourceIdentifierPatches {
            if (replacements == null || replacements.isEmpty()
                    || replacements.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("source identifier repair patch is invalid");
            }
            replacements = List.copyOf(replacements);
        }
    }

    record SourceIdentifierPatch(String slotId, String sourceIdentifier) {
        SourceIdentifierPatch {
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
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
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
