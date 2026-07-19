package com.rulepilot.ingestion.adapter.out.pdf;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.application.PdfPageExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PdfBoxPageExtractor implements PdfPageExtractor {

    private static final Set<COSName> ACTIVE_CONTENT_KEYS = Set.of(
            COSName.AA,
            COSName.JS,
            COSName.JAVA_SCRIPT,
            COSName.EMBEDDED_FILE,
            COSName.EMBEDDED_FILES);
    private static final Set<COSName> ACTIVE_CONTENT_TYPES = Set.of(
            COSName.getPDFName("Launch"),
            COSName.getPDFName("JavaScript"),
            COSName.getPDFName("RichMedia"),
            COSName.getPDFName("FileAttachment"));

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
            return extractPages(temporaryPdf);
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

    private List<DocumentProcessing.ExtractedPage> extractPages(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            if (document.getNumberOfPages() > maxPages) {
                throw new PdfExtractionException("PDF exceeds the configured page limit");
            }
            rejectActiveContent(document.getDocument().getTrailer());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<DocumentProcessing.ExtractedPage> pages = new ArrayList<>(document.getNumberOfPages());
            int extractedCharacters = 0;
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String text = stripper.getText(document).replace("\r\n", "\n").strip();
                extractedCharacters = Math.addExact(extractedCharacters, text.length());
                if (extractedCharacters > maxExtractedCharacters) {
                    throw new PdfExtractionException("PDF exceeds the configured extracted-text limit");
                }
                pages.add(new DocumentProcessing.ExtractedPage(pageNumber, text));
            }
            return List.copyOf(pages);
        }
    }

    private void rejectActiveContent(COSBase root) {
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            COSBase value = pending.removeFirst();
            if (!visited.add(value)) {
                continue;
            }
            if (value instanceof COSObject object) {
                if (object.getObject() != null) {
                    pending.add(object.getObject());
                }
            } else if (value instanceof COSArray array) {
                array.forEach(item -> {
                    if (item != null) {
                        pending.add(item);
                    }
                });
            } else if (value instanceof COSDictionary dictionary) {
                dictionary.forEach((key, item) -> {
                    if (ACTIVE_CONTENT_KEYS.contains(key)
                            || (item instanceof COSName name && ACTIVE_CONTENT_TYPES.contains(name))) {
                        throw new PdfExtractionException("PDF contains active or embedded content");
                    }
                    if (item != null) {
                        pending.add(item);
                    }
                });
            }
        }
    }
}
