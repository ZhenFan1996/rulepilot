package com.rulepilot.document.application;

import java.io.InputStream;

public interface DocumentStorage {

    StoredDocument store(String objectKey, InputStream content, long size, String contentType);

    InputStream open(String objectKey);

    void delete(String objectKey);

    record StoredDocument(String objectKey, long size, String contentType, String sha256) {}
}
