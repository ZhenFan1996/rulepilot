package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentPageImageStore;
import java.io.InputStream;
import java.util.function.Consumer;

public interface PdfPageImageRenderer {

    int render(InputStream input, Consumer<DocumentPageImageStore.RenderedPageImage> pageConsumer);
}
