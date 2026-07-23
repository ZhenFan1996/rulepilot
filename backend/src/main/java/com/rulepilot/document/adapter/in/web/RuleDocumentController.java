package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.application.RuleDocumentRepository;
import com.rulepilot.document.application.UploadRuleDocumentService;
import com.rulepilot.document.application.PhotographedRulebookUploadService;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.RuleDocument;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/editions/{editionId}/documents")
@Profile("!test")
public class RuleDocumentController {

    private final UploadRuleDocumentService documents;
    private final PhotographedRulebookUploadService photographedDocuments;

    public RuleDocumentController(
            UploadRuleDocumentService documents, PhotographedRulebookUploadService photographedDocuments) {
        this.documents = documents;
        this.photographedDocuments = photographedDocuments;
    }

    @GetMapping
    List<DocumentResponse> list(@PathVariable UUID editionId, Principal principal) {
        return documents.list(editionId, principal.getName()).stream().map(DocumentResponse::from).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse upload(
            @PathVariable UUID editionId,
            @RequestParam String title,
            @RequestParam DocumentSourceType sourceType,
            @RequestParam(required = false) String officialSourceUrl,
            @RequestParam(required = false) String officialCoverUrl,
            @RequestParam("file") MultipartFile file,
            Principal principal) {
        try {
            var result = documents.upload(
                    editionId,
                    title,
                    sourceType,
                    officialSourceUrl,
                    officialCoverUrl,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getInputStream(),
                    principal.getName());
            return UploadResponse.from(result);
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not read uploaded file", exception);
        }
    }

    @PostMapping(path = "/photo-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse uploadPhotographedRulebook(
            @PathVariable UUID editionId,
            @RequestParam(required = false) String title,
            @RequestParam DocumentSourceType sourceType,
            @RequestParam(required = false) String officialSourceUrl,
            @RequestParam(required = false) String officialCoverUrl,
            @RequestParam("photos") List<MultipartFile> photos,
            Principal principal) {
        try {
            var result = photographedDocuments.upload(
                    editionId,
                    title,
                    sourceType,
                    officialSourceUrl,
                    officialCoverUrl,
                    photoPages(photos),
                    principal.getName());
            return UploadResponse.from(result);
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not read photographed rulebook pages", exception);
        }
    }

    private List<PhotographedRulebookUploadService.PhotoPage> photoPages(List<MultipartFile> photos) throws IOException {
        try {
            return photos.stream().map(photo -> {
                try {
                    return new PhotographedRulebookUploadService.PhotoPage(
                            photo.getOriginalFilename(), photo.getContentType(), photo.getBytes());
                } catch (IOException exception) {
                    throw new PhotographedPageReadException(exception);
                }
            }).toList();
        } catch (PhotographedPageReadException exception) {
            throw (IOException) exception.getCause();
        }
    }

    private static final class PhotographedPageReadException extends RuntimeException {
        private PhotographedPageReadException(IOException cause) {
            super(cause);
        }
    }

    record UploadResponse(DocumentDetails document, VersionDetails version, boolean duplicate) {
        static UploadResponse from(UploadRuleDocumentService.UploadResult result) {
            return new UploadResponse(
                    DocumentDetails.from(result.document()), VersionDetails.from(result.version()), result.duplicate());
        }
    }

    record DocumentResponse(DocumentDetails document, VersionDetails latestVersion) {
        static DocumentResponse from(RuleDocumentRepository.DocumentSummary summary) {
            return new DocumentResponse(
                    DocumentDetails.from(summary.document()), VersionDetails.from(summary.latestVersion()));
        }
    }

    record DocumentDetails(
            UUID id,
            UUID gameEditionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String officialCoverUrl,
            String createdBy,
            Instant createdAt) {
        static DocumentDetails from(RuleDocument document) {
            return new DocumentDetails(
                    document.id(),
                    document.gameEditionId(),
                    document.title(),
                    document.sourceType(),
                    document.officialSourceUrl(),
                    document.officialCoverUrl(),
                    document.createdBy(),
                    document.createdAt());
        }
    }

    record VersionDetails(
            UUID id,
            int versionNumber,
            String originalFilename,
            String checksum,
            long size,
            String contentType,
            String status,
            Instant createdAt) {
        static VersionDetails from(DocumentVersion version) {
            return new VersionDetails(
                    version.id(),
                    version.versionNumber(),
                    version.originalFilename(),
                    version.checksum(),
                    version.size(),
                    version.contentType(),
                    version.status().name(),
                    version.createdAt());
        }
    }
}
