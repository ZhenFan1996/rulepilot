package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.application.UploadRuleDocumentService;
import com.rulepilot.document.domain.DocumentSourceType;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
@Profile("!test")
public class UserRuleDocumentController {

    private final UploadRuleDocumentService documents;

    public UserRuleDocumentController(UploadRuleDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    List<RuleDocumentController.DocumentResponse> list(Principal principal) {
        return documents.listOwned(principal.getName()).stream()
                .map(RuleDocumentController.DocumentResponse::from)
                .toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    RuleDocumentController.UploadResponse upload(
            @RequestParam String title,
            @RequestParam DocumentSourceType sourceType,
            @RequestParam("file") MultipartFile file,
            Principal principal) {
        try {
            var result = documents.upload(
                    null,
                    title,
                    sourceType,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getInputStream(),
                    principal.getName());
            return RuleDocumentController.UploadResponse.from(result);
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not read uploaded file", exception);
        }
    }

    @PutMapping("/{documentId}/edition")
    RuleDocumentController.DocumentDetails assign(
            @PathVariable UUID documentId, @RequestBody AssignEditionRequest request, Principal principal) {
        return RuleDocumentController.DocumentDetails.from(
                documents.assign(documentId, request.editionId(), principal.getName()));
    }

    record AssignEditionRequest(UUID editionId) {
        AssignEditionRequest {
            if (editionId == null) {
                throw new IllegalArgumentException("game edition is required");
            }
        }
    }
}
