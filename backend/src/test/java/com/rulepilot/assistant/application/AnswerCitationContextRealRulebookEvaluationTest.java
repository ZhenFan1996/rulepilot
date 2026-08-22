package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContext;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolEvidenceHandles;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.adapter.out.model.SpringAiNativeToolModel;
import com.rulepilot.assistant.adapter.out.model.SpringAiRuleAnswerModel;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("real-citation-context-evaluation")
class AnswerCitationContextRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void expandsAcrossAPageBoundaryAndPublishesAnInspectedPlayerAnswer() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_CITATION_CONTEXT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode node = mapper.readTree(root.resolve(
                ".local/agent-evaluation/citation-context-cases.json").toFile()).path("cases").get(0);
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(node.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required");
        ProviderConfiguration provider = provider();
        UUID versionId = UUID.nameUUIDFromBytes(
                ("citation-context:" + node.path("caseId").asText()).getBytes(StandardCharsets.UTF_8));
        PdfChunkCorpus corpus = new PdfChunkCorpus(pdf, versionId);
        RuleEvidenceHit anchor = corpus.pageSegment(
                node.path("anchorPage").asInt(), node.path("anchorSegment").asInt());
        UUID runId = UUID.randomUUID();
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(120));
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        ChatModel chatModel = chatModel(provider);
        RuntimeModelConfiguration runtime = runtime(chatModel, provider);
        var registry = new NativeAgentToolRegistry(
                List.of(
                        new ExpandRuleEvidenceContextNativeTool(corpus, mapper),
                        new ReadRulePagesNativeTool(corpus, mapper)),
                mapper,
                candidate -> candidate.ownerUsername().equals(scope.ownerUsername())
                        && candidate.documentVersionId().equals(scope.documentVersionId()));
        BoundedNativeToolAgent agent = new BoundedNativeToolAgent(
                new SpringAiNativeToolModel(runtime), registry, mock(AgentExecutionControl.class), audited, mapper);

        RunResult refinement = agent.run(new RunRequest(
                com.rulepilot.assistant.NativeAgentTool.Role.ANSWER,
                scope,
                """
                You are filling a rule-evidence coverage gap, not answering the player. The supplied canonical anchor
                ends at a page boundary and may omit the continuation. First call expand_rule_evidence_context on the
                supplied evidence handle with the requested radius. Inspect which neighboring page continues the
                ending, scoring, award-tie, and final-tiebreak sequence. Then call read_rule_pages for the exact pages
                needed to cover every part of the question. Nearby context is not automatically applicable. Return
                exactly EVIDENCE_READY only after both tools succeed. Never use outside game knowledge.
                """,
                "Player question: " + node.path("question").asText()
                        + "\nCanonical anchor handle: " + anchor.chunkId()
                        + "\nAnchor page: " + anchor.pageFrom()
                        + "\nAnchor excerpt: " + bounded(anchor.excerpt(), 900)
                        + "\nContext radius: " + node.path("contextRadius").asInt(),
                "EVIDENCE_REFINEMENT_UNAVAILABLE",
                3,
                256,
                Set.of("expand_rule_evidence_context", "read_rule_pages")));

        assertThat(refinement.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(refinement.observations()).extracting(observation -> observation.toolName())
                .contains("expand_rule_evidence_context", "read_rule_pages");
        assertThat(refinement.observations().stream()
                        .filter(observation -> "expand_rule_evidence_context".equals(observation.toolName()))
                        .flatMap(observation -> evidencePages(observation.observation().data()).stream()))
                .contains(17);

        Set<UUID> observedIds = NativeToolEvidenceHandles.prioritized(refinement, 24);
        List<RuleEvidenceHit> observed = corpus.findByIds(observedIds);
        assertThat(observed).anyMatch(evidence -> evidence.pageFrom() == 17);
        List<EvidenceInput> inputs = observed.stream().map(evidence -> new EvidenceInput(
                evidence.chunkId(), evidence.sectionType(), evidence.heading(), evidence.excerpt(),
                evidence.pageFrom(), evidence.pageTo())).toList();
        List<HybridEvidenceHit> publicationEvidence = observed.stream()
                .map(evidence -> new HybridEvidenceHit(evidence, 1.0, 1, null, true))
                .toList();
        SpringAiRuleAnswerModel answerModel = answerModel(runtime);
        ModelRequest answerRequest = new ModelRequest(
                node.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN), inputs);
        ModelDraft draft = prepared(answerRequest, answerModel.compose(answerRequest));
        Files.writeString(
                root.resolve(".local/agent-evaluation/citation-context-last-draft.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(draft) + "\n",
                StandardCharsets.UTF_8);
        StructuredRuleAnswer answer = publish(versionId, draft, publicationEvidence);
        Evaluation evaluation = evaluate(node, answer);
        if (!evaluation.passes()) {
            draft = prepared(answerRequest, answerModel.revise(answerRequest, draft, List.of(
                    "The player answer omitted or misstated part of a cross-page rule sequence.",
                    "Answer every requested part from the supplied citations: when the game ends; every before-scoring step; all four ordered additional Reputation categories; the award amount; what every tied player receives; all final Victory Point tiebreakers; and the terminal shared-victory outcome.",
                    "Do not use an award category as a final Victory Point tiebreaker unless the evidence explicitly lists it there.",
                    "Keep the explanation under 1500 characters and cite only supplied evidence.")));
            answer = publish(versionId, draft, publicationEvidence);
            evaluation = evaluate(node, answer);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("caseId", node.path("caseId").asText());
        report.put("provider", provider.provider());
        report.put("toolTrace", audited.toolNames);
        report.put("contextReachedNextPage", true);
        report.put("shortVerdict", answer.shortVerdict());
        report.put("explanation", answer.explanation());
        report.put("citationPages", answer.citations().stream().map(citation -> citation.pageFrom()).distinct().toList());
        report.put("requiredDetailsPresent", evaluation.requiredDetailsPresent());
        report.put("forbiddenClaimsAbsent", evaluation.forbiddenClaimsAbsent());
        report.put("playerAnswerInspected", !answer.shortVerdict().isBlank() && !answer.explanation().isBlank());
        Path output = root.resolve(".local/agent-evaluation/citation-context-generated-answer.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);

        assertThat(evaluation.requiredDetailsPresent()).isTrue();
        assertThat(evaluation.forbiddenClaimsAbsent()).isTrue();
        assertThat(answer.citations()).anyMatch(citation -> citation.pageFrom() == 17);
    }

    @Test
    void selectsAndExplainsTheMostDirectClauseFromRealRulebooks() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_SOURCE_EVIDENCE_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(root.resolve(
                ".local/agent-evaluation/source-evidence-cases.json").toFile());
        ProviderConfiguration provider = provider();
        SpringAiRuleAnswerModel model = answerModel(runtime(chatModel(provider), provider));
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/source-evidence-generated-answers.json");

        for (JsonNode node : configuration.path("cases")) {
            results.add(runSourceCase(root, node, model));
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "schemaVersion", 1,
                    "generatedAt", Instant.now().toString(),
                    "provider", provider.provider(),
                    "results", List.copyOf(results))) + "\n", StandardCharsets.UTF_8);
        }

        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("singleDirectCitation", true)
                .containsEntry("sourceClauseComplete", true)
                .containsEntry("visibleAnswerComplete", true)
                .containsEntry("noPageOnlyRedirect", true)
                .containsEntry("noUnsupportedScopeExpansion", true)
                .containsEntry("noInventedQuotation", true)
                .containsEntry("playerAnswerInspected", true));
    }

    @Test
    void validatesPermissionAndProhibitionAnswersFromRealRulebooks() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_PERMISSION_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(root.resolve(
                ".local/agent-evaluation/permission-ruling-cases.json").toFile());
        ProviderConfiguration provider = provider();
        SpringAiRuleAnswerModel model = answerModel(runtime(chatModel(provider), provider));
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/permission-ruling-generated-answers.json");

        for (JsonNode node : configuration.path("cases")) {
            results.add(runPermissionCase(root, node, model));
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "schemaVersion", 1,
                    "generatedAt", Instant.now().toString(),
                    "provider", provider.provider(),
                    "results", List.copyOf(results))) + "\n", StandardCharsets.UTF_8);
        }

        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("permissionValidated", true)
                .containsEntry("singleDirectCitation", true)
                .containsEntry("expectedDirectionPresent", true)
                .containsEntry("answerComplete", true)
                .containsEntry("forbiddenClaimsAbsent", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runPermissionCase(
            Path root, JsonNode node, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = node.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(node.path("file").asText());
        if (!Files.isRegularFile(pdf)) throw new IllegalStateException("real rulebook is required for " + caseId);
        int page = node.path("page").asInt();
        String clause = directClause(extractPage(pdf, page), node.path("anchor").asText());
        UUID versionId = UUID.nameUUIDFromBytes(("permission:" + caseId).getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes(("permission:" + caseId + ":" + page)
                .getBytes(StandardCharsets.UTF_8));
        ModelRequest request = new ModelRequest(
                node.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, "DIRECT_RULE_CLAUSE", node.path("heading").asText(),
                        clause, page, page)),
                Set.of(EvidenceNeed.DIRECT_RULE),
                AnswerAid.PERMISSION);
        assertThat(AnswerPermissionResolver.requiresPermission(request))
                .as("permission intent for %s", caseId)
                .isTrue();
        RuleEvidenceHit source = new RuleEvidenceHit(
                chunkId, versionId, "DIRECT_RULE_CLAUSE", node.path("heading").asText(), clause, page, page, 1.0);
        List<HybridEvidenceHit> evidence = List.of(new HybridEvidenceHit(source, 1.0, 1, null, true));
        ModelDraft draft = prepared(request, model.compose(request));
        List<String> repairStages = new ArrayList<>();
        try {
            new AnswerPermissionResolver().resolve(request, draft);
        } catch (RuntimeException invalid) {
            repairStages.add("PERMISSION_DIRECTION");
            draft = prepared(request, model.revise(request, draft, permissionFeedback()));
            new AnswerPermissionResolver().resolve(request, draft);
        }
        StructuredRuleAnswer answer = publish(versionId, draft, evidence);
        PermissionEvaluation evaluation = evaluatePermission(node, answer, page);
        if (!evaluation.passes()) {
            repairStages.add("PLAYER_ANSWER_FIDELITY");
            draft = prepared(request, model.revise(request, draft, permissionFeedback()));
            new AnswerPermissionResolver().resolve(request, draft);
            answer = publish(versionId, draft, evidence);
            evaluation = evaluatePermission(node, answer, page);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", node.path("question").asText());
        result.put("sourcePage", page);
        result.put("sourceExcerptShownToPlayer", clause);
        result.put("shortVerdict", answer.shortVerdict());
        result.put("explanation", answer.explanation());
        result.put("repairStages", List.copyOf(repairStages));
        result.put("answerPublished", answer.status().publishesConclusion());
        result.put("permissionValidated", true);
        result.put("singleDirectCitation", evaluation.singleDirectCitation());
        result.put("expectedDirectionPresent", evaluation.expectedDirectionPresent());
        result.put("answerComplete", evaluation.answerComplete());
        result.put("forbiddenClaimsAbsent", evaluation.forbiddenClaimsAbsent());
        result.put("playerAnswerInspected", !answer.shortVerdict().isBlank() && !answer.explanation().isBlank());
        return Map.copyOf(result);
    }

    private PermissionEvaluation evaluatePermission(JsonNode node, StructuredRuleAnswer answer, int page) {
        String verdict = normalized(answer.shortVerdict());
        String visible = normalized(answer.shortVerdict() + " " + answer.explanation());
        boolean deny = java.util.regex.Pattern.compile(
                        "(?iu)^no\\b|\\b(?:cannot|can't|can never|may not|not allowed)\\b")
                .matcher(verdict).find();
        boolean allow = java.util.regex.Pattern.compile(
                        "(?iu)^yes\\b|\\b(?:may|can|allowed|permitted)\\b")
                .matcher(java.util.regex.Pattern.compile(
                                "(?iu)\\b(?:cannot|can't|can never|may not|not allowed)\\b")
                        .matcher(verdict).replaceAll(" "))
                .find();
        boolean expectedDirection = "DENY".equals(node.path("expectedDirection").asText()) ? deny && !allow : allow && !deny;
        boolean complete = containsTermGroups(visible, node.path("answerTerms"));
        boolean forbiddenAbsent = values(node.path("forbiddenAnswerTerms")).stream()
                .map(this::normalized)
                .noneMatch(visible::contains);
        return new PermissionEvaluation(
                answer.citations().size() == 1 && answer.citations().getFirst().pageFrom() == page,
                expectedDirection,
                complete,
                forbiddenAbsent);
    }

    private List<String> permissionFeedback() {
        return List.of(
                "Answer the can/may question directly in shortVerdict with yes, no, can, or cannot.",
                "Preserve the modal direction of the supplied rule clause; never reverse permission and prohibition.",
                "Preserve every prerequisite, exception, actor, object, and timing boundary.",
                "Use exactly the supplied citation and explain only claims it directly supports.");
    }

    private record PermissionEvaluation(
            boolean singleDirectCitation,
            boolean expectedDirectionPresent,
            boolean answerComplete,
            boolean forbiddenClaimsAbsent) {
        boolean passes() {
            return singleDirectCitation && expectedDirectionPresent && answerComplete && forbiddenClaimsAbsent;
        }
    }

    private Map<String, Object> runSourceCase(
            Path root, JsonNode node, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = node.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(node.path("file").asText());
        if (!Files.isRegularFile(pdf)) throw new IllegalStateException("real rulebook is required for " + caseId);
        int page = node.path("page").asInt();
        String pageText = extractPage(pdf, page);
        String clause = directClause(pageText, node.path("anchor").asText());
        UUID versionId = UUID.nameUUIDFromBytes(("source:" + caseId).getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes(("source:" + caseId + ":" + page)
                .getBytes(StandardCharsets.UTF_8));
        ModelRequest request = new ModelRequest(
                node.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.SOURCE, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, "DIRECT_RULE_CLAUSE", node.path("heading").asText(),
                        clause, page, page)));
        RuleEvidenceHit source = new RuleEvidenceHit(
                chunkId, versionId, "DIRECT_RULE_CLAUSE", node.path("heading").asText(), clause, page, page, 1.0);
        List<HybridEvidenceHit> evidence = List.of(new HybridEvidenceHit(source, 1.0, 1, null, true));
        ModelDraft draft = prepared(request, model.compose(request));
        List<String> repairStages = new ArrayList<>();
        StructuredRuleAnswer answer;
        SourceEvaluation evaluation;
        try {
            new AnswerSourceEvidenceResolver().resolve(request, draft);
            answer = publish(versionId, draft, evidence);
            evaluation = evaluateSource(node, answer, clause, page);
        } catch (RuntimeException invalid) {
            repairStages.add("SOURCE_STRUCTURE_OR_PUBLICATION");
            draft = prepared(request, model.revise(request, draft, sourceFeedback(node)));
            new AnswerSourceEvidenceResolver().resolve(request, draft);
            answer = publish(versionId, draft, evidence);
            evaluation = evaluateSource(node, answer, clause, page);
        }
        if (!evaluation.passes()) {
            repairStages.add("PLAYER_ANSWER_FIDELITY");
            draft = prepared(request, model.revise(request, draft, sourceFeedback(node)));
            new AnswerSourceEvidenceResolver().resolve(request, draft);
            answer = publish(versionId, draft, evidence);
            evaluation = evaluateSource(node, answer, clause, page);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", node.path("question").asText());
        result.put("sourcePage", page);
        result.put("sourceExcerptShownToPlayer", clause);
        result.put("shortVerdict", answer.shortVerdict());
        result.put("explanation", answer.explanation());
        result.put("citationPages", answer.citations().stream().map(citation -> citation.pageFrom()).toList());
        result.put("repairStages", List.copyOf(repairStages));
        result.put("answerPublished", answer.status().publishesConclusion());
        result.put("singleDirectCitation", evaluation.singleDirectCitation());
        result.put("sourceClauseComplete", evaluation.sourceClauseComplete());
        result.put("visibleAnswerComplete", evaluation.visibleAnswerComplete());
        result.put("noPageOnlyRedirect", evaluation.noPageOnlyRedirect());
        result.put("noUnsupportedScopeExpansion", evaluation.noUnsupportedScopeExpansion());
        result.put("noInventedQuotation", evaluation.noInventedQuotation());
        result.put("playerAnswerInspected", !answer.shortVerdict().isBlank() && !answer.explanation().isBlank());
        return Map.copyOf(result);
    }

    private SourceEvaluation evaluateSource(
            JsonNode node, StructuredRuleAnswer answer, String sourceClause, int page) {
        String visible = answer.shortVerdict() + " " + answer.explanation();
        boolean sourceComplete = containsTermGroups(sourceClause, node.path("sourceTerms"));
        boolean answerComplete = containsTermGroups(visible, node.path("answerTerms"));
        boolean noRedirect = !java.util.regex.Pattern.compile(
                        "(?iu)\\b(?:see|read|check|consult) (?:the )?(?:excerpt|page|source|citation|rulebook)\\b")
                .matcher(answer.shortVerdict()).find();
        String allowedQuotedText = normalized(sourceClause + " " + node.path("heading").asText());
        boolean noInventedQuote = quotedSpans(visible).stream()
                .map(quote -> normalized(quote).replaceFirst("[,:;.!?]+$", ""))
                .allMatch(allowedQuotedText::contains);
        boolean noUnsupportedScopeExpansion = values(node.path("forbiddenAnswerTerms")).stream()
                .map(this::normalized)
                .noneMatch(normalized(visible)::contains);
        return new SourceEvaluation(
                answer.citations().size() == 1 && answer.citations().getFirst().pageFrom() == page,
                sourceComplete,
                answerComplete,
                noRedirect,
                noUnsupportedScopeExpansion,
                noInventedQuote);
    }

    private List<String> sourceFeedback(JsonNode node) {
        return List.of(
                "This is a source-focused answer. Use exactly the supplied direct citation and keep answerBasis DIRECT_RULE.",
                "Answer the rule question in shortVerdict; do not redirect the player to a page or excerpt.",
                "Explain the cited clause in plain player language while preserving every condition, modal, actor, object, and timing boundary.",
                "Do not add ownership or hand location. Do not change 'during a turn or phase' into 'at the end or completion of' that turn or phase unless the clause says so.",
                "Preserve the exact grammatical number of capitalized official terms; never change Persuasion to Persuasions.",
                "Do not claim that the rule or excerpt has no other restriction, condition, exception, limit, or exact timing; explain only what it affirmatively states.",
                "The visible answer must cover " + values(node.path("answerTerms")) + ".",
                "Remove unsupported scope expansions such as " + values(node.path("forbiddenAnswerTerms")) + ".",
                "Do not invent or reconstruct a quotation. The application displays the exact source excerpt separately.");
    }

    private String extractPage(Path pdf, int page) throws Exception {
        try (var document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document).replaceAll("\\s+", " ").strip();
        }
    }

    private String directClause(String pageText, String anchor) {
        return java.util.Arrays.stream(pageText.split("(?<=[.!?])\\s+"))
                .map(String::strip)
                .filter(sentence -> normalized(sentence).contains(normalized(anchor)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("direct source anchor was not found: " + anchor));
    }

    private boolean containsTermGroups(String text, JsonNode groups) {
        String candidate = normalized(text);
        return values(groups).stream().allMatch(group -> java.util.Arrays.stream(group.split("\\|"))
                .map(this::normalized).anyMatch(candidate::contains));
    }

    private List<String> quotedSpans(String text) {
        return java.util.regex.Pattern.compile("[\"“]([^\"”]{3,240})[\"”]")
                .matcher(text).results().map(match -> match.group(1)).toList();
    }

    private record SourceEvaluation(
            boolean singleDirectCitation,
            boolean sourceClauseComplete,
            boolean visibleAnswerComplete,
            boolean noPageOnlyRedirect,
            boolean noUnsupportedScopeExpansion,
            boolean noInventedQuotation) {
        boolean passes() {
            return singleDirectCitation && sourceClauseComplete && visibleAnswerComplete
                    && noPageOnlyRedirect && noUnsupportedScopeExpansion && noInventedQuotation;
        }
    }

    private StructuredRuleAnswer publish(
            UUID versionId, ModelDraft draft, List<HybridEvidenceHit> publicationEvidence) {
        return new AnswerPublicationValidator(new PolicyEvidenceVerifier())
                .publish(versionId, draft, publicationEvidence);
    }

    private ModelDraft prepared(ModelRequest request, ModelDraft draft) {
        var preparation = AnswerDraftPublicationPolicy.prepare(request, draft);
        assertThat(preparation.ready()).isTrue();
        return preparation.draft();
    }

    private Evaluation evaluate(JsonNode node, StructuredRuleAnswer answer) {
        String visible = normalized(answer.shortVerdict() + " " + answer.explanation());
        boolean required = values(node.path("requiredAnswerTerms")).stream().allMatch(group ->
                java.util.Arrays.stream(group.split("\\|"))
                        .map(this::normalized)
                        .anyMatch(visible::contains));
        boolean forbidden = values(node.path("forbiddenAnswerTerms")).stream()
                .map(this::normalized)
                .noneMatch(visible::contains);
        return new Evaluation(required, forbidden);
    }

    private List<Integer> evidencePages(Map<String, Object> data) {
        List<Integer> pages = new ArrayList<>();
        for (String key : List.of("anchors", "surroundingEvidence", "evidence")) {
            Object value = data.get(key);
            if (!(value instanceof List<?> entries)) continue;
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("pageFrom") instanceof Number page) {
                    pages.add(page.intValue());
                }
            }
        }
        return List.copyOf(pages);
    }

    private SpringAiRuleAnswerModel answerModel(RuntimeModelConfiguration runtime) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(VersionedAgentPrompts.class);
            context.refresh();
            return new SpringAiRuleAnswerModel(
                    runtime, context.getBean(VersionedAgentPrompts.class));
        }
    }

    private RuntimeModelConfiguration runtime(ChatModel chatModel, ProviderConfiguration provider) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(any(RuntimeModelConfiguration.Role.class), any(String.class))).thenReturn(chatModel);
        when(configuration.providerFor(any(RuntimeModelConfiguration.Role.class), any(String.class)))
                .thenReturn(provider.provider());
        when(configuration.usesDeepSeekNonThinkingGeneration(
                any(RuntimeModelConfiguration.Role.class), any(String.class))).thenReturn(true);
        when(configuration.modelFor(Role.ANSWER)).thenReturn(chatModel);
        when(configuration.providerFor(Role.ANSWER)).thenReturn(provider.provider());
        when(configuration.modelNameFor(Role.ANSWER)).thenReturn(provider.model());
        when(configuration.usesFake(Role.ANSWER)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.ANSWER)).thenReturn(true);
        return configuration;
    }

    private ChatModel chatModel(ProviderConfiguration provider) {
        return new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
    }

    private ProviderConfiguration provider() {
        return new ProviderConfiguration(
                "deepseek",
                requiredEnvironment("DEEPSEEK_API_KEY"),
                requiredEnvironment("DEEPSEEK_BASE_URL"),
                requiredEnvironment("DEEPSEEK_MODEL"));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private List<String> values(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private String normalized(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String bounded(String value, int maximum) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private record Evaluation(boolean requiredDetailsPresent, boolean forbiddenClaimsAbsent) {
        boolean passes() {
            return requiredDetailsPresent && forbiddenClaimsAbsent;
        }
    }

    private record ProviderConfiguration(String provider, String apiKey, String baseUrl, String model) {}

    private static final class DirectAuditedInvocations implements AuditedAgentInvocations {
        private final List<String> toolNames = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            T result = invocation.get();
            if (result instanceof NativeAgentToolRegistry.ToolExecution execution) {
                toolNames.add(execution.specification().name());
            }
            return result;
        }

        @Override
        public void record(UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {}
    }

    private static final class PdfChunkCorpus implements AssistantReadTools {
        private final UUID versionId;
        private final List<RuleEvidenceHit> chunks;

        private PdfChunkCorpus(Path pdf, UUID versionId) throws Exception {
            this.versionId = versionId;
            this.chunks = chunks(pdf, versionId);
        }

        @Override
        public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
            assertScope(request.documentVersionId());
            return List.of();
        }

        @Override
        public RuleEvidenceContext readRuleEvidenceContext(
                UUID documentVersionId, Set<UUID> anchorEvidenceIds, int radius) {
            assertScope(documentVersionId);
            List<RuleEvidenceHit> anchors = chunks.stream()
                    .filter(chunk -> anchorEvidenceIds.contains(chunk.chunkId()))
                    .toList();
            LinkedHashSet<RuleEvidenceHit> surrounding = new LinkedHashSet<>();
            for (RuleEvidenceHit anchor : anchors) {
                int index = chunks.indexOf(anchor);
                for (int candidate = Math.max(0, index - radius);
                        candidate <= Math.min(chunks.size() - 1, index + radius); candidate++) {
                    if (candidate != index) surrounding.add(chunks.get(candidate));
                }
            }
            return new RuleEvidenceContext(
                    anchors.stream().map(this::ruleEvidence).toList(),
                    surrounding.stream().map(this::ruleEvidence).toList());
        }

        @Override
        public List<RuleEvidence> readRuleEvidencePages(
                UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
            assertScope(documentVersionId);
            if (includePageImages) throw new IllegalArgumentException("text-only evaluation corpus");
            return chunks.stream()
                    .filter(chunk -> pageNumbers.contains(chunk.pageFrom()))
                    .map(this::ruleEvidence)
                    .toList();
        }

        private RuleEvidenceHit pageSegment(int page, int oneBasedSegment) {
            return chunks.stream()
                    .filter(chunk -> chunk.pageFrom() == page)
                    .skip(oneBasedSegment - 1L)
                    .findFirst()
                    .orElseThrow();
        }

        private List<RuleEvidenceHit> findByIds(Set<UUID> ids) {
            return chunks.stream().filter(chunk -> ids.contains(chunk.chunkId())).toList();
        }

        private void assertScope(UUID documentVersionId) {
            if (!versionId.equals(documentVersionId)) throw new IllegalArgumentException("document scope mismatch");
        }

        private RuleEvidence ruleEvidence(RuleEvidenceHit source) {
            return new RuleEvidence(
                    source.chunkId(), source.documentVersionId(), source.sectionType(), source.heading(),
                    source.excerpt(), source.pageFrom(), source.pageTo());
        }

        private static List<RuleEvidenceHit> chunks(Path pdf, UUID versionId) throws Exception {
            List<RuleEvidenceHit> result = new ArrayList<>();
            try (var document = Loader.loadPDF(pdf.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String text = stripper.getText(document).replaceAll("\\s+", " ").strip();
                    int segment = 0;
                    for (int start = 0; start < text.length(); start += 1000) {
                        int end = Math.min(text.length(), start + 1200);
                        String excerpt = text.substring(start, end).strip();
                        result.add(new RuleEvidenceHit(
                                UUID.nameUUIDFromBytes((versionId + ":" + page + ":" + segment)
                                        .getBytes(StandardCharsets.UTF_8)),
                                versionId,
                                "PAGE",
                                "Rulebook page " + page + " segment " + (segment + 1),
                                excerpt,
                                page,
                                page,
                                1.0));
                        segment++;
                    }
                }
            }
            return List.copyOf(result);
        }
    }
}
