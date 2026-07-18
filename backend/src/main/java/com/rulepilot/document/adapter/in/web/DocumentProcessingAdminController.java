package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.application.ManualDocumentRetryService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/processing-jobs")
@Profile("!test")
public class DocumentProcessingAdminController {

    private final ManualDocumentRetryService retries;

    public DocumentProcessingAdminController(ManualDocumentRetryService retries) {
        this.retries = retries;
    }

    @PostMapping("/{jobId}/retry")
    ResponseEntity<ManualDocumentRetryService.RetryAccepted> retry(@PathVariable UUID jobId) {
        return ResponseEntity.accepted().body(retries.retry(jobId));
    }
}
