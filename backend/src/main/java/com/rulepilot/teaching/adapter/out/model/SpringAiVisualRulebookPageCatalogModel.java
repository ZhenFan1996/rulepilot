package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ModelExecutionIdentity;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogContractViolation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRejection;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRepairCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** Reads page-local evidence without asking the vision model to certify a complete rule or lesson inventory. */
@Component
@Primary
public class SpringAiVisualRulebookPageCatalogModel implements VisualRulebookPageCatalogModel {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String OUTPUT_SCHEMA = """
            {"pages":[{"pageNumber":1,"printedTerms":["visible term"],"keywords":[],
            "ruleGroups":[{"identifier":"visible label","fact":"faithful page-local fact",
            "quantitySpans":["optional exact visible span"]}]}]}
            Only pageNumber and pages are structurally required. ruleGroups may be empty. Additive fields are allowed.
            """;

    private final RuntimeModelConfiguration models;
    private final FakeVisualRulebookPageCatalogModel fake;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final String systemPrompt;

    public SpringAiVisualRulebookPageCatalogModel(
            RuntimeModelConfiguration models,
            FakeVisualRulebookPageCatalogModel fake,
            @Value("classpath:prompts/visual-page-teaching-catalog-v6-literal-quantity-spans-system.txt")
                    Resource systemPrompt)
            throws IOException {
        this.models = models;
        this.fake = fake;
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        if (this.systemPrompt.isBlank()) {
            throw new IllegalArgumentException("visual page catalog prompt must not be blank");
        }
    }

    @Override
    public boolean available(String owner) {
        return !models.usesFake(Role.VISUAL, owner) && models.supportsVision(Role.VISUAL, owner);
    }

    @Override
    public CatalogDraft summarize(CatalogRequest request) {
        return summarizeForTeaching(request);
    }

