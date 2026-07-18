package com.rulepilot.document;

public interface DocumentProcessingFailures {

    void retry(DocumentProcessingCommand command, int attempt);

    void deadLetter(DocumentProcessingCommand command, int attempt, String errorCode);
}
