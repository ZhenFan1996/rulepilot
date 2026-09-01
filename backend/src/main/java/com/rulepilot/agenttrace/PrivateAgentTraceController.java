package com.rulepilot.agenttrace;

import com.rulepilot.agenttrace.AgentTraceExporter.PreparedExport;
import com.rulepilot.agenttrace.PrivateAgentTraceService.ExportLease;
import com.rulepilot.agenttrace.PrivateAgentTraceService.TraceAccessException;
import com.rulepilot.agenttrace.PrivateAgentTraceService.TraceStatus;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/private-agent-trace")
@ConditionalOnProperty(name = "rulepilot.private-agent-trace.enabled", havingValue = "true")
public class PrivateAgentTraceController {

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final PrivateAgentTraceService traces;
    private final AgentTraceExporter exporter;

    public PrivateAgentTraceController(PrivateAgentTraceService traces, AgentTraceExporter exporter) {
        if (traces == null || exporter == null) {
            throw new IllegalArgumentException("private agent trace controller dependencies are required");
        }
        this.traces = traces;
        this.exporter = exporter;
    }

    @PostMapping("/start")
    ResponseEntity<TraceStatusResponse> start(Principal principal, HttpSession session) {
        return metadata(HttpStatus.CREATED, TraceStatusResponse.from(traces.start(principal, session)));
    }

    @GetMapping
    ResponseEntity<TraceStatusResponse> status(Principal principal, HttpSession session) {
        Optional<TraceStatus> status = traces.status(principal, session);
        return status.map(value -> metadata(HttpStatus.OK, TraceStatusResponse.from(value)))
                .orElseGet(() -> ResponseEntity.noContent().headers(privateHeaders()).build());
    }

    @PostMapping("/seal")
    ResponseEntity<TraceStatusResponse> seal(Principal principal, HttpSession session) {
        return metadata(HttpStatus.OK, TraceStatusResponse.from(traces.seal(principal, session)));
    }

    @GetMapping("/export")
    ResponseEntity<StreamingResponseBody> export(Principal principal, HttpSession session) {
        ExportLease lease = traces.beginExport(principal, session);
        try {
            PreparedExport prepared = exporter.prepare(lease.snapshot());
            HttpHeaders headers = privateHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.set(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                            .filename(prepared.filename(), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            StreamingResponseBody body = output -> {
                try (lease) {
                    prepared.writeTo(output);
                }
            };
            return new ResponseEntity<>(body, headers, HttpStatus.OK);
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    @DeleteMapping
    ResponseEntity<Void> delete(Principal principal, HttpSession session) {
        traces.delete(principal, session);
        return ResponseEntity.noContent().headers(privateHeaders()).build();
    }

    @ExceptionHandler(TraceAccessException.class)
    ResponseEntity<ProblemDetail> traceFailure(TraceAccessException exception) {
        HttpStatus status = switch (exception.code()) {
            case ACTIVE_TRACE_EXISTS, OWNER_TRACE_EXISTS, TRACE_CONFLICT -> HttpStatus.CONFLICT;
            case TRACE_EXPORT_BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case TRACE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TRACE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.code().name());
        problem.setTitle("Private agent trace request failed");
        problem.setProperty("code", exception.code().name());
        return new ResponseEntity<>(problem, privateHeaders(), status);
    }

    private ResponseEntity<TraceStatusResponse> metadata(HttpStatus status, TraceStatusResponse body) {
        return new ResponseEntity<>(body, privateHeaders(), status);
    }

    private HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        headers.set(HttpHeaders.PRAGMA, "no-cache");
        headers.set(HttpHeaders.EXPIRES, "0");
        headers.set(X_CONTENT_TYPE_OPTIONS, "nosniff");
        return headers;
    }

    record TraceStatusResponse(
            String state,
            String integrity,
            String incompleteReason,
            Instant createdAt,
            Instant captureUntil,
            Instant expiresAt,
            Instant sealedAt,
            long eventCount,
            long storedBytes) {
        static TraceStatusResponse from(TraceStatus status) {
            return new TraceStatusResponse(
                    status.state().name(),
                    status.integrity().name(),
                    status.incompleteReason(),
                    status.createdAt(),
                    status.captureUntil(),
                    status.expiresAt(),
                    status.sealedAt(),
                    status.eventCount(),
                    status.storedBytes());
        }
    }
}
