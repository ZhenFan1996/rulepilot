package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.application.OfficialRulebookImportIdentityException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = UserRuleDocumentController.class)
class OfficialRulebookImportExceptionHandler {

    @ExceptionHandler(OfficialRulebookImportIdentityException.class)
    ResponseEntity<ProblemDetail> handleIdentity(OfficialRulebookImportIdentityException failure) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
        problem.setTitle("Rulebook identity review required");
        problem.setProperty("code", "RULEBOOK_" + failure.code().name());
        problem.setProperty("identityReview", failure.review());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
