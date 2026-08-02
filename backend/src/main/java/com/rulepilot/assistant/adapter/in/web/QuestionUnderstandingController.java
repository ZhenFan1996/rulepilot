package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.QuestionUnderstanding;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/question-understanding")
public class QuestionUnderstandingController {

    private final QuestionUnderstanding understanding;

    public QuestionUnderstandingController(QuestionUnderstanding understanding) {
        this.understanding = understanding;
    }

    @PostMapping
    QuestionResponse understand(@PathVariable UUID versionId, @RequestBody QuestionRequest request) {
        UnderstoodQuestion result = understanding.understand(
                request.question(),
                new QuestionContext(versionId));
        return QuestionResponse.from(result);
    }

    record QuestionRequest(String question) {}

    record QuestionResponse(
            UUID documentVersionId,
            String originalQuestion,
            String normalizedQuestion,
            QuestionType type,
            List<String> terms,
            Set<MissingQuestionContext> missingContext,
            boolean needsClarification) {

        static QuestionResponse from(UnderstoodQuestion result) {
            return new QuestionResponse(
                    result.documentVersionId(),
                    result.originalQuestion(),
                    result.normalizedQuestion(),
                    result.type(),
                    result.terms(),
                    result.missingContext(),
                    result.needsClarification());
        }
    }
}
