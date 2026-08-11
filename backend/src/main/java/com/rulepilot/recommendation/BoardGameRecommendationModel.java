package com.rulepilot.recommendation;

import java.util.List;

/** Provider-neutral native action-call port for the conversational recommendation Agent. */
public interface BoardGameRecommendationModel {

    boolean configured();

    Turn next(Request request);

    default boolean preferenceReviewConfigured() {
        return false;
    }

    default boolean preferenceInterpretationConfigured() {
        return false;
    }

    /** Extracts confirmed constraints and reversible assumptions before the ReAct loop. */
    default PreferenceInterpretation interpretPreferences(PreferenceInterpretationRequest request) {
        return new PreferenceInterpretation(List.of(), new Turn("", List.of()));
    }

    /**
     * Classifies whether each proposed preference is a confirmed constraint, a reversible contextual
     * assumption, or unsupported by its cited user message. Application code still owns schema, range,
     * provenance, and state transitions.
     */
    default PreferenceReview reviewPreferences(PreferenceReviewRequest request) {
        return new PreferenceReview(
                java.util.Collections.nCopies(
                        request.proposals().size(),
                        new PreferenceDecision(PreferenceEvidenceStatus.DIRECT, "DIRECT")),
                new Turn("", List.of()));
    }

    record ToolSpec(String name, String description, String inputSchema) {
        public ToolSpec {
            if (blank(name) || blank(description) || blank(inputSchema)) {
                throw new IllegalArgumentException("recommendation action specification is invalid");
            }
        }
    }

    record ToolCall(String id, String name, String argumentsJson) {
        public ToolCall {
            if (blank(id) || blank(name) || blank(argumentsJson)) {
                throw new IllegalArgumentException("recommendation action call is invalid");
            }
        }
    }

    enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
        public Message {
            if (role == null || content == null || toolCalls == null) {
                throw new IllegalArgumentException("recommendation action message is invalid");
            }
            toolCalls = List.copyOf(toolCalls);
            if (role == Role.TOOL && (blank(toolCallId) || blank(toolName))) {
                throw new IllegalArgumentException("recommendation action response correlation is invalid");
            }
            if (role != Role.ASSISTANT && !toolCalls.isEmpty()) {
                throw new IllegalArgumentException("only assistant messages may contain action calls");
            }
        }

        public static Message system(String content) {
            return new Message(Role.SYSTEM, content, List.of(), null, null);
        }

        public static Message user(String content) {
            return new Message(Role.USER, content, List.of(), null, null);
        }

        public static Message assistant(String content, ToolCall toolCall) {
            return new Message(Role.ASSISTANT, content == null ? "" : content, List.of(toolCall), null, null);
        }

        public static Message tool(ToolCall call, String observation) {
            return new Message(Role.TOOL, observation, List.of(), call.id(), call.name());
        }
    }

    record Request(List<Message> messages, List<ToolSpec> tools, int maxOutputTokens) {
        public Request {
            if (messages == null
                    || messages.isEmpty()
                    || tools == null
                    || tools.isEmpty()
                    || maxOutputTokens < 128
                    || maxOutputTokens > 2_048) {
                throw new IllegalArgumentException("recommendation model request is invalid");
            }
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    record Turn(String text, List<ToolCall> toolCalls) {
        public Turn {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    record PreferenceEvidence(String id, String text) {
        public PreferenceEvidence {
            if (blank(id) || blank(text) || id.length() > 16 || text.length() > 500) {
                throw new IllegalArgumentException("recommendation preference evidence is invalid");
            }
        }
    }

    record PreferenceProposal(int index, String field, String value, String evidenceId) {
        public PreferenceProposal {
            if (index < 0 || blank(field) || blank(value) || blank(evidenceId)
                    || field.length() > 40 || value.length() > 80 || evidenceId.length() > 16) {
                throw new IllegalArgumentException("recommendation preference proposal is invalid");
            }
        }
    }

    record PreferenceReviewRequest(
            List<PreferenceEvidence> evidence,
            List<PreferenceProposal> proposals) {
        public PreferenceReviewRequest {
            if (evidence == null || evidence.isEmpty() || evidence.size() > 20
                    || proposals == null || proposals.isEmpty() || proposals.size() > 5) {
                throw new IllegalArgumentException("recommendation preference review request is invalid");
            }
            evidence = List.copyOf(evidence);
            proposals = List.copyOf(proposals);
            for (int index = 0; index < proposals.size(); index++) {
                if (proposals.get(index).index() != index) {
                    throw new IllegalArgumentException("recommendation preference proposal indexes are invalid");
                }
            }
        }
    }

    record CurrentPreference(String field, String value) {
        public CurrentPreference {
            if (blank(field) || blank(value) || field.length() > 40 || value.length() > 80) {
                throw new IllegalArgumentException("current recommendation preference is invalid");
            }
        }
    }

    record PreferenceInterpretationRequest(
            List<PreferenceEvidence> evidence,
            List<CurrentPreference> currentPreferences) {
        public PreferenceInterpretationRequest {
            if (evidence == null || evidence.isEmpty() || evidence.size() > 20
                    || currentPreferences == null || currentPreferences.size() > 5) {
                throw new IllegalArgumentException("recommendation preference interpretation request is invalid");
            }
            evidence = List.copyOf(evidence);
            currentPreferences = List.copyOf(currentPreferences);
        }
    }

    enum PreferenceEvidenceStatus {
        DIRECT,
        CONTEXTUAL,
        UNSUPPORTED
    }

    record PreferenceDecision(PreferenceEvidenceStatus status, String reason) {
        public PreferenceDecision {
            if (status == null || blank(reason) || reason.length() > 80) {
                throw new IllegalArgumentException("recommendation preference decision is invalid");
            }
            boolean consistent = switch (status) {
                case DIRECT -> "DIRECT".equals(reason);
                case CONTEXTUAL -> "COMPLETE_GROUP_INFERENCE".equals(reason);
                case UNSUPPORTED -> !java.util.Set.of("DIRECT", "COMPLETE_GROUP_INFERENCE").contains(reason);
            };
            if (!consistent) {
                throw new IllegalArgumentException("recommendation preference decision status is inconsistent");
            }
        }
    }

    record PreferenceReview(List<PreferenceDecision> decisions, Turn rawTurn) {
        public PreferenceReview {
            if (decisions == null || decisions.isEmpty() || rawTurn == null) {
                throw new IllegalArgumentException("recommendation preference review is invalid");
            }
            decisions = List.copyOf(decisions);
            if (decisions.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("recommendation preference review decision is invalid");
            }
        }
    }

    record InterpretedPreference(
            String field,
            String value,
            String evidenceId,
            PreferenceDecision decision) {
        public InterpretedPreference {
            if (blank(field) || blank(value) || blank(evidenceId) || decision == null
                    || field.length() > 40 || value.length() > 80 || evidenceId.length() > 16
                    || decision.status() == PreferenceEvidenceStatus.UNSUPPORTED) {
                throw new IllegalArgumentException("interpreted recommendation preference is invalid");
            }
        }
    }

    record PreferenceInterpretation(List<InterpretedPreference> preferences, Turn rawTurn) {
        public PreferenceInterpretation {
            if (preferences == null || preferences.size() > 5 || rawTurn == null) {
                throw new IllegalArgumentException("recommendation preference interpretation is invalid");
            }
            preferences = List.copyOf(preferences);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
