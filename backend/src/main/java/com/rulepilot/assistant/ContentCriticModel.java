package com.rulepilot.assistant;

import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import java.util.List;

public interface ContentCriticModel {

    default String providerId() {
        return "unspecified";
    }

    default String providerId(String ownerUsername) {
        return providerId();
    }

    CritiqueDraft critique(ReviewRequest request);

    default CritiqueDraft critique(ReviewRequest request, String ownerUsername) {
        return critique(request);
    }

    record CritiqueDraft(List<Issue> issues) {
        public CritiqueDraft {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
