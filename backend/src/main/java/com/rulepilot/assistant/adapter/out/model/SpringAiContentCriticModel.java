package com.rulepilot.assistant.adapter.out.model;

import com.rulepilot.assistant.ContentCriticModel;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiContentCriticModel implements ContentCriticModel {

    private final RuntimeModelConfiguration models;
    private final FakeContentCriticModel fakeModel;
    private final VersionedAgentPrompts prompts;

    public SpringAiContentCriticModel(
            RuntimeModelConfiguration models, FakeContentCriticModel fakeModel, VersionedAgentPrompts prompts) {
        this.models = models;
        this.fakeModel = fakeModel;
        this.prompts = prompts;
    }

    @Override
    public String providerId() {
        return models.providerFor(Role.CRITIC);
    }

    @Override
    public CritiqueDraft critique(ReviewRequest request) {
        if (models.usesFake(Role.CRITIC)) {
            return fakeModel.critique(request);
        }
        RuntimeException firstFailure;
        try {
            return critiqueOnce(request, "");
        } catch (RuntimeException exception) {
            firstFailure = exception;
        }
        try {
            return critiqueOnce(request, prompts.structuredOutputRepair());
        } catch (RuntimeException exception) {
            exception.addSuppressed(firstFailure);
            throw exception;
        }
    }

    private CritiqueDraft critiqueOnce(ReviewRequest request, String repair) {
        Map<String, UUID> evidenceIds = evidenceIds(request);
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.CRITIC)).prompt();
        if (models.usesDeepSeekNonThinkingGeneration(Role.CRITIC)
                && (request.reviewMode() == ReviewMode.DISCOVERY
                        || request.reviewMode() == ReviewMode.OBJECTIVE_COVERAGE
                        || request.reviewMode() == ReviewMode.POST_PUBLICATION
                        || request.contentType() == ContentType.ANSWER)) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
            options.temperature(0.0);
            options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            prompt = prompt.options(options);
        }
        ModelCritiqueDraft draft = prompt
                .system(systemPrompt(request.reviewMode()))
                .user(user -> user.text(prompts.criticUser())
                        .param("type", request.contentType())
                        .param("mode", request.reviewMode())
                        .param("objective", request.taskContext().objective())
                        .param("coverage", request.taskContext().requiredCoverage())
                        .param("claims", modelClaims(request))
                        .param("evidence", modelEvidence(request))
                        .param("repair", repair))
                .call()
                .entity(ModelCritiqueDraft.class);
        if (draft == null) throw new IllegalArgumentException("critic returned no draft");
        return new CritiqueDraft(draft.issues().stream()
                .map(issue -> new Issue(
                        issue.type(),
                        issue.claimPosition(),
                        resolveReferences(issue.evidenceIds(), evidenceIds),
                        issue.summary()))
                .toList());
    }

    private String systemPrompt(ReviewMode mode) {
        return switch (mode) {
            case DISCOVERY, POST_PUBLICATION -> prompts.criticSystem();
            case ATOMIC_CONFIRMATION -> prompts.atomicCriticSystem();
            case OBJECTIVE_COVERAGE -> prompts.objectiveCoverageCriticSystem();
        };
    }

    private List<ModelClaim> modelClaims(ReviewRequest request) {
        Map<UUID, String> references = reverseEvidenceIds(request);
        return request.claims().stream()
                .map(claim -> new ModelClaim(
                        claim.position(),
                        claim.text(),
                        claim.citationIds().stream().map(references::get).filter(java.util.Objects::nonNull).toList()))
                .toList();
    }

    private List<ModelEvidence> modelEvidence(ReviewRequest request) {
        return IntStream.range(0, request.evidence().size())
                .mapToObj(index -> new ModelEvidence(
                        "E" + (index + 1), request.evidence().get(index).excerpt()))
                .toList();
    }

    private Map<String, UUID> evidenceIds(ReviewRequest request) {
        Map<String, UUID> references = new LinkedHashMap<>();
        IntStream.range(0, request.evidence().size())
                .forEach(index -> references.put(
                        "E" + (index + 1), request.evidence().get(index).chunkId()));
        return Map.copyOf(references);
    }

    private Map<UUID, String> reverseEvidenceIds(ReviewRequest request) {
        Map<UUID, String> references = new LinkedHashMap<>();
        evidenceIds(request).forEach((reference, id) -> references.put(id, reference));
        return Map.copyOf(references);
    }

    private List<UUID> resolveReferences(List<String> references, Map<String, UUID> evidenceIds) {
        if (references == null) return List.of();
        return references.stream()
                .map(reference -> reference == null ? "" : reference.strip().toUpperCase())
                .map(reference -> {
                    UUID id = evidenceIds.get(reference);
                    if (id == null) throw new IllegalArgumentException("critic cited an unknown evidence reference");
                    return id;
                })
                .distinct()
                .toList();
    }

    private record ModelClaim(int position, String text, List<String> citationIds) {}

    private record ModelEvidence(String evidenceRef, String excerpt) {}

    private record ModelCritiqueDraft(List<ModelIssue> issues) {
        private ModelCritiqueDraft {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private record ModelIssue(
            IssueType type, int claimPosition, List<String> evidenceIds, String summary) {
        private ModelIssue {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }
}
