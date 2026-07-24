package com.rulepilot.ingestion.adapter.out.pdf;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.application.PdfPageExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Text-only adapter for the on-demand layout-rebuild workflow. Upload parsing uses {@link PdfBoxRulebookPreparation}. */
@Component
public class PdfBoxPageExtractor implements PdfPageExtractor {

    private final int maxPages;
    private final int maxExtractedCharacters;

    public PdfBoxPageExtractor(
            @Value("${rulepilot.document.max-pdf-pages:500}") int maxPages,
            @Value("${rulepilot.document.max-extracted-characters:5000000}") int maxExtractedCharacters) {
        if (maxPages < 1 || maxExtractedCharacters < 1) {
            throw new IllegalArgumentException("PDF extraction limits must be positive");
        }
        this.maxPages = maxPages;
        this.maxExtractedCharacters = maxExtractedCharacters;
    }

    @Override
    public List<DocumentProcessing.ExtractedPage> extract(InputStream input) {
        Path temporaryPdf = null;
        try (input) {
            temporaryPdf = Files.createTempFile("rulepilot-document-", ".pdf");
            Files.copy(input, temporaryPdf, StandardCopyOption.REPLACE_EXISTING);
            try (PDDocument document = Loader.loadPDF(temporaryPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                PdfBoxRulebookPreparation.validateDocument(document, maxPages);
                return PdfBoxRulebookPreparation.extractPages(document, maxExtractedCharacters);
            }
        } catch (PdfExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PdfExtractionException("could not extract PDF pages", exception);
        } finally {
            if (temporaryPdf != null) {
                try {
                    Files.deleteIfExists(temporaryPdf);
                } catch (IOException ignored) {
                    temporaryPdf.toFile().deleteOnExit();
                }
            }
        }
    }
}
