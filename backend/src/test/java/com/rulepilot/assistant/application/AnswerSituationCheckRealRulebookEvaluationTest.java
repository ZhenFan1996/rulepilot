package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.SituationCheckRequest;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.SituationCheckStatus;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-situation-check-evaluation")
class AnswerSituationCheckRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void validatesConfirmedContradictedAndMissingStateAgainstIgnoredRealRulebookPages() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_SITUATION_CHECK_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/situation-check-cases.json").toFile());
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode));
        }

        assertThat(results).hasSizeGreaterThanOrEqualTo(3).allSatisfy(result -> assertThat(result)
                .containsEntry("realPageTermsVerified", true)
                .containsEntry("confirmed", true)
                .containsEntry("contradicted", true)
                .containsEntry("missingStayedConditional", true)
                .containsEntry("inventedFactRejected", true)
                .containsEntry("citationPublished", true));
        Path output = root.resolve(".local/agent-evaluation/situation-check-real-rulebooks.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
    }

    private Map<String, Object> runCase(Path root, JsonNode caseNode) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        int page = caseNode.path("page").asInt();
        String pageText = extractPage(pdf, page);
        String normalizedPage = pageText.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> requiredTerms = new ArrayList<>();
        caseNode.path("requiredTerms").forEach(term -> requiredTerms.add(term.asText().toLowerCase(Locale.ROOT)));
        assertThat(requiredTerms).allSatisfy(term -> assertThat(normalizedPage).contains(term));

        UUID versionId = UUID.nameUUIDFromBytes(caseId.getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes((caseId + ":" + page).getBytes(StandardCharsets.UTF_8));
        RuleEvidenceHit source = new RuleEvidenceHit(
                chunkId, versionId, "REAL_RULE_PAGE", "Rule condition", pageText, page, page, 1.0);
        HybridEvidenceHit evidence = new HybridEvidenceHit(source, 1.0, 1, null, true);
        String requirement = caseNode.path("requirement").asText();
        assertThat(normalizedPage).contains(requirement.toLowerCase(Locale.ROOT));

        boolean confirmed = resolve(
                versionId, evidence, requirement, caseNode.path("confirmedQuestion").asText(),
                "CONFIRMED", caseNode.path("confirmedFact").asText(), "The stated condition is satisfied.")
                == SituationCheckStatus.CONFIRMED;
        boolean contradicted = resolve(
                versionId, evidence, requirement, caseNode.path("contradictedQuestion").asText(),
                "CONTRADICTED", caseNode.path("contradictedFact").asText(), "The stated condition is not satisfied.")
                == SituationCheckStatus.CONTRADICTED;
        var missing = resolved(
                versionId, evidence, requirement, caseNode.path("missingQuestion").asText(),
                "NOT_PROVIDED", "", "It depends on whether the cited condition is satisfied.");

        ModelRequest negativeRequest = request(
                versionId, evidence, caseNode.path("confirmedQuestion").asText());
        ModelDraft invented = draft(
                chunkId, requirement, "CONFIRMED", "a fact not present in the current question", "The condition is satisfied.");
        assertThatThrownBy(() -> new AnswerSituationCheckResolver().resolve(negativeRequest, invented))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("literal current-question input");

        ModelDraft missingDraft = draft(
                chunkId, requirement, "NOT_PROVIDED", "", "It depends on whether the cited condition is satisfied.");
        var published = new AnswerPublicationValidator(new PolicyEvidenceVerifier()).publish(
                versionId, missingDraft, List.of(evidence), List.of(), missing);

        return Map.of(
                "caseId", caseId,
                "page", page,
                "realPageTermsVerified", true,
                "confirmed", confirmed,
                "contradicted", contradicted,
                "missingStayedConditional", missing.getFirst().status() == SituationCheckStatus.NOT_PROVIDED,
                "inventedFactRejected", true,
                "citationPublished", published.citations().stream().anyMatch(citation -> citation.pageFrom() == page));
    }

    private SituationCheckStatus resolve(
            UUID versionId,
            HybridEvidenceHit evidence,
            String requirement,
            String question,
            String status,
            String playerFact,
            String verdict) {
        return resolved(versionId, evidence, requirement, question, status, playerFact, verdict)
                .getFirst().status();
    }

    private List<com.rulepilot.assistant.domain.RuleSituationCheck> resolved(
            UUID versionId,
            HybridEvidenceHit evidence,
            String requirement,
            String question,
            String status,
            String playerFact,
            String verdict) {
        ModelRequest request = request(versionId, evidence, question);
        return new AnswerSituationCheckResolver().resolve(
                request, draft(evidence.evidence().chunkId(), requirement, status, playerFact, verdict));
    }

    private ModelRequest request(UUID versionId, HybridEvidenceHit evidence, String question) {
        RuleEvidenceHit source = evidence.evidence();
        return new ModelRequest(
                question,
                QuestionType.SITUATION_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        source.chunkId(), source.sectionType(), source.heading(), source.excerpt(),
                        source.pageFrom(), source.pageTo())));
    }

    private ModelDraft draft(UUID chunkId, String requirement, String status, String playerFact, String verdict) {
        return new ModelDraft(
                true,
                null,
                verdict,
                "The result uses the cited requirement and only the table fact stated in the question.",
                List.of(chunkId),
                List.of(),
                "HIGH",
                AnswerBasis.GROUNDED_APPLICATION.name(),
                List.of(),
                List.of(new SituationCheckRequest(requirement, status, playerFact, List.of(chunkId))));
    }

    private String extractPage(Path pdf, int page) throws Exception {
        try (var document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document).replaceAll("\\s+", " ").strip();
        }
    }
}
