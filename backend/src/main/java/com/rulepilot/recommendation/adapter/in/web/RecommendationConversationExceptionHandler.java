package com.rulepilot.recommendation.adapter.in.web;

import com.rulepilot.recommendation.RecommendationConversationInputException;
import com.rulepilot.recommendation.application.RecommendationConversationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
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

    @ExceptionHandler(RecommendationConversationInputException.class)
    ResponseEntity<ProblemDetail> handleInput(
            RecommendationConversationInputException failure,
            HttpServletRequest request) {
        String requestedLocale = request.getParameter("locale");
        boolean chinese = "zh".equals(Locale.forLanguageTag(requestedLocale == null ? "" : requestedLocale)
                .getLanguage());
        String detail = chinese
                ? "消息最多 500 个字符；本次输入没有被截断，也没有进入推荐处理。"
                : "Messages can contain at most 500 characters; this input was not truncated or passed to recommendation processing.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle(chinese ? "消息超过长度限制" : "Message exceeds the length limit");
        problem.setProperty("code", failure.code().name().toLowerCase(Locale.ROOT));
        problem.setProperty("limit", failure.limit());
        problem.setProperty("actual", failure.actual());
        return ResponseEntity.badRequest().body(problem);
    }

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
