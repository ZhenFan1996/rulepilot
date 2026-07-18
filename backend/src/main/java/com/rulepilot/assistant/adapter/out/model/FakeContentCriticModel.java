package com.rulepilot.assistant.adapter.out.model;

import com.rulepilot.assistant.ContentCriticModel;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "rulepilot.critic.provider", havingValue = "fake", matchIfMissing = true)
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
