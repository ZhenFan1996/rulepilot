package com.rulepilot.teaching;

import java.util.List;

/** Lets the model decide how this particular game should be taught before retrieval begins. */
public interface TeachingOutlineModel {

    OutlineDraft organize(OutlineRequest request);

    record OutlineRequest(
            int playerCount,
            int beginnerCount,
            int durationMinutes,
            List<PageInput> pages,
            List<PageImageInput> pageImages) {
        public OutlineRequest {
            if (playerCount < 1 || beginnerCount < 0 || beginnerCount > playerCount
                    || durationMinutes < 2 || pages == null || pages.isEmpty() || pageImages == null) {
                throw new IllegalArgumentException("teaching outline request is invalid");
            }
            pages = List.copyOf(pages);
            pageImages = List.copyOf(pageImages);
        }

        public OutlineRequest(int playerCount, int beginnerCount, int durationMinutes, List<PageInput> pages) {
            this(playerCount, beginnerCount, durationMinutes, pages, List.of());
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

    record PageImageInput(int pageNumber, String mediaType, byte[] content) {
        public PageImageInput {
            if (pageNumber < 1 || mediaType == null || mediaType.isBlank() || content == null || content.length == 0) {
                throw new IllegalArgumentException("rulebook outline page image is invalid");
            }
            mediaType = mediaType.strip();
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
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
            if (title == null || title.isBlank() || title.length() > 160
                    || objective == null || objective.isBlank() || objective.length() > 600
                    || (key != null && key.length() > 100)
                    || (retrievalQueries != null && (retrievalQueries.size() > 8 || retrievalQueries.stream()
                            .anyMatch(query -> query == null || query.isBlank() || query.length() > 300)))) {
                throw new IllegalArgumentException("teaching outline topic is invalid");
            }
            key = key == null ? "" : key.strip();
            title = title.strip();
            objective = objective.strip();
            retrievalQueries = retrievalQueries == null ? List.of() : List.copyOf(retrievalQueries);
            coverageTags = coverageTags == null ? List.of() : List.copyOf(coverageTags);
        }
    }
}
