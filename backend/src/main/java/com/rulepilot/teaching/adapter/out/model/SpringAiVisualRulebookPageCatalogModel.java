package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** Bounded vision pass that turns page images into an auditable, page-numbered planning catalog. */
@Component
@Primary
public class SpringAiVisualRulebookPageCatalogModel implements VisualRulebookPageCatalogModel {

    private static final String SYSTEM = """
            You are a meticulous board-game rulebook visual reader. Inspect only the supplied page images.
            Build a per-page rule evidence ledger for a later planner and writer; do not write a lesson and do not
            invent rules.
            For every supplied page return exactly one item with its pageNumber. printedTerms must preserve the visible
            printed headings, labels, action names, component names, icon labels, and numbers in their original language
            (verbatim where readable). factualSummary is a complete Simplified-Chinese evidence ledger of the rules on
            that one page, using original-language terms in parentheses where needed. It may report only what is visibly
            printed or shown, including diagrams and examples.

            For an operational rule page, do not summarize away details: record every visible branch and its exact
            condition, whether it is optional or mandatory, timing and frequency limits, which exact components are
            removed/drawn/replaced/left in place, and the order in which those changes happen. Preserve distinctions
            such as 3 versus 4, any versus all, may versus must, once versus repeatable, and token versus tile. A later
            writer will cite this ledger, so an incomplete generic paraphrase is invalid. Do not infer an unprinted
            convention such as clockwise turn order, a redraw of a different component, or a player-specific setup.
            keywords must contain 2-8 short original-language terms that occur visibly on that same page. If a page is
            a cover, index, illustration, or unreadable, say so explicitly instead of guessing. The page number and every
            reported term must come from an attached page.
            Return structured data only.
            """;

    private final RuntimeModelConfiguration models;
    private final FakeVisualRulebookPageCatalogModel fake;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();

    public SpringAiVisualRulebookPageCatalogModel(
            RuntimeModelConfiguration models, FakeVisualRulebookPageCatalogModel fake) {
        this.models = models;
        this.fake = fake;
    }

    @Override
    public CatalogDraft summarize(CatalogRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return fake.summarize(request);
        }
        RuntimeException firstFailure;
        try {
            return validate(request, summarizeOnce(request, owner, ""));
        } catch (RuntimeException failure) {
            firstFailure = failure;
        }
        try {
            return validate(request, summarizeOnce(request, owner, """
                    The first catalog was invalid. Return one summary for every supplied page, use only supplied page
                    numbers, preserve visible original-language terms, and return structured data only.
                    """));
        } catch (RuntimeException failure) {
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    private CatalogDraft summarizeOnce(CatalogRequest request, String owner, String correction) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            prompt = prompt.options(OpenAiChatOptions.builder()
                    .extraBody(Map.of("enable_thinking", false))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()));
        }
        return prompt.system(SYSTEM)
                .user(user -> {
                    user.text("""
                                    Attached rulebook page numbers: {pageNumbers}
                                    {correction}
                                    Return a JSON object with a pages array. Each array item must have pageNumber,
                                    printedTerms, factualSummary, and keywords.
                                    """)
                            .param("pageNumbers", request.pages().stream().map(PageImageInput::pageNumber).toList())
                            .param("correction", correction);
                    request.pages().stream().map(images::prepare).forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .entity(CatalogDraft.class);
    }

    private CatalogDraft validate(CatalogRequest request, CatalogDraft draft) {
        if (draft == null) throw new IllegalArgumentException("visual page catalog model returned no draft");
        Set<Integer> requested = request.pages().stream().map(PageImageInput::pageNumber).collect(Collectors.toSet());
        Set<Integer> returned = draft.pages().stream().map(PageSummary::pageNumber).collect(Collectors.toSet());
        if (draft.pages().size() != requested.size() || !returned.equals(requested)) {
            throw new IllegalArgumentException("visual page catalog did not cover exactly the supplied pages");
        }
        return new CatalogDraft(draft.pages().stream()
                .sorted(java.util.Comparator.comparingInt(PageSummary::pageNumber))
                .toList());
    }
}
