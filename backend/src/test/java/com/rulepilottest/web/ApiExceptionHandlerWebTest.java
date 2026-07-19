package com.rulepilottest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rulepilot.shared.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerWebTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsSafeProblemDetailsWithStableCodeAndTraceId() throws Exception {
        mockMvc.perform(get("/test/invalid").header("X-Trace-Id", "trace-test-001"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rulepilot:problem:invalid-request"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.traceId").value("trace-test-001"))
                .andExpect(jsonPath("$.detail").value("The request could not be accepted."));
    }

    @Test
    void prefersCurrentTelemetryTraceOverClientHeader() throws Exception {
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");
        try {
            mockMvc.perform(get("/test/invalid").header("X-Trace-Id", "client-controlled"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.traceId").value("0123456789abcdef0123456789abcdef"));
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void returnsRetryableCapacityProblemWhenTheGenerationQueueIsFull() throws Exception {
        mockMvc.perform(get("/test/capacity"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:rulepilot:problem:capacity-exceeded"))
                .andExpect(jsonPath("$.code").value("GENERATION_CAPACITY_EXCEEDED"));
    }

    @RestController
    static class FailingController {

        @GetMapping("/test/invalid")
        void invalidRequest() {
            throw new IllegalArgumentException("internal input detail must not leak");
        }

        @GetMapping("/test/capacity")
        void capacityExceeded() {
            throw new TaskRejectedException("internal queue detail must not leak");
        }
    }
}
