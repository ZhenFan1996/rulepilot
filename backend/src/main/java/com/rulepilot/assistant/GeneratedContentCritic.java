package com.rulepilot.assistant;

import java.util.List;
import java.util.UUID;

public interface GeneratedContentCritic {

    Review review(ReviewRequest request, ReviewRisk risk);

    enum ContentType {
        LESSON,
        ANSWER
    }

    enum ReviewRisk {
        STANDARD,
        LOW_CONFIDENCE
    }

    enum IssueType {
        UNSUPPORTED_CLAIM,
        CONTRADICTION,
        MISSING_EXCEPTION,
        OVERREACH
    }

    record ReviewRequest(UUID assistantRunId, ContentType contentType, List<Claim> claims, List<Evidence> evidence) {
        public ReviewRequest {
            claims = claims == null ? List.of() : List.copyOf(claims);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record Claim(int position, String text, List<UUID> citationIds) {
        public Claim {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }

    record Evidence(UUID chunkId, String excerpt) {}

    record Issue(IssueType type, int claimPosition, List<UUID> evidenceIds, String summary) {
        public Issue {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    record Review(boolean performed, List<Issue> issues) {
        public Review {
            issues = issues == null ? List.of() : List.copyOf(issues);
            if (!performed && !issues.isEmpty()) {
                throw new IllegalArgumentException("skipped critic review cannot contain issues");
            }
        }

        public boolean accepted() {
            return issues.isEmpty();
        }
    }
}