    @Override
    public CatalogDraft summarizeForTeaching(CatalogRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return fake.summarizeForTeaching(request);
        }
        return requestAndValidate(request, owner, null);
    }

    @Override
    public CatalogDraft correctTeachingCatalog(CatalogRequest request, TeachingCatalogRejection rejection) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return VisualRulebookPageCatalogModel.super.correctTeachingCatalog(request, rejection);
        }
        return requestAndValidate(request, owner, rejection);
    }

    @Override
    public Optional<ModelExecutionIdentity> teachingStartupExecutionIdentity(String owner) {
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return Optional.empty();
        }
        String provider = models.providerFor(Role.VISUAL, owner);
        return Optional.of(new ModelExecutionIdentity(
                provider,
                models.modelNameFor(Role.VISUAL, owner)));
    }

    private CatalogDraft requestAndValidate(
            CatalogRequest request,
            String owner,
            TeachingCatalogRejection rejection) {
        String candidate = requestCandidate(request, owner, rejection);
        try {
            return normalizeTeachingPageBindings(request, parse(candidate));
        } catch (CatalogValidationFailure invalid) {
            TeachingCatalogRejection observation = new TeachingCatalogRejection(
                    candidate == null ? "" : candidate,
                    invalid.code,
                    invalid.path,
                    invalid.getMessage(),
                    OUTPUT_SCHEMA,
                    request.pages().stream().map(PageImageInput::pageNumber).collect(Collectors.toSet()));
            throw new TeachingCatalogContractViolation(invalid.code, observation, invalid);
        }
    }

    private String requestCandidate(
            CatalogRequest request,
            String owner,
            TeachingCatalogRejection rejection) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        String provider = models.providerFor(Role.VISUAL, owner);
        if ("qwen".equals(provider)) {
            prompt = prompt.options(OpenAiChatOptions.builder()
                    .model(models.modelNameFor(Role.VISUAL, owner))
                    .temperature(0.0)
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                    .extraBody(Map.of("enable_thinking", false)));
        }
        return prompt.system(systemPrompt)
                .user(user -> {
                    user.text("""
                                    Supplied PDF page numbers: {pageNumbers}
                                    Rulebook title: {rulebookTitle}
                                    Attachment mapping: {attachmentOrder}
                                    Return readable page-local evidence using this minimal schema:
                                    {schema}
                                    Correction observation (empty for candidate 1):
                                    {correctionObservation}
                                    """)
                            .param("pageNumbers", request.pages().stream().map(PageImageInput::pageNumber).toList())
                            .param("rulebookTitle", request.rulebookTitle() == null
                                    ? "not supplied"
                                    : request.rulebookTitle())
                            .param("schema", OUTPUT_SCHEMA)
                            .param("correctionObservation", correctionObservation(rejection))
                            .param("attachmentOrder", java.util.stream.IntStream.range(0, request.pages().size())
                                    .mapToObj(index -> "image " + (index + 1) + " = PDF page "
                                            + request.pages().get(index).pageNumber())
                                    .collect(Collectors.joining("; ")));
                    request.pages().stream().map(images::prepare).forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()),
                            new ByteArrayResource(page.content())));
                })
                .call()
                .content();
    }

    private static String correctionObservation(TeachingCatalogRejection rejection) {
        if (rejection == null) return "No rejected candidate.";
        return """
                The complete previous JSON was rejected. Regenerate the complete object; do not patch fragments.
                code: %s
                path: %s
                reason: %s
                schema: %s
                allowedPageIds: %s
                rejectedCandidate: %s
                """.formatted(
                rejection.code(),
                rejection.path(),
                rejection.reason(),
                rejection.schema(),
                rejection.allowedPageIds(),
                rejection.candidateJson());
    }

    static CatalogDraft parse(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw invalid(TeachingCatalogRepairCode.MALFORMED_JSON, "$", "model returned no JSON", null);
        }
        final JsonNode root;
        try {
            root = JSON.readTree(candidate);
        } catch (JsonProcessingException malformed) {
            throw invalid(TeachingCatalogRepairCode.MALFORMED_JSON, "$", deepestMessage(malformed), malformed);
        }
        if (root == null || !root.isObject()) {
            throw invalid(TeachingCatalogRepairCode.SCHEMA_MISMATCH, "$", "root must be an object", null);
        }
        JsonNode pages = root.get("pages");
        if (pages == null || !pages.isArray() || pages.isEmpty()) {
            throw invalid(TeachingCatalogRepairCode.SCHEMA_MISMATCH, "$.pages", "pages must be a non-empty array", null);
        }
        List<PageSummary> summaries = new ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            summaries.add(pageSummary(pages.get(index), index));
        }
        return new CatalogDraft(summaries);
    }

    private static PageSummary pageSummary(JsonNode page, int index) {
        String path = "$.pages[" + index + "]";
        if (!page.isObject()) {
            throw invalid(TeachingCatalogRepairCode.SCHEMA_MISMATCH, path, "page must be an object", null);
        }
        JsonNode pageNumberValue = page.get("pageNumber");
        if (pageNumberValue == null || !pageNumberValue.canConvertToInt() || pageNumberValue.intValue() < 1) {
            throw invalid(
                    TeachingCatalogRepairCode.SCHEMA_MISMATCH,
                    path + ".pageNumber",
                    "pageNumber must be a positive integer",
                    null);
        }
        int pageNumber = pageNumberValue.intValue();
        List<RuleGroupFact> groups = ruleGroups(page.get("ruleGroups"), path + ".ruleGroups");
        String printedTerms = joinedText(page.get("printedTerms"), "; ");
        if (printedTerms.isBlank()) printedTerms = "No reliably readable printed term.";
        String factualSummary = joinedText(page.get("factualSummary"), "\n");
        String groupFacts = groups.stream()
                .map(group -> group.identifier() + ": [" + group.label() + "] " + group.fact())
                .collect(Collectors.joining("\n"));
        if (factualSummary.isBlank()) factualSummary = groupFacts;
        else if (!groupFacts.isBlank()) factualSummary = factualSummary + "\n" + groupFacts;
        if (factualSummary.isBlank()) {
            factualSummary = "No reliably readable rule fragment was observed on this page.";
        }
        return new PageSummary(
                pageNumber,
                printedTerms,
                factualSummary,
                strings(page.get("keywords")),
                List.of(),
                groups);
    }

    private static List<RuleGroupFact> ruleGroups(JsonNode value, String path) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) {
            return List.of();
        }
        List<RuleGroupFact> groups = new ArrayList<>();
        Set<String> observed = new LinkedHashSet<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode group = value.get(index);
            if (!group.isObject()) {
                continue;
            }
            String identifier = optionalText(group.get("identifier"));
            String fact = optionalText(group.get("fact"));
            if (identifier == null || fact == null) continue;
            String label = optionalText(group.get("label"));
            String identity = identifier + "\u0000" + fact;
            if (observed.add(identity)) {
                groups.add(new RuleGroupFact(identifier, label == null ? identifier : label, fact));
            }
        }
        return List.copyOf(groups);
    }

    private static String optionalText(JsonNode value) {
        return value == null || !value.isTextual() || value.textValue().isBlank()
                ? null
                : value.textValue().strip();
    }

    private static List<String> strings(JsonNode value) {
        if (value == null || value.isNull()) return List.of();
        if (value.isTextual()) return value.textValue().isBlank() ? List.of() : List.of(value.textValue().strip());
        if (!value.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual() && !item.textValue().isBlank()) values.add(item.textValue().strip());
        });
        return values.stream().distinct().toList();
    }

    private static String joinedText(JsonNode value, String separator) {
        if (value == null || value.isNull()) return "";
        if (value.isTextual()) return value.textValue().strip();
        return String.join(separator, strings(value));
    }

    static CatalogDraft normalizeTeachingPageBindings(CatalogRequest request, CatalogDraft draft) {
        if (draft == null) {
            throw invalid(
                    TeachingCatalogRepairCode.PAGE_BINDING_MISMATCH,
                    "$.pages",
                    "model returned no page draft",
                    null);
        }
        List<Integer> requested = request.pages().stream().map(PageImageInput::pageNumber).toList();
        if (requested.size() == 1 && draft.pages().size() == 1) {
            PageSummary page = draft.pages().getFirst();
            return new CatalogDraft(List.of(new PageSummary(
                    requested.getFirst(),
                    page.printedTerms(),
                    page.factualSummary(),
                    page.keywords(),
                    List.of(),
                    page.ruleGroupFacts())));
        }
        Set<Integer> expected = Set.copyOf(requested);
        Set<Integer> returned = draft.pages().stream().map(PageSummary::pageNumber).collect(Collectors.toSet());
        if (draft.pages().size() != requested.size() || returned.size() != requested.size() || !returned.equals(expected)) {
            throw invalid(
                    TeachingCatalogRepairCode.PAGE_BINDING_MISMATCH,
                    "$.pages[*].pageNumber",
                    "page identities must match the supplied attachments exactly",
                    null);
        }
        return new CatalogDraft(draft.pages().stream()
                .map(page -> new PageSummary(
                        page.pageNumber(),
                        page.printedTerms(),
                        page.factualSummary(),
                        page.keywords(),
                        List.of(),
                        page.ruleGroupFacts()))
                .sorted(java.util.Comparator.comparingInt(page -> requested.indexOf(page.pageNumber())))
                .toList());
    }

    private static CatalogValidationFailure invalid(
            TeachingCatalogRepairCode code,
            String path,
            String reason,
            Throwable cause) {
        return new CatalogValidationFailure(code, path, reason, cause);
    }

    private static String deepestMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class CatalogValidationFailure extends RuntimeException {
        private final TeachingCatalogRepairCode code;
        private final String path;

        private CatalogValidationFailure(
                TeachingCatalogRepairCode code,
                String path,
                String reason,
                Throwable cause) {
            super(reason, cause);
            this.code = code;
            this.path = path;
        }
    }
}
