package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.regex.Pattern;

/** Separates a quoted rule from a conclusion that applies it to a player's stated table state. */
final class AnswerBasisPolicy {

    private static final Pattern PLAYER_STATED_TABLE_STATE = Pattern.compile(
            "(?iu)(?:\\bI\\s+(?:have|just|already|now|currently)|\\bwe\\s+(?:have|just|already|now)|"
                    + "我(?:已经|刚|现在|目前)|我们(?:已经|刚|现在|目前)|当前(?:局面|回合|阶段)|此时|"
                    + "这一步(?:之后|完成后)?|(?:拿|放|移动|补|完成)(?:了|完|后))");

    private AnswerBasisPolicy() {}

    static ModelDraft classify(ModelRequest request, ModelDraft draft) {
        if (draft == null || !draft.answerable() || !PLAYER_STATED_TABLE_STATE.matcher(request.question()).find()) {
            return draft;
        }
        if ("GROUNDED_APPLICATION".equalsIgnoreCase(draft.answerBasis())) {
            return draft;
        }
        return new ModelDraft(
                draft.answerable(),
                draft.insufficiencyReason(),
                draft.shortVerdict(),
                draft.explanation(),
                draft.citationIds(),
                draft.exceptions(),
                draft.confidence(),
                "GROUNDED_APPLICATION");
    }
}
