package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.document.DocumentProcessing.ExtractedTextBlock;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.CoverageLedgerEntry;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.CoverageState;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.PageBlock;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.RuleEvidenceItem;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.TerminologyCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Preserves source layout as evidence and creates an intentionally unjudged whole-document ledger.
 * It does not decide what a game rule means; that is an Agent task with this evidence as input.
 */
@Component
public class RulebookUnderstandingBuilder {

    private static final int MAX_TERM_LENGTH = 120;
    static final String VISUAL_PAGE_PLACEHOLDER =
            "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
    public RulebookUnderstanding build(List<ExtractedPage> pages) {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("extracted pages are required");
        }
        List<PageBlock> blocks = pageBlocks(pages);
        List<TerminologyCandidate> terms = terminology(blocks);
        List<RuleEvidenceItem> inventory = inventory(blocks);
        List<CoverageLedgerEntry> ledger = inventory.stream()
                .map(item -> new CoverageLedgerEntry(item.key(), CoverageState.UNPLANNED, null))
                .toList();
        return new RulebookUnderstanding(blocks, terms, inventory, ledger);
    }

    private List<PageBlock> pageBlocks(List<ExtractedPage> pages) {
        List<PageBlock> result = new ArrayList<>();
        pages.stream().sorted(Comparator.comparingInt(ExtractedPage::pageNumber)).forEach(page -> {
            List<ExtractedTextBlock> rawBlocks = page.textBlocks().isEmpty()
                    ? fallbackBlock(page)
                    : page.textBlocks();
            Integer currentHeading = null;
            int blockIndex = 0;
            for (ExtractedTextBlock raw : rawBlocks) {
                BlockRole role = role(raw);
                if (role == BlockRole.HEADING) {
                    currentHeading = blockIndex;
                }
                result.add(new PageBlock(
                        page.pageNumber(),
                        blockIndex,
                        raw.readingOrder(),
                        role,
                        raw.text(),
                        new Rectangle(raw.x(), raw.y(), raw.width(), raw.height()),
                        role == BlockRole.HEADING ? null : currentHeading));
                blockIndex++;
            }
        });
        if (result.isEmpty()) {
            throw new IllegalArgumentException("rulebook contains no extractable layout evidence");
        }
        return List.copyOf(result);
    }

    private List<ExtractedTextBlock> fallbackBlock(ExtractedPage page) {
        String text = page.text().strip();
        String evidence = text.isEmpty() ? VISUAL_PAGE_PLACEHOLDER : text;
        return List.of(new ExtractedTextBlock(0, evidence, 0, 0, 1_000, 1_000));
    }

    private BlockRole role(ExtractedTextBlock block) {
        String text = block.text().strip();
        if (block.y() >= 945 && text.length() <= 30 && text.matches(".*\\d.*")) {
            return BlockRole.FOOTER;
        }
        if (!text.contains("\n")
                && text.length() <= 160
                && text.split("\\s+").length <= 14
                && !text.matches(".*[.!?。！？]$")
                && (isMostlyUppercase(text) || block.y() < 420)) {
            return BlockRole.HEADING;
        }
        return BlockRole.BODY;
    }

    private boolean isMostlyUppercase(String text) {
        int letters = 0;
        int uppercase = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetter(character)) {
                letters++;
                if (Character.isUpperCase(character)) uppercase++;
            }
        }
        return letters >= 3 && uppercase * 100 >= letters * 70;
    }

    private List<TerminologyCandidate> terminology(List<PageBlock> blocks) {
        Map<String, TerminologyCandidate> candidates = new LinkedHashMap<>();
        for (PageBlock block : blocks) {
            if (block.role() == BlockRole.HEADING) {
                addTerm(candidates, block.text(), block);
            }
        }
        return List.copyOf(candidates.values());
    }

    private void addTerm(Map<String, TerminologyCandidate> candidates, String rawTerm, PageBlock block) {
        String term = rawTerm.replaceAll("\\s+", " ").strip();
        if (term.length() < 2 || term.length() > MAX_TERM_LENGTH) return;
        String normalized = term.toLowerCase(Locale.ROOT);
        candidates.putIfAbsent(normalized, new TerminologyCandidate(
                term, normalized, block.pageNumber(), block.blockIndex()));
    }

    private List<RuleEvidenceItem> inventory(List<PageBlock> blocks) {
        return blocks.stream()
                .filter(block -> block.role() != BlockRole.FOOTER)
                .map(block -> new RuleEvidenceItem(
                        "p%d-b%d".formatted(block.pageNumber(), block.blockIndex()),
                        block.role() == BlockRole.HEADING ? "HEADING_EVIDENCE" : "TEXT_EVIDENCE",
                        "Page %d, block %d".formatted(block.pageNumber(), block.blockIndex()),
                        block.pageNumber(),
                        block.blockIndex()))
                .toList();
    }
}
