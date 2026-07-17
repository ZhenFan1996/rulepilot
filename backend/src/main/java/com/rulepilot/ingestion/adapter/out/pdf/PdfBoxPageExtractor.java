package com.rulepilot.ingestion.adapter.out.pdf;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.application.PdfPageExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfBoxPageExtractor implements PdfPageExtractor {

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
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<DocumentProcessing.ExtractedPage> pages = new ArrayList<>(document.getNumberOfPages());
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String text = stripper.getText(document).replace("\r\n", "\n").strip();
                pages.add(new DocumentProcessing.ExtractedPage(pageNumber, text));
            }
            return List.copyOf(pages);
        }
    }
}
