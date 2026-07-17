package com.rulepilot.shared.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final URI INVALID_REQUEST_TYPE = URI.create("urn:rulepilot:problem:invalid-request");
    private static final URI INTERNAL_ERROR_TYPE = URI.create("urn:rulepilot:problem:internal-error");
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(
            IllegalArgumentException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST_TYPE,
                "Invalid request",
                "INVALID_REQUEST",
                "The request could not be accepted.",
                request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        String traceId = traceId(request);
        LOGGER.error("Unhandled web exception traceId={} path={}", traceId, request.getRequestURI(), exception);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
        problem.setType(INTERNAL_ERROR_TYPE);
        problem.setTitle("Internal server error");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("traceId", traceId);
        return ResponseEntity.internalServerError().body(problem);
    }

    private ProblemDetail problem(
            HttpStatus status,
            URI type,
            String title,
            String code,
            String detail,
            HttpServletRequest request) {
        String traceId = traceId(request);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId);
        return problem;
    }

    private String traceId(HttpServletRequest request) {
        String candidate = request.getHeader("X-Trace-Id");
        if (candidate != null && SAFE_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
