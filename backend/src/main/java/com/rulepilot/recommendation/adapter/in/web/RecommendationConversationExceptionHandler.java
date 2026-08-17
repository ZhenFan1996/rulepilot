package com.rulepilot.recommendation.adapter.in.web;

import com.rulepilot.recommendation.application.RecommendationConversationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = BggRecommendationAgentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class RecommendationConversationExceptionHandler {

    @ExceptionHandler(RecommendationConversationException.class)
    ResponseEntity<ProblemDetail> handle(RecommendationConversationException failure) {
        HttpStatus status = failure.code() == RecommendationConversationException.Code.NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, failure.getMessage());
        problem.setTitle("Recommendation conversation could not continue");
        problem.setProperty("code", failure.code().name().toLowerCase(java.util.Locale.ROOT));
        return ResponseEntity.status(status).body(problem);
    }
}
