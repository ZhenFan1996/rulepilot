package com.rulepilot.document.adapter.in.web;

import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.catalog.CatalogEditionLookup.EditionReference;
import com.rulepilot.document.application.RuleDocumentMetadataSuggestionService;
import com.rulepilot.document.application.RuleDocumentMetadataConfirmationService;
import com.rulepilot.document.application.RuleDocumentMetadataConfirmationService.Confirmation;
import com.rulepilot.document.application.OfficialRulebookImportJobService;
import com.rulepilot.document.application.OfficialRulebookImportIdentity;
import com.rulepilot.document.application.OfficialRulebookImportRecovery;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.application.UploadRuleDocumentService;
import com.rulepilot.document.application.UploadedRulebookTeachingHandoffService;
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
    private final OfficialRulebookImportJobService officialImports;
    private final UploadedRulebookTeachingHandoffService uploadedTeachingHandoffs;
    private final CatalogEditionLookup catalog;

    public UserRuleDocumentController(
            UploadRuleDocumentService documents,
            PhotographedRulebookUploadService photographedDocuments,
            RuleDocumentRemovalService removals,
            RuleDocumentMetadataSuggestionService metadataSuggestions,
            RuleDocumentMetadataConfirmationService metadataConfirmations,
            OfficialRulebookImportJobService officialImports,
            UploadedRulebookTeachingHandoffService uploadedTeachingHandoffs,
            CatalogEditionLookup catalog) {
        this.documents = documents;
        this.photographedDocuments = photographedDocuments;
        this.removals = removals;
        this.metadataSuggestions = metadataSuggestions;
        this.metadataConfirmations = metadataConfirmations;
        this.officialImports = officialImports;
        this.uploadedTeachingHandoffs = uploadedTeachingHandoffs;
        this.catalog = catalog;
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
            @RequestParam(defaultValue = "false") boolean startTeaching,
            @RequestParam(required = false) String learningGoal,
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
                    principal.getName(),
                    startTeaching,
                    learningGoal);
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
            @RequestParam(defaultValue = "false") boolean startTeaching,
            @RequestParam(required = false) String learningGoal,
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
                    principal.getName(),
                    startTeaching,
                    learningGoal);
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
    @ResponseStatus(HttpStatus.ACCEPTED)
    OfficialRulebookImportJobResponse importOfficialRulebook(
            @RequestBody OfficialRulebookImportRequest request, Principal principal) {
        var launch = officialImports.enqueue(new OfficialRulebookImportJobService.Command(
                request.editionId(),
                request.title(),
                request.sourceType(),
                request.officialSourceUrl(),
                request.rightsConfirmed(),
                request.startTeaching(),
                request.learningGoal(),
                new OfficialRulebookImportIdentity.SourceClaim(
                        request.discoveredForEditionId(),
                        request.sourceEdition(),
                        request.sourceLanguage(),
                        request.sourceLanguageVerified()),
                request.identityConfirmed()), principal.getName());
        return officialImportResponse(launch.job(), launch.reused());
    }

    @GetMapping("/official-imports")
    List<OfficialRulebookImportJobResponse> officialRulebookImports(Principal principal) {
        return officialImports.recentOwned(principal.getName()).stream()
                .map(job -> officialImportResponse(job, false))
                .toList();
    }

    @GetMapping("/official-imports/{jobId}")
    OfficialRulebookImportJobResponse officialRulebookImport(
            @PathVariable UUID jobId, Principal principal) {
        return officialImportResponse(officialImports.requireOwned(jobId, principal.getName()), false);
    }

    @PostMapping("/official-imports/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OfficialRulebookImportJobResponse retryOfficialRulebookImport(
            @PathVariable UUID jobId, Principal principal) {
        var launch = officialImports.retryImport(jobId, principal.getName());
        return officialImportResponse(launch.job(), launch.reused());
    }

    @PostMapping("/official-imports/{jobId}/teaching-retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OfficialRulebookImportJobResponse retryOfficialRulebookTeaching(
            @PathVariable UUID jobId,
            @RequestBody TeachingHandoffRetryRequest request,
            Principal principal) {
        return officialImportResponse(officialImports.retryTeaching(
                jobId, request.expectedPreparationRunId(), principal.getName()), true);
    }

    @PostMapping("/official-imports/{jobId}/teaching-ensure-current")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OfficialRulebookImportJobResponse ensureOfficialRulebookTeachingCurrent(
            @PathVariable UUID jobId,
            @RequestBody TeachingHandoffRetryRequest request,
            Principal principal) {
        return officialImportResponse(officialImports.ensureTeachingCurrent(
                jobId, request.expectedPreparationRunId(), principal.getName()), true);
    }

    @GetMapping("/upload-teaching-handoffs")
    List<UploadedRulebookTeachingHandoffResponse> uploadedRulebookTeachingHandoffs(
            Principal principal) {
        return uploadedTeachingHandoffs.recentOwned(principal.getName()).stream()
                .map(view -> UploadedRulebookTeachingHandoffResponse.from(view, catalog))
                .toList();
    }

    @PostMapping("/upload-teaching-handoffs/{handoffId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    UploadedRulebookTeachingHandoffResponse retryUploadedRulebookTeaching(
            @PathVariable UUID handoffId,
            @RequestBody TeachingHandoffRetryRequest request,
            Principal principal) {
        return UploadedRulebookTeachingHandoffResponse.from(uploadedTeachingHandoffs.retry(
                handoffId, request.expectedPreparationRunId(), principal.getName()), catalog);
    }

    private OfficialRulebookImportJobResponse officialImportResponse(
            OfficialRulebookImportJob job,
            boolean reused) {
        EditionReference edition = job.editionId() == null
                ? null
                : catalog.findEdition(job.editionId()).orElse(null);
        return OfficialRulebookImportJobResponse.from(job, edition, reused);
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
            boolean rightsConfirmed,
            boolean startTeaching,
            String learningGoal,
            UUID discoveredForEditionId,
            String sourceEdition,
            String sourceLanguage,
            boolean sourceLanguageVerified,
            boolean identityConfirmed) {}

    record TeachingHandoffRetryRequest(UUID expectedPreparationRunId) {}

    record OfficialRulebookImportJobResponse(
            UUID id,
            String title,
            String rulebookTitle,
            UUID editionId,
            String editionName,
            String sourceDomain,
            String officialSourceUrl,
            DocumentSourceType sourceType,
            String learningGoal,
            OfficialRulebookImportJob.Stage stage,
            long downloadedBytes,
            Long totalBytes,
            UUID documentVersionId,
            boolean duplicate,
            String errorCode,
            OfficialRulebookImportJob.TeachingHandoffState teachingHandoffState,
            UUID teachingPreparationRunId,
            String teachingErrorCode,
            int teachingAutomaticRecoveryCount,
            TeachingRecoveryAction teachingNextAction,
            java.time.Instant downloadCompletedAt,
            java.time.Instant importCompletedAt,
            java.time.Instant teachingHandoffUpdatedAt,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            OfficialRulebookImportRecovery recovery,
            boolean reused) {

        static OfficialRulebookImportJobResponse from(
                OfficialRulebookImportJob job,
                EditionReference edition,
                boolean reused) {
            return new OfficialRulebookImportJobResponse(
                    job.id(),
                    edition == null ? job.title() : edition.gameName(),
                    job.title(),
                    job.editionId(),
                    edition == null ? null : edition.name(),
                    java.net.URI.create(job.sourceUrl()).getHost(),
                    job.sourceUrl(),
                    job.sourceType(),
                    job.teachingHandoff().learningGoal(),
                    job.stage(),
                    job.downloadedBytes(),
                    job.totalBytes(),
                    job.documentVersionId(),
                    job.duplicate(),
                    job.errorCode(),
                    job.teachingHandoff().state(),
                    job.teachingHandoff().preparationRunId(),
                    job.teachingHandoff().errorCode(),
                    job.teachingHandoff().automaticRecoveryCount(),
                    teachingRecoveryAction(
                            job.teachingHandoff().state(), job.teachingHandoff().errorCode()),
                    job.downloadCompletedAt(),
                    job.completedAt(),
                    job.teachingHandoff().updatedAt(),
                    job.createdAt(),
                    job.updatedAt(),
                    OfficialRulebookImportRecovery.forJob(job),
                    reused);
        }
    }

    record UploadedRulebookTeachingHandoffResponse(
            UUID id,
            UUID documentVersionId,
            UUID editionId,
            String title,
            String rulebookTitle,
            String state,
            UUID preparationRunId,
            String errorCode,
            int automaticRecoveryCount,
            TeachingRecoveryAction nextAction,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {

        static UploadedRulebookTeachingHandoffResponse from(
                UploadedRulebookTeachingHandoffService.HandoffView view,
                CatalogEditionLookup catalog) {
            String title = view.editionId() == null
                    ? view.rulebookTitle()
                    : catalog.findEdition(view.editionId())
                            .map(EditionReference::gameName)
                            .orElse(view.rulebookTitle());
            return new UploadedRulebookTeachingHandoffResponse(
                    view.id(),
                    view.documentVersionId(),
                    view.editionId(),
                    title,
                    view.rulebookTitle(),
                    view.state().name(),
                    view.preparationRunId(),
                    view.errorCode(),
                    view.automaticRecoveryCount(),
                    teachingRecoveryAction(view.state(), view.errorCode()),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    enum TeachingRecoveryAction {
        WAIT,
        OPEN_PROGRESS,
        RETRY_TEACHING,
        RETRY_DOCUMENT,
        NONE
    }

    private static TeachingRecoveryAction teachingRecoveryAction(
            OfficialRulebookImportJob.TeachingHandoffState state, String errorCode) {
        return switch (state) {
            case WAITING_FOR_DOCUMENT, LAUNCHING -> TeachingRecoveryAction.WAIT;
            case LAUNCHED -> TeachingRecoveryAction.OPEN_PROGRESS;
            case FAILED -> failedTeachingRecoveryAction(errorCode);
            case NOT_REQUESTED -> TeachingRecoveryAction.NONE;
        };
    }

    private static TeachingRecoveryAction teachingRecoveryAction(
            com.rulepilot.document.application.UploadedRulebookTeachingHandoffStore.State state,
            String errorCode) {
        return switch (state) {
            case WAITING_FOR_DOCUMENT, LAUNCHING -> TeachingRecoveryAction.WAIT;
            case LAUNCHED -> TeachingRecoveryAction.OPEN_PROGRESS;
            case FAILED -> failedTeachingRecoveryAction(errorCode);
        };
    }

    private static TeachingRecoveryAction failedTeachingRecoveryAction(String errorCode) {
        if ("DOCUMENT_PROCESSING_FAILED".equals(errorCode)) {
            return TeachingRecoveryAction.RETRY_DOCUMENT;
        }
        return switch (errorCode == null ? "" : errorCode) {
            case "APPLICATION_RESTARTED",
                    "TEACHING_HANDOFF_LAUNCH_FAILED",
                    "TEACHING_PREPARATION_FAILED",
                    // Existing persisted records used this code before the handoff-owned one-shot policy was removed.
                    "TEACHING_RECOVERY_EXHAUSTED" -> TeachingRecoveryAction.RETRY_TEACHING;
            default -> TeachingRecoveryAction.NONE;
        };
    }
}
