package com.rulepilot.document.application;

import com.rulepilot.document.domain.DocumentSourceType;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class PhotographedRulebookUploadService {

    /** Matches the PDF processor's page boundary; the multipart request separately bounds aggregate bytes. */
    static final int MAX_PAGE_COUNT = 500;
    static final long MAX_PHOTO_BYTES = 8L * 1024 * 1024;
    private static final Set<String> ACCEPTED_IMAGE_TYPES = Set.of("image/jpeg", "image/png");
    private static final String DEFAULT_TITLE = "Photographed rulebook";

    private final PhotographedRulebookAssembler assembler;
    private final UploadRuleDocumentService documents;

    public PhotographedRulebookUploadService(
            PhotographedRulebookAssembler assembler, UploadRuleDocumentService documents) {
        this.assembler = assembler;
        this.documents = documents;
    }

    public UploadRuleDocumentService.UploadResult upload(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String officialCoverUrl,
            List<PhotoPage> pages,
            String username) {
        return upload(
                editionId,
                title,
                sourceType,
                officialSourceUrl,
                officialCoverUrl,
                pages,
                username,
                false,
                null);
    }

    public UploadRuleDocumentService.UploadResult upload(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String officialCoverUrl,
            List<PhotoPage> pages,
            String username,
            boolean startTeaching,
            String learningGoal) {
        validate(pages);
        PhotographedRulebookAssembler.AssembledRulebook assembled = assembler.assemble(pages);
        return documents.upload(
                editionId,
                normalizedTitle(title),
                sourceType,
                officialSourceUrl,
                officialCoverUrl,
                assembled.originalFilename(),
                RuleDocumentStorageService.PDF_CONTENT_TYPE,
                assembled.pdf().length,
                new ByteArrayInputStream(assembled.pdf()),
                username,
                startTeaching,
                learningGoal);
    }

    private void validate(List<PhotoPage> pages) {
        if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGE_COUNT) {
            throw new IllegalArgumentException("upload between 1 and " + MAX_PAGE_COUNT + " photographed pages");
        }
        for (PhotoPage page : pages) {
            if (page == null || page.content() == null || page.content().length == 0
                    || page.content().length > MAX_PHOTO_BYTES) {
                throw new IllegalArgumentException("each photographed page must be between 1 byte and "
                        + MAX_PHOTO_BYTES + " bytes");
            }
            if (page.contentType() == null || !ACCEPTED_IMAGE_TYPES.contains(page.contentType().toLowerCase())) {
                throw new IllegalArgumentException("photographed pages must be JPEG or PNG images");
            }
        }
    }

    private String normalizedTitle(String title) {
        return title == null || title.isBlank() ? DEFAULT_TITLE : title;
    }

    public record PhotoPage(String originalFilename, String contentType, byte[] content) {}
}
