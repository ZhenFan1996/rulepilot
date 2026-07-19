package com.rulepilot.teaching;

import java.util.List;

/** Lets the model decide how this particular game should be taught before retrieval begins. */
public interface TeachingOutlineModel {

    OutlineDraft organize(OutlineRequest request);

    record OutlineRequest(
            int playerCount,
            int beginnerCount,
            int durationMinutes,
            List<PageInput> pages) {
        public OutlineRequest {
            if (playerCount < 1 || beginnerCount < 0 || beginnerCount > playerCount
                    || durationMinutes < 2 || pages == null || pages.isEmpty()) {
                throw new IllegalArgumentException("teaching outline request is invalid");
            }
            pages = List.copyOf(pages);
        }
    }

    record PageInput(int pageNumber, String text) {
        public PageInput {
            if (pageNumber < 1 || text == null || text.isBlank()) {
                throw new IllegalArgumentException("rulebook page input is invalid");
            }
            text = text.strip();
        }
    }

    record OutlineDraft(String gameTitle, String premise, List<TopicDraft> topics) {
        public OutlineDraft {
            if (gameTitle == null || gameTitle.isBlank() || gameTitle.length() > 200
                    || premise == null || premise.isBlank() || premise.length() > 1_200) {
                throw new IllegalArgumentException("teaching outline identity is invalid");
            }
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    record TopicDraft(
            String key,
            String title,
            String objective,
            boolean required,
            boolean visualEvidenceRecommended,
            List<String> retrievalQueries,
            List<String> coverageTags) {
        public TopicDraft {
            if (key == null || key.isBlank() || key.length() > 100
                    || title == null || title.isBlank() || title.length() > 160
                    || objective == null || objective.isBlank() || objective.length() > 600
                    || retrievalQueries == null || retrievalQueries.isEmpty() || retrievalQueries.size() > 5
                    || retrievalQueries.stream()
                            .anyMatch(query -> query == null || query.isBlank() || query.length() > 300)
                    || coverageTags == null) {
                throw new IllegalArgumentException("teaching outline topic is invalid");
            }
            retrievalQueries = retrievalQueries == null ? List.of() : List.copyOf(retrievalQueries);
            coverageTags = coverageTags == null ? List.of() : List.copyOf(coverageTags);
        }
    }
}
