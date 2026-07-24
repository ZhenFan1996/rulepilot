package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import java.io.InputStream;
import java.util.List;

/** Extracts rulebook text/layout for maintenance flows that intentionally do not render visual pages. */
public interface PdfPageExtractor {

    List<DocumentProcessing.ExtractedPage> extract(InputStream input);
}
