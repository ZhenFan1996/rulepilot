package com.rulepilot.modelconfig.adapter.in.web;

import com.rulepilot.modelconfig.AccountQuotaExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ModelConfigurationExceptionHandler {

    @ExceptionHandler(AccountQuotaExceededException.class)
    ResponseEntity<ProblemDetail> handleQuota(AccountQuotaExceededException failure) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYMENT_REQUIRED,
                "本月平台模型额度已用完。你可以稍后再试、联系管理员调整额度，或改用自己的 API Key。");
        problem.setTitle("模型额度已用完");
        problem.setProperty("code", failure.getMessage());
        problem.setProperty("recoverable", true);
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(problem);
    }
}
