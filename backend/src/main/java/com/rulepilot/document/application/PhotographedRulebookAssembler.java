package com.rulepilot.document.application;

import java.util.List;

/** Turns validated, ordered rulebook page images into the immutable PDF source used by ingestion. */
public interface PhotographedRulebookAssembler {

    AssembledRulebook assemble(List<PhotographedRulebookUploadService.PhotoPage> pages);

    record AssembledRulebook(String originalFilename, byte[] pdf) {
        public AssembledRulebook {
            if (originalFilename == null || originalFilename.isBlank() || pdf == null || pdf.length == 0) {
                throw new IllegalArgumentException("assembled photographed rulebook is invalid");
            }
        }
    }
}
