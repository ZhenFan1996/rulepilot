package com.rulepilot.document.adapter.out.storage;

import com.rulepilot.document.application.DocumentStorage;
import com.rulepilot.document.application.MinioStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class MinioDocumentStorage implements DocumentStorage {

    private final MinioClient client;
    private final String bucket;
    private final AtomicBoolean bucketReady = new AtomicBoolean();

    public MinioDocumentStorage(MinioClient client, MinioStorageProperties properties) {
        this.client = client;
        this.bucket = properties.bucket();
    }

    @Override
    public StoredDocument store(String objectKey, InputStream content, long size, String contentType) {
        try {
            ensureBucket();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestInputStream hashingStream = new DigestInputStream(content, digest);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(contentType)
                    .stream(hashingStream, size, -1)
                    .build());
            return new StoredDocument(objectKey, size, contentType, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        } catch (Exception exception) {
            throw new DocumentStorageException("could not store document", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new DocumentStorageException("could not open document", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new DocumentStorageException("could not delete document", exception);
        }
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady.get()) {
            return;
        }
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        bucketReady.set(true);
    }
}
