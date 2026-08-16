package com.rulepilot.assistant.adapter.out.model;

import com.rulepilot.assistant.ContentCriticModel;
import com.rulepilot.assistant.GeneratedContentCritic.ClaimAspect;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class SpringAiContentCriticModel implements ContentCriticModel {

    private final RuntimeModelConfiguration models;
    private final FakeContentCriticModel fakeModel;
    private final VersionedAgentPrompts prompts;
    private final double temperature;

    public SpringAiContentCriticModel(
            RuntimeModelConfiguration models, FakeContentCriticModel fakeModel, VersionedAgentPrompts prompts) {
        this(models, fakeModel, prompts, 0.0);
    }

    @Autowired
    public SpringAiContentCriticModel(
            RuntimeModelConfiguration models,
            FakeContentCriticModel fakeModel,
            VersionedAgentPrompts prompts,
            @Value("${rulepilot.critic.temperature:0.0}") double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("critic model temperature must be between 0 and 2");
        }
        this.models = models;
        this.fakeModel = fakeModel;
        this.prompts = prompts;
        this.temperature = temperature;
    }

    @Override
    public String providerId() {
        return providerId(null);
    }

    @Override
    public String providerId(String ownerUsername) {
        return providerFor(ownerUsername);
    }

    @Override
    public CritiqueDraft critique(ReviewRequest request) {
        return critique(request, null);
    }

    @Override
    public CritiqueDraft critique(ReviewRequest request, String ownerUsername) {
        if (usesFake(ownerUsername)) {
            return fakeModel.critique(request);
        }
        RuntimeException firstFailure;
        try {
            return critiqueOnce(request, "", ownerUsername);
        } catch (RuntimeException exception) {
            firstFailure = exception;
        }
        try {
            String repair = prompts.criticOutputRepair();
            if (repair == null || repair.isBlank()) repair = prompts.structuredOutputRepair();
            return critiqueOnce(request, repair, ownerUsername);
        } catch (RuntimeException secondFailure) {
            secondFailure.addSuppressed(firstFailure);
            throw secondFailure;
        }
    }

    private CritiqueDraft critiqueOnce(
            ReviewRequest request, String repair, String ownerUsername) {
        Map<String, UUID> evidenceIds = evidenceIds(request);
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(modelFor(ownerUsername)).prompt();
        boolean deepSeekNonThinking = usesDeepSeekNonThinkingGeneration(ownerUsername);
        boolean qwen = usesQwen(ownerUsername);
        if (deepSeekNonThinking || qwen) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
            options.model(modelNameFor(ownerUsername));
            options.temperature(temperature);
            if (deepSeekNonThinking) {
                options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else {
                options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
                options.extraBody(Map.of("enable_thinking", false));
            }
            prompt = prompt.options(options);
        } else {
            prompt = prompt.options(ChatOptions.builder()
                    .temperature(temperature));
        }
        ModelCritiqueDraft draft = prompt
                .system(systemPrompt(request.reviewMode()))
                .user(user -> user.text(userPrompt(request.reviewMode()))
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
                .filter(issue -> Boolean.TRUE.equals(issue.defectConfirmed()))
                .map(issue -> confirmedIssue(request, issue, evidenceIds))
                .toList());
    }

    private Issue confirmedIssue(ReviewRequest request, ModelIssue issue, Map<String, UUID> evidenceIds) {
        if (issue.type() == null || issue.claimPosition() < 1
                || issue.summary() == null || issue.summary().isBlank()) {
            throw new IllegalArgumentException("confirmed critic issue is incomplete");
        }
        ClaimAspect claimAspect = issue.claimAspect();
        if (request.contentType() == ContentType.LESSON && claimAspect == null) {
            throw new IllegalArgumentException("confirmed lesson critic issue is missing claimAspect");
        }
        List<UUID> resolvedEvidence = resolveReferences(issue.evidenceIds(), evidenceIds);
        if (request.contentType() == ContentType.LESSON) {
            Set<UUID> claimEvidence = request.claims().stream()
                    .filter(claim -> claim.position() == issue.claimPosition())
                    .flatMap(claim -> claim.citationIds().stream())
                    .collect(Collectors.toUnmodifiableSet());
            if (resolvedEvidence.stream().noneMatch(claimEvidence::contains)) {
                throw new IllegalArgumentException("confirmed lesson critic issue has no claim-bound evidence");
            }
        }
        return new Issue(
                issue.type(),
                claimAspect == null ? ClaimAspect.GENERAL : claimAspect,
                issue.claimPosition(),
                resolvedEvidence,
                issue.summary());
    }

    private boolean usesQwen(String ownerUsername) {
        return "qwen".equals(providerFor(ownerUsername));
    }

    private ChatModel modelFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.modelFor(Role.CRITIC)
                : models.modelFor(Role.CRITIC, ownerUsername);
    }

    private String providerFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.providerFor(Role.CRITIC)
                : models.providerFor(Role.CRITIC, ownerUsername);
    }

    private String modelNameFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.modelNameFor(Role.CRITIC)
                : models.modelNameFor(Role.CRITIC, ownerUsername);
    }

    private boolean usesFake(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesFake(Role.CRITIC)
                : models.usesFake(Role.CRITIC, ownerUsername);
    }

    private boolean usesDeepSeekNonThinkingGeneration(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesDeepSeekNonThinkingGeneration(Role.CRITIC)
                : models.usesDeepSeekNonThinkingGeneration(Role.CRITIC, ownerUsername);
    }

    private String systemPrompt(ReviewMode mode) {
        return switch (mode) {
            case DISCOVERY, POST_PUBLICATION -> prompts.criticSystem();
            case POST_PUBLICATION_STRUCTURE -> prompts.lessonStructureCriticSystem();
            case ATOMIC_CONFIRMATION -> prompts.atomicCriticSystem();
            case OBJECTIVE_COVERAGE -> prompts.objectiveCoverageCriticSystem();
        };
    }

    private String userPrompt(ReviewMode mode) {
        return mode == ReviewMode.ATOMIC_CONFIRMATION ? prompts.atomicCriticUser() : prompts.criticUser();
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
            Boolean defectConfirmed,
            IssueType type,
            ClaimAspect claimAspect,
            int claimPosition,
            List<String> evidenceIds,
            String summary) {
        private ModelIssue {
            if (defectConfirmed == null) {
                throw new IllegalArgumentException("critic issue verdict is missing");
            }
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }
}
