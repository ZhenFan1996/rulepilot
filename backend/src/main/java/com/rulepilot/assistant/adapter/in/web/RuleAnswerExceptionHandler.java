package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.assistant.application.RuleAnswerRateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = StructuredRuleAnswerController.class)
public class RuleAnswerExceptionHandler {

    private static final URI TYPE = URI.create("urn:rulepilot:problem:rule-answer-rate-limit");
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @ExceptionHandler(RuleAnswerRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(
            RuleAnswerRateLimitExceededException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "The rule answer request limit was reached. Retry later.");
        problem.setType(TYPE);
        problem.setTitle("Too many rule questions");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "RULE_ANSWER_RATE_LIMITED");
        problem.setProperty("dimension", exception.dimension().name());
        problem.setProperty("traceId", traceId(request));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .body(problem);
    }

    private String traceId(HttpServletRequest request) {
        String candidate = request.getHeader("X-Trace-Id");
        return candidate != null && SAFE_TRACE_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
