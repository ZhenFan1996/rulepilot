package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.StructuredRuleAnswerService;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/document-versions/{versionId}/answers")
public class StructuredRuleAnswerController {

    private final StructuredRuleAnswerService answers;

    public StructuredRuleAnswerController(StructuredRuleAnswerService answers) {
        this.answers = answers;
    }

    @PostMapping
    StructuredRuleAnswer answer(@PathVariable UUID versionId, @RequestBody AnswerRequest request) {
        return answers.answer(
                request.question(),
                new QuestionContext(
                        versionId, request.currentLessonSection(), request.gamePhase(),
                        request.playerCount(), request.activeExpansions()));
    }

    record AnswerRequest(
            String question,
            String currentLessonSection,
            String gamePhase,
            Integer playerCount,
            Set<String> activeExpansions) {}
}
