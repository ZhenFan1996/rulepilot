package com.rulepilot.assistant.adapter.out.model;

import com.rulepilot.assistant.RuleAnswerModel;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeRuleAnswerModel implements RuleAnswerModel {

    @Override
    public String providerId() {
        return "fake";
    }

    @Override
    public ModelDraft compose(ModelRequest request) {
        EvidenceInput source = request.evidence().getFirst();
        return new ModelDraft(
                source.excerpt(),
                "依据“" + source.heading() + "”中的规则内容：" + source.excerpt(),
                List.of(source.chunkId()),
                List.of(),
                "MEDIUM");
    }
}
