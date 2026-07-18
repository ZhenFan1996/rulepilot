package com.rulepilot.ruling.adapter.in.web;

import com.rulepilot.ruling.application.RulingVersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = ConfirmedRulingController.class)
public class RulingExceptionHandler {

    @ExceptionHandler(RulingVersionConflictException.class)
    ResponseEntity<ProblemDetail> handleVersionConflict(
            RulingVersionConflictException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The confirmed ruling changed after this editor loaded it.");
        problem.setType(URI.create("urn:rulepilot:problem:ruling-version-conflict"));
        problem.setTitle("Confirmed ruling edit conflict");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "RULING_VERSION_CONFLICT");
        problem.setProperty("currentVersion", exception.currentVersion());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
