package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.VisualSourceRuleGroupLedger;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates the source-owned obligations that must close before a lesson may claim completeness. */
public final class TeachingSourceCoverageContract {

    public static final String CONTRACT_VERSION_TAG = "source_contract_v1";
    static final String COMPLETE_INVENTORY_TAG = "source_contract_inventory_complete";
    static final String INCOMPLETE_INVENTORY_TAG = "source_contract_inventory_incomplete";
    static final String UNSOURCED_TAG = "source_contract_unsourced";
    private static final String ROLE_TAG_PREFIX = "source_contract_role_";
    private static final Set<SourceCoverageRole> CORE_ROLES = EnumSet.of(
            SourceCoverageRole.SETUP,
            SourceCoverageRole.CORE_LOOP,
            SourceCoverageRole.ENDING,
            SourceCoverageRole.SCORING);

    private TeachingSourceCoverageContract() {}

    public static void requireCompleteModelContract(OutlineRequest request, OutlineDraft outline) {
        if (outline == null || !outline.sourceCoverageInventoryComplete()) {
            throw new IllegalArgumentException("teaching outline did not return a complete source coverage inventory");
        }
        validateAgainstSources(request, outline);
    }

    public static void validateAgainstSources(OutlineRequest request, OutlineDraft outline) {
        if (request == null) throw new IllegalArgumentException("teaching source coverage request is required");
        validateStructure(outline);
        if (!declaresContract(outline)) return;

        Map<Integer, PageInput> pages = request.pages().stream().collect(Collectors.toMap(
                PageInput::pageNumber,
                page -> page,
                (first, ignored) -> first,
                LinkedHashMap::new));
        for (SourceCoverageSlotDraft slot : outline.sourceCoverageSlots()) {
            if (slot.sourcePageNumbers().stream().anyMatch(page -> !pages.containsKey(page))) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot references an unknown source page: " + slot.slotId());
            }
            if (slot.availability() == SourceCoverageAvailability.SOURCED
                    && slot.sourcePageNumbers().stream()
                            .map(pages::get)
                            .noneMatch(page -> containsSourceIdentifier(page, slot.sourceIdentifier()))) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot has no exact source identifier: " + slot.slotId());
            }
            if (slot.availability() == SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE
                    && !matchesMissingExternalSource(slot, pages)) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot has no matching external source dependency: " + slot.slotId());
            }
        }
        for (PageInput page : request.pages()) {
            if (!page.sourceRuleGroupInventoryComplete()) continue;
            for (String identifier : page.sourceRuleGroupIdentifiers()) {
                long owners = outline.sourceCoverageSlots().stream()
                        .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                        .filter(slot -> slot.sourcePageNumbers().contains(page.pageNumber()))
                        .filter(slot -> identity(slot.sourceIdentifier()).equals(identity(identifier)))
                        .count();
                if (owners == 0) {
                    throw new IllegalArgumentException(
                            "teaching source coverage contract omitted source rule group on page "
                                    + page.pageNumber() + ": " + identifier);
                }
                if (owners > 1) {
                    throw new IllegalArgumentException(
                            "teaching source rule group has multiple chapter owners on page "
                                    + page.pageNumber() + ": " + identifier);
                }
            }
        }
    }

    static void validateStructure(OutlineDraft outline) {
        if (outline == null) throw new IllegalArgumentException("teaching source coverage outline is required");
        if (!declaresContract(outline)) return;
        if (outline.sourceCoverageSlots().isEmpty()) {
            throw new IllegalArgumentException("teaching source coverage inventory is empty");
        }

        Map<String, TopicDraft> topics = new LinkedHashMap<>();
        for (TopicDraft topic : outline.topics()) {
            if (topic.key().isBlank() || topics.putIfAbsent(topic.key(), topic) != null) {
                throw new IllegalArgumentException(
                        "an explicit teaching source contract requires unique non-empty topic keys");
            }
        }
        Set<String> slotIds = new LinkedHashSet<>();
        Map<SourceAnchor, String> owners = new LinkedHashMap<>();
        for (SourceCoverageSlotDraft slot : outline.sourceCoverageSlots()) {
            if (!slotIds.add(identity(slot.slotId()))) {
                throw new IllegalArgumentException("teaching source coverage slot IDs must be unique");
            }
            TopicDraft owner = topics.get(slot.ownerTopicKey());
            if (owner == null) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot has no chapter owner: " + slot.slotId());
            }
            Set<String> ownerTags = tags(owner);
            if (owner.retrievalQueries().stream()
                    .map(TeachingSourceCoverageContract::identity)
                    .noneMatch(identity(slot.sourceIdentifier())::equals)) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot is absent from its owner's retrieval contract: "
                                + slot.slotId());
            }
            if (!owner.sourcePageNumbers().containsAll(slot.sourcePageNumbers())) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot exceeds its owner's source pages: " + slot.slotId());
            }
            validateOwnerRole(slot, owner, ownerTags);
            if (slot.availability() == SourceCoverageAvailability.SOURCED) {
                for (Integer page : slot.sourcePageNumbers()) {
                    SourceAnchor anchor = new SourceAnchor(page, identity(slot.sourceIdentifier()));
                    String previousOwner = owners.putIfAbsent(anchor, slot.ownerTopicKey());
                    if (previousOwner != null) {
                        throw new IllegalArgumentException(
                                "teaching source coverage slot has multiple chapter owners: "
                                        + slot.sourceIdentifier());
                    }
                }
            }
        }
        for (TopicDraft topic : outline.topics()) {
            if (tags(topic).contains("source_dependency")) continue;
            Set<String> ownedIdentifiers = outline.sourceCoverageSlots().stream()
                    .filter(slot -> slot.ownerTopicKey().equals(topic.key()))
                    .map(SourceCoverageSlotDraft::sourceIdentifier)
                    .map(TeachingSourceCoverageContract::identity)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (ownedIdentifiers.isEmpty()) continue;
            Set<String> retrievalIdentifiers = topic.retrievalQueries().stream()
                    .map(TeachingSourceCoverageContract::identity)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!retrievalIdentifiers.equals(ownedIdentifiers)) {
                throw new IllegalArgumentException(
                        "a source contract owner must retrieve exactly its owned source slots: " + topic.key());
            }
        }

        for (SourceCoverageRole role : CORE_ROLES) {
            if (outline.sourceCoverageSlots().stream().noneMatch(slot -> slot.role() == role)) {
                throw new IllegalArgumentException(
                        "teaching source coverage contract omitted required role " + role);
            }
        }
        if (outline.sourceCoverageInventoryComplete()
                && outline.sourceCoverageSlots().stream()
                        .anyMatch(slot -> slot.availability() == SourceCoverageAvailability.UNRESOLVED)) {
            throw new IllegalArgumentException(
                    "a complete teaching source coverage inventory cannot contain unresolved slots");
        }
        for (TopicDraft topic : outline.topics()) {
            Set<String> topicTags = tags(topic);
            if (topicTags.contains("source_coverage")
                    && outline.sourceCoverageSlots().stream()
                            .noneMatch(slot -> slot.ownerTopicKey().equals(topic.key()))) {
                throw new IllegalArgumentException(
                        "teaching chapter claims source_coverage without a source coverage slot");
            }
            for (SourceCoverageRole role : SourceCoverageRole.values()) {
                if (role == SourceCoverageRole.SUPPORTING_RULE) continue;
                String publicTag = publicCoverageTag(role);
                if (publicTag == null || !topicTags.contains(publicTag)) continue;
                boolean backed = outline.sourceCoverageSlots().stream()
                        .anyMatch(slot -> slot.ownerTopicKey().equals(topic.key()) && slot.role() == role);
                if (!backed) {
                    throw new IllegalArgumentException(
                            "teaching chapter claims " + publicTag + " without a source coverage slot");
                }
            }
        }
        validatePlayableJourneyOrder(outline);
    }

    private static void validatePlayableJourneyOrder(OutlineDraft outline) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (int index = 0; index < outline.topics().size(); index++) {
            positions.put(outline.topics().get(index).key(), index);
        }
        List<SourceCoverageSlotDraft> sourced = outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                .toList();
        int latestSetup = latestPosition(sourced, positions, Set.of(SourceCoverageRole.SETUP));
        int earliestPlayable = earliestPosition(
                sourced,
                positions,
                Set.of(SourceCoverageRole.CORE_LOOP, SourceCoverageRole.LEGAL_ACTION));
        int latestPlayable = latestPosition(
                sourced,
                positions,
                Set.of(SourceCoverageRole.CORE_LOOP, SourceCoverageRole.LEGAL_ACTION));
        int earliestEnding = earliestPosition(sourced, positions, Set.of(SourceCoverageRole.ENDING));
        int latestEnding = latestPosition(sourced, positions, Set.of(SourceCoverageRole.ENDING));
        int earliestScoring = earliestPosition(sourced, positions, Set.of(SourceCoverageRole.SCORING));
        if (latestSetup > earliestPlayable) {
            throw new IllegalArgumentException(
                    "sourced setup obligation appears after the playable turn or action chapters");
        }
        if (latestPlayable > earliestEnding) {
            throw new IllegalArgumentException(
                    "sourced playable turn or action obligation appears after the ending chapter");
        }
        if (latestEnding > earliestScoring) {
            throw new IllegalArgumentException(
                    "sourced ending obligation appears after the scoring chapter");
        }
    }

    private static int earliestPosition(
            List<SourceCoverageSlotDraft> slots,
            Map<String, Integer> positions,
            Set<SourceCoverageRole> roles) {
        return slots.stream()
                .filter(slot -> roles.contains(slot.role()))
                .map(SourceCoverageSlotDraft::ownerTopicKey)
                .mapToInt(positions::get)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private static int latestPosition(
            List<SourceCoverageSlotDraft> slots,
            Map<String, Integer> positions,
            Set<SourceCoverageRole> roles) {
        return slots.stream()
                .filter(slot -> roles.contains(slot.role()))
                .map(SourceCoverageSlotDraft::ownerTopicKey)
                .mapToInt(positions::get)
                .max()
                .orElse(Integer.MIN_VALUE);
    }

    static List<String> metadataForTopic(OutlineDraft outline, TopicDraft topic) {
        if (!declaresContract(outline)) return List.of();
        LinkedHashSet<String> metadata = new LinkedHashSet<>();
        metadata.add(CONTRACT_VERSION_TAG);
        metadata.add(outline.sourceCoverageInventoryComplete() ? COMPLETE_INVENTORY_TAG : INCOMPLETE_INVENTORY_TAG);
        if (outline.sourceCoverageSlots().stream()
                .anyMatch(slot -> slot.availability() != SourceCoverageAvailability.SOURCED)) {
            metadata.add(UNSOURCED_TAG);
        }
        outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.ownerTopicKey().equals(topic.key()))
                .map(SourceCoverageSlotDraft::role)
                .map(TeachingSourceCoverageContract::roleTag)
                .forEach(metadata::add);
        return List.copyOf(metadata);
    }

    public static String roleTag(SourceCoverageRole role) {
        if (role == null) throw new IllegalArgumentException("teaching source coverage role is required");
        return ROLE_TAG_PREFIX + role.name().toLowerCase(Locale.ROOT);
    }

    static Assessment assess(TeachingPlan plan, List<LessonSection> sections) {
        if (plan == null || sections == null) {
            throw new IllegalArgumentException("teaching source coverage assessment is invalid");
        }
        boolean applicable = plan.sections().stream()
                .flatMap(section -> section.coverageTags().stream())
                .anyMatch(CONTRACT_VERSION_TAG::equals);
        if (!applicable) return new Assessment(false, true, List.of());

        List<String> gaps = new ArrayList<>();
        if (plan.sections().stream().anyMatch(section -> !section.coverageTags().contains(CONTRACT_VERSION_TAG))) {
            gaps.add("contract metadata differs across chapters");
        }
        if (plan.sections().stream()
                .anyMatch(section -> !section.coverageTags().contains(COMPLETE_INVENTORY_TAG))) {
            gaps.add("source obligation inventory is incomplete");
        }
        if (plan.sections().stream().anyMatch(section -> section.coverageTags().contains(UNSOURCED_TAG)
                || section.coverageTags().contains("source_dependency"))) {
            gaps.add("one or more source obligations are unavailable");
        }
        for (SourceCoverageRole role : CORE_ROLES) {
            if (plan.sections().stream().noneMatch(section -> section.coverageTags().contains(roleTag(role)))) {
                gaps.add("required source role is absent: " + role);
            }
        }

        Map<String, List<LessonSection>> lessonByTopic = sections.stream()
                .collect(Collectors.groupingBy(
                        LessonSection::topicKey,
                        LinkedHashMap::new,
                        Collectors.toList()));
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            boolean ownsRequiredSlot = ownsRequiredSlot(planned);
            if (!ownsRequiredSlot) continue;
            List<LessonSection> matches = lessonByTopic.getOrDefault(planned.topicKey(), List.of());
            if (matches.size() != 1) {
                gaps.add("required source owner is missing or duplicated: " + planned.topicKey());
                continue;
            }
            LessonSection actual = matches.getFirst();
            if (actual.position() != planned.position()
                    || actual.required() != planned.required()
                    || !new LinkedHashSet<>(actual.coverageTags())
                            .equals(new LinkedHashSet<>(planned.coverageTags()))) {
                gaps.add("chapter projection conflicts with its source owner: " + planned.topicKey());
                continue;
            }
            if (actual.evidenceStatus() != EvidenceStatus.SUPPORTED) {
                gaps.add("source owner has not passed evidence review: " + planned.topicKey());
                continue;
            }
            if (planned.sourcePageNumbers().isEmpty()) {
                gaps.add("source owner has no bound page: " + planned.topicKey());
                continue;
            }
            boolean citedOwnerPage = actual.steps().stream()
                    .flatMap(step -> step.sourcePages().stream())
                    .anyMatch(planned.sourcePageNumbers()::contains);
            if (!citedOwnerPage) {
                gaps.add("source owner has no citation to its bound page: " + planned.topicKey());
                continue;
            }
            List<String> uncoveredIdentifiers = planned.retrievalQueries().stream()
                    .filter(identifier -> actual.steps().stream().noneMatch(step ->
                            citesOwnedIdentifier(step, planned.sourcePageNumbers(), identifier)))
                    .toList();
            if (!uncoveredIdentifiers.isEmpty()) {
                gaps.add("source owner omitted one or more required source slots: " + planned.topicKey());
            }
        }
        return new Assessment(true, gaps.isEmpty(), List.copyOf(gaps));
    }

    private static boolean ownsRequiredSlot(TeachingPlan.PlannedSection section) {
        if (!section.required()) return false;
        return section.coverageTags().stream()
                .anyMatch(tag -> tag.startsWith(ROLE_TAG_PREFIX)
                        && !tag.equals(roleTag(SourceCoverageRole.SUPPORTING_RULE)));
    }

    private static boolean citesOwnedIdentifier(
            com.rulepilot.teaching.domain.IllustratedLesson.LessonStep step,
            List<Integer> ownerPages,
            String identifier) {
        if (step.sourcePages().stream().noneMatch(ownerPages::contains)) return false;
        return containsIdentifier(identity(step.heading() + " " + step.text()), identity(identifier));
    }

    private static boolean containsIdentifier(String text, String identifier) {
        if (identifier.isBlank()) return false;
        boolean ascii = identifier.codePoints().allMatch(codePoint -> codePoint < 128);
        if (!ascii) return text.contains(identifier);
        int firstWordCharacter = java.util.stream.IntStream.range(0, identifier.length())
                .filter(index -> Character.isLetterOrDigit(identifier.charAt(index)))
                .findFirst()
                .orElse(-1);
        int lastWordCharacter = java.util.stream.IntStream.iterate(
                        identifier.length() - 1, index -> index >= 0, index -> index - 1)
                .filter(index -> Character.isLetterOrDigit(identifier.charAt(index)))
                .findFirst()
                .orElse(-1);
        if (firstWordCharacter < 0 || lastWordCharacter < 0) return text.contains(identifier);
        int from = 0;
        while (from <= text.length() - identifier.length()) {
            int match = text.indexOf(identifier, from);
            if (match < 0) return false;
            int left = match + firstWordCharacter - 1;
            int right = match + lastWordCharacter + 1;
            boolean leftBoundary = left < 0 || !Character.isLetterOrDigit(text.charAt(left));
            boolean rightBoundary = right >= text.length() || !Character.isLetterOrDigit(text.charAt(right));
            if (leftBoundary && rightBoundary) return true;
            from = match + 1;
        }
        return false;
    }

    private static void validateOwnerRole(
            SourceCoverageSlotDraft slot, TopicDraft owner, Set<String> ownerTags) {
        if (slot.role() != SourceCoverageRole.SUPPORTING_RULE && !owner.required()) {
            throw new IllegalArgumentException(
                    "required teaching source coverage slots need a required chapter owner: " + slot.slotId());
        }
        if (slot.availability() == SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE) {
            String missingTag = missingSourceTag(slot.role());
            if (missingTag == null
                    || !ownerTags.contains("source_dependency")
                    || !ownerTags.contains(missingTag)) {
                throw new IllegalArgumentException(
                        "missing teaching source coverage slot is not backed by a source dependency: " + slot.slotId());
            }
            return;
        }
        if (ownerTags.contains("source_dependency")) {
            throw new IllegalArgumentException("a source dependency chapter cannot own a supplied rule slot");
        }
        String requiredTag = publicCoverageTag(slot.role());
        if (requiredTag != null && !ownerTags.contains(requiredTag)) {
            throw new IllegalArgumentException(
                    "teaching source coverage slot role disagrees with its chapter: " + slot.slotId());
        }
    }

    private static boolean matchesMissingExternalSource(
            SourceCoverageSlotDraft slot, Map<Integer, PageInput> pages) {
        String requiredMissingTag = missingCoverageTag(slot.role());
        if (requiredMissingTag == null) return false;
        return slot.sourcePageNumbers().stream()
                .map(pages::get)
                .filter(java.util.Objects::nonNull)
                .flatMap(page -> page.sourceDependencies().stream())
                .anyMatch(dependency -> identity(dependency.title()).equals(identity(slot.sourceIdentifier()))
                        && dependency.missingCoverageTags().contains(requiredMissingTag));
    }

    private static boolean containsSourceIdentifier(PageInput page, String identifier) {
        if (page.sourceRuleGroupInventoryComplete()) {
            return page.sourceRuleGroupIdentifiers().stream()
                    .map(VisualSourceRuleGroupLedger::identity)
                    .anyMatch(VisualSourceRuleGroupLedger.identity(identifier)::equals);
        }
        return containsIdentifier(identity(page.text()), identity(identifier));
    }

    private static Set<String> tags(TopicDraft topic) {
        return topic.coverageTags().stream()
                .filter(java.util.Objects::nonNull)
                .map(TeachingSourceCoverageContract::tagIdentity)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String publicCoverageTag(SourceCoverageRole role) {
        return switch (role) {
            case SETUP -> "setup";
            case CORE_LOOP -> "core_loop";
            case LEGAL_ACTION -> "legal_action";
            case ENDING -> "end";
            case SCORING -> "scoring";
            case NECESSARY_EXCEPTION -> "necessary_exception";
            case SUPPORTING_RULE -> "source_coverage";
        };
    }

    private static String missingSourceTag(SourceCoverageRole role) {
        String coverage = missingCoverageTag(role);
        return coverage == null ? null : "missing_" + coverage + "_source";
    }

    private static String missingCoverageTag(SourceCoverageRole role) {
        return switch (role) {
            case SETUP -> "setup";
            case CORE_LOOP -> "core_loop";
            case ENDING -> "end";
            case SCORING -> "scoring";
            case LEGAL_ACTION, NECESSARY_EXCEPTION, SUPPORTING_RULE -> null;
        };
    }

    private static boolean declaresContract(OutlineDraft outline) {
        return outline.sourceCoverageInventoryComplete() || !outline.sourceCoverageSlots().isEmpty();
    }

    private static String tagIdentity(String value) {
        return identity(value).replace('-', '_');
    }

    private static String identity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private record SourceAnchor(int pageNumber, String sourceIdentifier) {}

    record Assessment(boolean applicable, boolean complete, List<String> gaps) {
        Assessment {
            gaps = List.copyOf(gaps);
        }
    }
}
