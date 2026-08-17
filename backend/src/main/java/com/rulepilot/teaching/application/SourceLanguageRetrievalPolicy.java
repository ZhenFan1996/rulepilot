package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
public final class SourceLanguageRetrievalPolicy {

    private SourceLanguageRetrievalPolicy() {}

    public static void validate(OutlineRequest request, OutlineDraft outline) {
        if (outline != null && !outline.sourceCoverageSlots().isEmpty()) {
            TeachingSourceCoverageContract.requireCompleteSourceContract(request, outline);
        }
    }
}
