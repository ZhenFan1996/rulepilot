package com.rulepilot.assistant;

import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import java.util.List;

public interface ContentCriticModel {

    default String providerId() {
        return "unspecified";
    }

    CritiqueDraft critique(ReviewRequest request);

    record CritiqueDraft(List<Issue> issues) {
        public CritiqueDraft {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
