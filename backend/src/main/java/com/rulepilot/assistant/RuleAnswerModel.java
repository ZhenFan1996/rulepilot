package com.rulepilot.assistant;

import java.util.List;
import java.util.UUID;

public interface RuleAnswerModel {

    default String providerId() {
        return "unspecified";
    }

    ModelDraft compose(ModelRequest request);

    record ModelRequest(String question, List<EvidenceInput> evidence) {
        public ModelRequest {
            if (question == null || question.isBlank() || evidence == null || evidence.isEmpty()) {
                throw new IllegalArgumentException("answer model request is invalid");
            }
            evidence = List.copyOf(evidence);
        }
    }

    record EvidenceInput(UUID chunkId, String sectionType, String heading, String excerpt, int pageFrom, int pageTo) {}

    record ModelDraft(
            String shortVerdict,
            String explanation,
            List<UUID> citationIds,
            List<String> exceptions,
            String confidence) {

        public ModelDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        }
    }
}
