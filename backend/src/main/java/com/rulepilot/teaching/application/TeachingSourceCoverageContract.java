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
import java.util.ArrayList;
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

    private TeachingSourceCoverageContract() {}

    public static void requireCompleteModelContract(OutlineRequest request, OutlineDraft outline) {
        requireCompleteSourceContract(request, outline);
        requireCompleteWholeGameUnderstanding(outline);
    }

    public static void requireCompleteSourceContract(OutlineRequest request, OutlineDraft outline) {
        if (outline == null || !outline.sourceCoverageInventoryComplete()) {
            throw new IllegalArgumentException("teaching outline did not return a complete source coverage inventory");
        }
        validateAgainstSources(request, outline);
    }

    public static void requireCompleteWholeGameUnderstanding(OutlineDraft outline) {
        TeachingWholeGameUnderstandingPolicy.validateComplete(outline);
    }

    public static List<String> unrelatedSourceOwnedTopicKeys(OutlineDraft outline) {
        if (outline == null || outline.wholeGameUnderstanding() == null) return List.of();
        Set<String> relatedTopics = outline.wholeGameUnderstanding().concepts().stream()
                .flatMap(concept -> concept.relatedTopicKeys().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                .map(SourceCoverageSlotDraft::ownerTopicKey)
                .filter(owner -> !relatedTopics.contains(owner))
                .distinct()
                .toList();
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
        List<SourceCoverageSlotDraft> missingExactIdentifiers = missingExactSourceSlots(request, outline);
        for (SourceCoverageSlotDraft slot : outline.sourceCoverageSlots()) {
            if (slot.sourcePageNumbers().stream().anyMatch(page -> !pages.containsKey(page))) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot references an unknown source page: " + slot.slotId());
            }
            if (missingExactIdentifiers.contains(slot)) {
                throw new MissingExactSourceIdentifierException(slot.slotId(), slot.sourceIdentifier());
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

    public static List<SourceCoverageSlotDraft> missingExactSourceSlots(
            OutlineRequest request, OutlineDraft outline) {
        if (request == null || outline == null) return List.of();
        Map<Integer, PageInput> pages = request.pages().stream().collect(Collectors.toMap(
                PageInput::pageNumber,
                page -> page,
                (first, ignored) -> first,
                LinkedHashMap::new));
        return outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                .filter(slot -> slot.sourcePageNumbers().stream()
                        .map(pages::get)
                        .filter(java.util.Objects::nonNull)
                        .noneMatch(page -> containsSourceIdentifier(page, slot.sourceIdentifier())))
                .toList();
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
        Map<String, String> teachingUnitOwners = new LinkedHashMap<>();
        for (SourceCoverageSlotDraft slot : outline.sourceCoverageSlots()) {
            if (!slotIds.add(identity(slot.slotId()))) {
                throw new IllegalArgumentException("teaching source coverage slot IDs must be unique");
            }
            TopicDraft owner = topics.get(slot.ownerTopicKey());
            if (owner == null) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot has no chapter owner: " + slot.slotId());
            }
            String previousUnitOwner = teachingUnitOwners.putIfAbsent(
                    identity(slot.teachingUnitId()), slot.ownerTopicKey());
            if (previousUnitOwner != null && !previousUnitOwner.equals(slot.ownerTopicKey())) {
                throw new IllegalArgumentException(
                        "one planned teaching unit cannot span multiple chapter owners: " + slot.teachingUnitId());
            }
            if (!owner.sourcePageNumbers().containsAll(slot.sourcePageNumbers())) {
                throw new IllegalArgumentException(
                        "teaching source coverage slot exceeds its owner's source pages: " + slot.slotId());
            }
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
        }
    }

    static List<String> metadataForTopic(OutlineDraft outline, TopicDraft topic) {
        if (!declaresContract(outline)) return List.of();
        LinkedHashSet<String> metadata = new LinkedHashSet<>();
        metadata.add(CONTRACT_VERSION_TAG);
        metadata.add(outline.sourceCoverageInventoryComplete() ? COMPLETE_INVENTORY_TAG : INCOMPLETE_INVENTORY_TAG);
        if (outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.ownerTopicKey().equals(topic.key()))
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
        }
        return new Assessment(true, gaps.isEmpty(), List.copyOf(gaps));
    }

    private static boolean ownsRequiredSlot(TeachingPlan.PlannedSection section) {
        if (!section.required()) return false;
        return section.coverageTags().stream()
                .anyMatch(tag -> tag.startsWith(ROLE_TAG_PREFIX));
    }

    private static boolean matchesMissingExternalSource(
            SourceCoverageSlotDraft slot, Map<Integer, PageInput> pages) {
        String requiredMissingTag = missingCoverageTag(slot.role());
        return slot.sourcePageNumbers().stream()
                .map(pages::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(page -> page.sourceDependencies().stream().anyMatch(dependency ->
                        requiredMissingTag == null
                                || dependency.missingCoverageTags().contains(requiredMissingTag)));
    }

    private static boolean containsSourceIdentifier(PageInput page, String identifier) {
        if (!page.sourceRuleGroupInventoryComplete()) return true;
        return page.sourceRuleGroupIdentifiers().stream()
                .map(VisualSourceRuleGroupLedger::identity)
                .anyMatch(VisualSourceRuleGroupLedger.identity(identifier)::equals);
    }

    private static Set<String> tags(TopicDraft topic) {
        return topic.coverageTags().stream()
                .filter(java.util.Objects::nonNull)
                .map(TeachingSourceCoverageContract::tagIdentity)
                .collect(Collectors.toUnmodifiableSet());
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
        return VisualSourceRuleGroupLedger.identity(value);
    }

    private record SourceAnchor(int pageNumber, String sourceIdentifier) {}

    public static final class MissingExactSourceIdentifierException extends IllegalArgumentException {
        private final String slotId;
        private final String sourceIdentifier;

        MissingExactSourceIdentifierException(String slotId, String sourceIdentifier) {
            super("teaching source coverage slot has no exact source identifier: " + slotId);
            this.slotId = slotId;
            this.sourceIdentifier = sourceIdentifier;
        }

        public String slotId() {
            return slotId;
        }

        public String sourceIdentifier() {
            return sourceIdentifier;
        }
    }

    record Assessment(boolean applicable, boolean complete, List<String> gaps) {
        Assessment {
            gaps = List.copyOf(gaps);
        }
    }
}
