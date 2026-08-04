package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.WalkthroughStepRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-walkthrough-evaluation")
class AnswerWalkthroughRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void preservesCitedProceduresAcrossIgnoredRealRulebookLayouts() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_WALKTHROUGH_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/walkthrough-cases.json").toFile());
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode));
        }

        assertThat(results).hasSizeGreaterThanOrEqualTo(3).allSatisfy(result -> assertThat(result)
                .containsEntry("realPageOrderVerified", true)
                .containsEntry("reversedOrderRejectedByOracle", true)
                .containsEntry("toolPreservedStepOrder", true)
                .containsEntry("allStepsSeparatelyCited", true)
                .containsEntry("citationPublished", true));
        Path output = root.resolve(".local/agent-evaluation/walkthrough-real-rulebooks.json");
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
        String normalizedPage = normalized(pageText);
        List<String> markers = values(caseNode.path("markers"));
        assertThat(markersAreInOrder(normalizedPage, markers)).isTrue();
        List<String> reversed = new ArrayList<>(markers);
        Collections.reverse(reversed);
        assertThat(markersAreInOrder(normalizedPage, reversed)).isFalse();

        UUID versionId = UUID.nameUUIDFromBytes(caseId.getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes((caseId + ":" + page).getBytes(StandardCharsets.UTF_8));
        RuleEvidenceHit source = new RuleEvidenceHit(
                chunkId, versionId, "REAL_PROCEDURE_PAGE", "Procedure", pageText, page, page, 1.0);
        HybridEvidenceHit evidence = new HybridEvidenceHit(source, 1.0, 1, null, true);
        List<WalkthroughStepRequest> requested = new ArrayList<>();
        for (JsonNode step : caseNode.path("steps")) {
            requested.add(new WalkthroughStepRequest(
                    step.path("instruction").asText(),
                    step.path("explanation").asText(),
                    step.path("orderBasis").asText(),
                    List.of(chunkId)));
        }
        ModelDraft draft = new ModelDraft(
                true, null, "Follow the cited procedure.", "The steps preserve the rulebook's stated sequence.",
                List.of(chunkId), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), requested);
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, source.sectionType(), source.heading(), pageText, page, page)));

        var resolved = new AnswerWalkthroughResolver().resolve(request, draft);
        var published = new AnswerPublicationValidator(new PolicyEvidenceVerifier()).publish(
                versionId, draft, List.of(evidence), List.of(), List.of(), resolved);

        return Map.of(
                "caseId", caseId,
                "page", page,
                "stepCount", resolved.size(),
                "realPageOrderVerified", true,
                "reversedOrderRejectedByOracle", true,
                "toolPreservedStepOrder", resolved.stream().map(step -> step.instruction()).toList()
                        .equals(requested.stream().map(WalkthroughStepRequest::instruction).toList()),
                "allStepsSeparatelyCited", resolved.stream().allMatch(step ->
                        step.orderBasis() == WalkthroughOrderBasis.RULE_ORDER
                                && step.citationIds().equals(List.of(chunkId))),
                "citationPublished", published.citations().stream().anyMatch(citation -> citation.pageFrom() == page));
    }

    private boolean markersAreInOrder(String page, List<String> markers) {
        int previous = -1;
        for (String marker : markers) {
            int next = page.indexOf(marker.toLowerCase(Locale.ROOT), previous + 1);
            if (next < 0 || next <= previous) return false;
            previous = next;
        }
        return true;
    }

    private List<String> values(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText().toLowerCase(Locale.ROOT)));
        return List.copyOf(values);
    }

    private String normalized(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
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
