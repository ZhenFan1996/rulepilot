package com.rulepilot.document.adapter.in.web;

import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.document.application.RuleDocumentMetadataSuggestionService;
import com.rulepilot.document.application.RuleDocumentMetadataConfirmationService;
import com.rulepilot.document.application.RuleDocumentMetadataConfirmationService.Confirmation;
import com.rulepilot.document.application.OfficialRulebookImportService;
import com.rulepilot.document.application.UploadRuleDocumentService;
import com.rulepilot.document.application.RuleDocumentRemovalService;
import com.rulepilot.document.application.PhotographedRulebookUploadService;
import com.rulepilot.document.domain.DocumentSourceType;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final PhotographedRulebookUploadService photographedDocuments;
    private final RuleDocumentRemovalService removals;
    private final RuleDocumentMetadataSuggestionService metadataSuggestions;
    private final RuleDocumentMetadataConfirmationService metadataConfirmations;
    private final OfficialRulebookImportService officialImports;

    public UserRuleDocumentController(
            UploadRuleDocumentService documents,
            PhotographedRulebookUploadService photographedDocuments,
            RuleDocumentRemovalService removals,
            RuleDocumentMetadataSuggestionService metadataSuggestions,
            RuleDocumentMetadataConfirmationService metadataConfirmations,
            OfficialRulebookImportService officialImports) {
        this.documents = documents;
        this.photographedDocuments = photographedDocuments;
        this.removals = removals;
        this.metadataSuggestions = metadataSuggestions;
        this.metadataConfirmations = metadataConfirmations;
        this.officialImports = officialImports;
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
            @RequestParam(required = false) String officialSourceUrl,
            @RequestParam(required = false) String officialCoverUrl,
            @RequestParam("file") MultipartFile file,
            Principal principal) {
        try {
            var result = documents.upload(
                    null,
                    title,
                    sourceType,
                    officialSourceUrl,
                    officialCoverUrl,
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

    @PostMapping(path = "/photo-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    RuleDocumentController.UploadResponse uploadPhotographedRulebook(
            @RequestParam(required = false) String title,
            @RequestParam DocumentSourceType sourceType,
            @RequestParam(required = false) String officialSourceUrl,
            @RequestParam(required = false) String officialCoverUrl,
            @RequestParam("photos") List<MultipartFile> photos,
            Principal principal) {
        try {
            var result = photographedDocuments.upload(
                    null,
                    title,
                    sourceType,
                    officialSourceUrl,
                    officialCoverUrl,
                    photoPages(photos),
                    principal.getName());
            return RuleDocumentController.UploadResponse.from(result);
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not read photographed rulebook pages", exception);
        }
    }

    @PutMapping("/{documentId}/edition")
    RuleDocumentController.DocumentDetails assign(
            @PathVariable UUID documentId, @RequestBody AssignEditionRequest request, Principal principal) {
        return RuleDocumentController.DocumentDetails.from(
                documents.assign(documentId, request.editionId(), principal.getName()));
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID documentId, Principal principal) {
        removals.removeOwned(documentId, principal.getName());
    }

    @GetMapping("/{documentId}/bgg-suggestions")
    List<BggSuggestionResponse> bggSuggestions(@PathVariable UUID documentId, Principal principal) {
        return metadataSuggestions.suggest(documentId, principal.getName()).stream()
                .map(BggSuggestionResponse::from)
                .toList();
    }

    @PostMapping("/{documentId}/bgg-link")
    BggLinkResponse confirmBggLink(
            @PathVariable UUID documentId, @RequestBody ConfirmBggLinkRequest request, Principal principal) {
        return BggLinkResponse.from(metadataConfirmations.confirm(documentId, request.bggId(), principal.getName()));
    }

    @PostMapping("/official-imports")
    @ResponseStatus(HttpStatus.CREATED)
    RuleDocumentController.UploadResponse importOfficialRulebook(
            @RequestBody OfficialRulebookImportRequest request, Principal principal) {
        return RuleDocumentController.UploadResponse.from(officialImports.importRulebook(
                request.editionId(),
                request.title(),
                request.sourceType(),
                request.officialSourceUrl(),
                request.rightsConfirmed(),
                principal.getName()));
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

    record AssignEditionRequest(UUID editionId) {
        AssignEditionRequest {
            if (editionId == null) {
                throw new IllegalArgumentException("game edition is required");
            }
        }
    }

    record BggSuggestionResponse(
            int bggId,
            String name,
            Integer publicationYear,
            String coverUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge,
            boolean normalizedTitleMatch,
            String bggUrl) {
        static BggSuggestionResponse from(Candidate candidate) {
            return new BggSuggestionResponse(
                    candidate.bggId(),
                    candidate.name(),
                    candidate.publicationYear(),
                    candidate.coverUrl(),
                    candidate.minPlayers(),
                    candidate.maxPlayers(),
                    candidate.playingTimeMinutes(),
                    candidate.minimumAge(),
                    candidate.normalizedTitleMatch(),
                    "https://boardgamegeek.com/boardgame/" + candidate.bggId());
        }
    }

    record ConfirmBggLinkRequest(int bggId) {
        ConfirmBggLinkRequest {
            if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        }
    }

    record BggLinkResponse(
            RuleDocumentController.DocumentDetails document,
            UUID gameId,
            UUID editionId,
            int bggId,
            String name,
            String coverUrl,
            String bggUrl,
            boolean alreadyImported) {
        static BggLinkResponse from(Confirmation confirmation) {
            var link = confirmation.link();
            return new BggLinkResponse(
                    RuleDocumentController.DocumentDetails.from(confirmation.document()),
                    link.gameId(),
                    link.editionId(),
                    link.bggId(),
                    link.gameName(),
                    link.coverUrl(),
                    "https://boardgamegeek.com/boardgame/" + link.bggId(),
                    link.alreadyImported());
        }
    }

    record OfficialRulebookImportRequest(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            boolean rightsConfirmed) {}
}
