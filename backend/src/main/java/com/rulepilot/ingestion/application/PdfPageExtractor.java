package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import java.io.InputStream;
import java.util.List;

public interface PdfPageExtractor {

    List<DocumentProcessing.ExtractedPage> extract(InputStream input);
}
