package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidencePage;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.EvidenceVerifier.Verification;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.AnswerEvidenceRetriever;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.HybridRuleSearch.SearchPage;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartialSearchObservationContractTest {

    @Test
    void projectsPartialAvailabilityThroughAssistantEvidenceAndNativeSearch() {
        UUID versionId = UUID.randomUUID();
        HybridEvidenceHit hit = hybridEvidence(versionId);
        HybridRuleSearch retrieval = new HybridRuleSearch() {
            @Override
            public List<HybridEvidenceHit> search(
                    UUID documentVersionId, String query, RetrievalOptions options) {
                return List.of(hit);
            }

            @Override
            public SearchPage searchPage(UUID documentVersionId, String query, RetrievalOptions options) {
                return new SearchPage(
                        List.of(hit), false, HybridRuleSearch.SourceAvailability.PARTIAL);
            }
        };
        ValidatedAssistantReadTools readTools = new ValidatedAssistantReadTools(retrieval);
        SearchRuleEvidence request = new SearchRuleEvidence(
                versionId, "legal action", 1, Set.of(), null, false, false);

        RuleEvidencePage page = readTools.searchRuleEvidencePage(request, 0, 1);
        ToolObservation observation = new SearchRuleEvidenceNativeTool(readTools, new ObjectMapper())
                .execute(
                        "{\"query\":\"legal action\",\"limit\":1,\"sectionTypes\":[],\"includeAdjacentContext\":false}",
                        scope(versionId));

        assertThat(page.sourceAvailability()).isEqualTo(AssistantReadTools.SourceAvailability.PARTIAL);
        assertThat(observation.status()).isEqualTo(ObservationStatus.PARTIAL);
        assertThat(observation.code()).isEqualTo("RETRIEVAL_SOURCE_PARTIAL");
        assertThat(observation.data())
                .containsEntry("sourceAvailability", "PARTIAL")
                .containsEntry("hasMore", false);
        assertThat(observation.evidenceCount()).isEqualTo(1);
    }

    @Test
    void relationshipSearchDoesNotCallAPartialEmptyPageANegativeResult() {
        UUID versionId = UUID.randomUUID();
        AssistantReadTools readTools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public RuleEvidencePage searchRuleEvidencePage(
                    SearchRuleEvidence request, int offset, int pageSize, Set<UUID> excludedEvidenceIds) {
                return new RuleEvidencePage(
                        List.of(), false, 0, AssistantReadTools.SourceAvailability.PARTIAL);
            }
        };

        ToolObservation observation = new SearchRuleRelationshipsNativeTool(readTools, new ObjectMapper())
                .execute("{\"topic\":\"general rule and exception\",\"limit\":1}", scope(versionId));

        assertThat(observation.status()).isEqualTo(ObservationStatus.PARTIAL);
        assertThat(observation.code()).isEqualTo("RETRIEVAL_SOURCE_PARTIAL");
        assertThat(observation.data())
                .containsEntry("sourceAvailability", "PARTIAL")
                .containsEntry("hasMore", false);
        assertThat(observation.evidenceCount()).isZero();
    }

    @Test
    void partialDeterministicEvidenceRequiresRefinementAndPublishesOnlyItsVerifiedSupport() {
        UUID versionId = UUID.randomUUID();
        HybridEvidenceHit hit = hybridEvidence(versionId);
        AnswerEvidenceRetriever.Result partial = new AnswerEvidenceRetriever.Result(
                List.of(hit), AnswerEvidenceRetriever.State.PARTIAL);
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "What is the legal action?",
                "what is the legal action",
                QuestionType.RULE_QUERY,
                List.of(),
                Set.of());
        QuestionContext context = new QuestionContext(versionId);
        AnswerQuestionPlan direct = plan(EvidenceNeed.DIRECT_RULE);
        AnswerQuestionPlan advice = plan(EvidenceNeed.ADVICE);
        AnswerQuestionPlan completeList = plan(EvidenceNeed.COMPLETE_LIST);

        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, direct, partial)).isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, advice, partial)).isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, completeList, partial)).isTrue();

        AnswerEvidenceAdmissionGate gate = new AnswerEvidenceAdmissionGate(
                new AnswerPublicationValidator(request ->
                        new Verification(VerificationStatus.VERIFIED, List.of())));
        AnswerEvidenceAdmissionGate.Admission admission = gate.admit(versionId, partial);

        assertThat(admission.ready()).isTrue();
        assertThat(admission.evidence()).containsExactly(hit);
        assertThat(admission.evidenceCoverage())
                .isEqualTo(com.rulepilot.assistant.RuleAnswerModel.EvidenceCoverage.PARTIAL);
        assertThat(new AnswerModelRequestFactory()
                        .create(question, context, admission.evidence(), direct, admission.evidenceCoverage())
                        .context()
                        .evidenceCoverage())
                .isEqualTo(com.rulepilot.assistant.RuleAnswerModel.EvidenceCoverage.PARTIAL);
    }

    private AnswerQuestionPlan plan(EvidenceNeed need) {
        return new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion("source obligation", Set.of(need))),
                true,
                AnswerAid.NONE,
                ReferenceBinding.CURRENT_QUESTION);
    }

    private HybridEvidenceHit hybridEvidence(UUID versionId) {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Action", "Canonical action rule.", 2, 2, 0.9);
        return new HybridEvidenceHit(source, 0.9, 1, null, false);
    }

    private ToolScope scope(UUID versionId) {
        return new NativeAgentTool.ToolScope(
                "alice", versionId, UUID.randomUUID(), Instant.now().plusSeconds(30), 4_096);
    }
}
