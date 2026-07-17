package com.rulepilot.ingestion;

import java.util.List;
import java.util.UUID;

public interface RuleStructureCatalog {

    StructureView structure(UUID documentVersionId);

    record StructureView(List<SectionView> sections, int presentSections, int requiredSections) {
        public StructureView {
            sections = List.copyOf(sections);
        }
    }

    record SectionView(String type, String label, boolean present, String content, List<Integer> pageNumbers) {
        public SectionView {
            pageNumbers = List.copyOf(pageNumbers);
        }
    }
}
