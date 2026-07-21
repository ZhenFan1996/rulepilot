package com.rulepilot.ingestion.layout;

import java.util.List;

/** Immutable, document-derived evidence prepared before an Agent plans a lesson. */
public record RulebookUnderstanding(
        List<PageBlock> pageBlocks,
        List<TerminologyCandidate> terminology,
        List<RuleEvidenceItem> inventory,
        List<CoverageLedgerEntry> coverageLedger) {

    public RulebookUnderstanding {
        pageBlocks = List.copyOf(pageBlocks);
        terminology = List.copyOf(terminology);
        inventory = List.copyOf(inventory);
        coverageLedger = List.copyOf(coverageLedger);
        if (inventory.size() != coverageLedger.size()) {
            throw new IllegalArgumentException("every inventory item requires a coverage ledger entry");
        }
    }

    public record PageBlock(
            int pageNumber,
            int blockIndex,
            int readingOrder,
            BlockRole role,
            String text,
            Rectangle rectangle,
            Integer headingBlockIndex) {
        public PageBlock {
            if (pageNumber < 1 || blockIndex < 0 || readingOrder < 0 || role == null
                    || text == null || text.isBlank() || rectangle == null
                    || (headingBlockIndex != null && headingBlockIndex < 0)) {
                throw new IllegalArgumentException("page block is invalid");
            }
            text = text.strip();
        }
    }

    public record Rectangle(int x, int y, int width, int height) {
        public Rectangle {
            if (x < 0 || y < 0 || width < 1 || height < 1 || x + width > 1_000 || y + height > 1_000) {
                throw new IllegalArgumentException("page rectangle is invalid");
            }
        }
    }

    public record TerminologyCandidate(String term, String normalizedTerm, int pageNumber, int blockIndex) {
        public TerminologyCandidate {
            if (term == null || term.isBlank() || normalizedTerm == null || normalizedTerm.isBlank()
                    || pageNumber < 1 || blockIndex < 0) {
                throw new IllegalArgumentException("terminology candidate is invalid");
            }
            term = term.strip();
            normalizedTerm = normalizedTerm.strip();
        }
    }

    public record RuleEvidenceItem(String key, String kind, String label, int pageNumber, int blockIndex) {
        public RuleEvidenceItem {
            if (key == null || key.isBlank() || kind == null || kind.isBlank() || label == null || label.isBlank()
                    || pageNumber < 1 || blockIndex < 0) {
                throw new IllegalArgumentException("rule evidence item is invalid");
            }
            key = key.strip();
            kind = kind.strip();
            label = label.strip();
        }
    }

    public record CoverageLedgerEntry(String inventoryKey, CoverageState state, String exclusionReason) {
        public CoverageLedgerEntry {
            if (inventoryKey == null || inventoryKey.isBlank() || state == null
                    || (state == CoverageState.EXCLUDED && (exclusionReason == null || exclusionReason.isBlank()))
                    || (state != CoverageState.EXCLUDED && exclusionReason != null && !exclusionReason.isBlank())) {
                throw new IllegalArgumentException("coverage ledger entry is invalid");
            }
            inventoryKey = inventoryKey.strip();
            exclusionReason = exclusionReason == null ? null : exclusionReason.strip();
        }
    }

    public enum BlockRole {
        HEADING,
        BODY,
        FOOTER
    }

    public enum CoverageState {
        UNPLANNED,
        EXCLUDED
    }
}
