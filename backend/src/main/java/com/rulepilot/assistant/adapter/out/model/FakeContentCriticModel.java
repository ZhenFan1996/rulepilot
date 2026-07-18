package com.rulepilot.assistant.adapter.out.model;

import com.rulepilot.assistant.ContentCriticModel;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeContentCriticModel implements ContentCriticModel {

    @Override
    public String providerId() {
        return "fake";
    }

    @Override
    public CritiqueDraft critique(com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest request) {
        return new CritiqueDraft(List.of());
    }
}
