package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Repairs exact concept sources that are present in the rulebook but were omitted from the chapter-owned ledger. */
public final class TeachingConceptSourceOwnership {

    private TeachingConceptSourceOwnership() {}

    public static OutlineDraft repairMissingOwners(OutlineRequest request, OutlineDraft outline) {
        if (request == null || outline == null || outline.wholeGameUnderstanding() == null) return outline;

        Map<String, MissingSourceGroup> missingByIdentifier = new LinkedHashMap<>();
        for (GlobalConceptDraft concept : outline.wholeGameUnderstanding().concepts()) {
            for (String identifier : concept.sourceIdentifiers()) {
                if (isOwnedAnywhere(outline, identifier)) continue;
                String identity = identity(identifier);
                MissingSourceGroup group = missingByIdentifier.get(identity);
                if (group == null) {
                    missingByIdentifier.put(identity, new MissingSourceGroup(
                            identifier,
                            new LinkedHashSet<>(concept.sourcePageNumbers()),
                            new LinkedHashSet<>(concept.relatedTopicKeys())));
                } else {
                    group.commonPages.retainAll(concept.sourcePageNumbers());
                    group.commonTopicKeys.retainAll(concept.relatedTopicKeys());
                }
            }
        }
        if (missingByIdentifier.isEmpty()) return outline;

        List<SourceCoverageSlotDraft> slots = new ArrayList<>(outline.sourceCoverageSlots());
        Map<String, LinkedHashSet<Integer>> addedPagesByTopic = new LinkedHashMap<>();
        Set<String> slotIds = slots.stream()
                .map(SourceCoverageSlotDraft::slotId)
                .map(TeachingConceptSourceOwnership::identity)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int sequence = 1;
        for (MissingSourceGroup group : missingByIdentifier.values()) {
            TopicDraft owner = outline.topics().stream()
                    .filter(topic -> group.commonTopicKeys.contains(topic.key()))
                    .filter(topic -> topic.sourcePageNumbers().stream().anyMatch(group.commonPages::contains))
                    .findFirst()
                    .orElseGet(() -> outline.topics().stream()
                            .filter(topic -> group.commonTopicKeys.contains(topic.key()))
                            .findFirst()
                            .orElse(null));
            if (owner == null) continue;

            List<Integer> exactPages = request.pages().stream()
                    .filter(page -> group.commonPages.contains(page.pageNumber()))
                    .filter(page -> identity(page.text()).contains(identity(group.sourceIdentifier)))
                    .map(page -> page.pageNumber())
                    .toList();
            if (exactPages.isEmpty()) continue;

            String slotId;
            do {
                slotId = "concept-source-" + sequence++;
            } while (!slotIds.add(identity(slotId)));
            slots.add(new SourceCoverageSlotDraft(
                    slotId,
                    SourceCoverageRole.SUPPORTING_RULE,
                    group.sourceIdentifier,
                    exactPages,
                    owner.key(),
                    slotId,
                    SourceCoverageAvailability.SOURCED));
            addedPagesByTopic
                    .computeIfAbsent(owner.key(), ignored -> new LinkedHashSet<>())
                    .addAll(exactPages);
        }
        if (slots.size() == outline.sourceCoverageSlots().size()) return outline;

        List<TopicDraft> topics = outline.topics().stream()
                .map(topic -> withAdditionalPages(topic, addedPagesByTopic.get(topic.key())))
                .toList();
        return new OutlineDraft(
                outline.gameTitle(),
                outline.premise(),
                topics,
                slots,
                outline.sourceCoverageInventoryComplete(),
                outline.wholeGameUnderstanding());
    }

    private static boolean isOwnedAnywhere(OutlineDraft outline, String identifier) {
        String expected = identity(identifier);
        return outline.sourceCoverageSlots().stream()
                .filter(slot -> slot.availability() == SourceCoverageAvailability.SOURCED)
                .filter(slot -> identity(slot.sourceIdentifier()).equals(expected))
                .findAny()
                .isPresent();
    }

    private static TopicDraft withAdditionalPages(TopicDraft topic, Set<Integer> additionalPages) {
        if (additionalPages == null || additionalPages.isEmpty()) return topic;
        LinkedHashSet<Integer> pages = new LinkedHashSet<>(topic.sourcePageNumbers());
        pages.addAll(additionalPages);
        return new TopicDraft(
                topic.key(),
                topic.title(),
                topic.objective(),
                topic.required(),
                topic.visualEvidenceRecommended(),
                topic.retrievalQueries(),
                topic.coverageTags(),
                List.copyOf(pages));
    }

    private static String identity(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static final class MissingSourceGroup {
        private final String sourceIdentifier;
        private final LinkedHashSet<Integer> commonPages;
        private final LinkedHashSet<String> commonTopicKeys;

        private MissingSourceGroup(
                String sourceIdentifier,
                LinkedHashSet<Integer> commonPages,
                LinkedHashSet<String> commonTopicKeys) {
            this.sourceIdentifier = sourceIdentifier;
            this.commonPages = commonPages;
            this.commonTopicKeys = commonTopicKeys;
        }
    }
}
