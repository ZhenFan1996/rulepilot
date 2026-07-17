package com.rulepilot.document.application;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RuleDocumentStorageService {

    public static final String PDF_CONTENT_TYPE = "application/pdf";

    private final DocumentStorage storage;
    private final long maxPdfBytes;

    public RuleDocumentStorageService(DocumentStorage storage, MinioStorageProperties properties) {
        this.storage = storage;
        this.maxPdfBytes = properties.maxPdfBytes();
    }

    public DocumentStorage.StoredDocument storePdf(
            InputStream content, long size, String contentType, String originalFilename) {
        Objects.requireNonNull(content, "content is required");
        if (size <= 0 || size > maxPdfBytes) {
            throw new IllegalArgumentException("PDF size must be between 1 and " + maxPdfBytes + " bytes");
        }
        if (!PDF_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            throw new IllegalArgumentException("only application/pdf documents are accepted");
        }
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("the original filename must end with .pdf");
        }

        BufferedInputStream validatedContent = validatePdfSignature(content);
        String objectKey = "rulebooks/" + UUID.randomUUID() + ".pdf";
        return storage.store(objectKey, validatedContent, size, PDF_CONTENT_TYPE);
    }

    private BufferedInputStream validatePdfSignature(InputStream content) {
        try {
            BufferedInputStream buffered = new BufferedInputStream(content);
            buffered.mark(5);
            String signature = new String(buffered.readNBytes(5), StandardCharsets.US_ASCII);
            buffered.reset();
            if (!"%PDF-".equals(signature)) {
                throw new IllegalArgumentException("file content is not a PDF");
            }
            return buffered;
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not read PDF content", exception);
        }
    }
}
