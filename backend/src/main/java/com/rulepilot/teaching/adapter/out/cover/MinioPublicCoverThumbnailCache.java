package com.rulepilot.teaching.adapter.out.cover;

import com.rulepilot.teaching.PublicCoverThumbnailCache;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Object-store adapter so public thumbnails survive application restarts and can be browser-cached. */
@Component
@Profile("!test")
public class MinioPublicCoverThumbnailCache implements PublicCoverThumbnailCache {

    private final MinioClient client;
    private final String bucket;
    private final AtomicBoolean bucketReady = new AtomicBoolean();

    public MinioPublicCoverThumbnailCache(
            MinioClient client,
            @Value("${rulepilot.storage.minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public Optional<Thumbnail> find(String sourceDigest) {
        try (InputStream input = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey(sourceDigest))
                .build())) {
            byte[] content = input.readNBytes(Thumbnail.MAX_CONTENT_BYTES + 1);
            if (content.length == 0 || content.length > Thumbnail.MAX_CONTENT_BYTES) return Optional.empty();
            return Optional.of(new Thumbnail(content));
        } catch (Exception missingOrUnavailable) {
            return Optional.empty();
        }
    }

    @Override
    public void store(String sourceDigest, Thumbnail thumbnail) {
        byte[] content = thumbnail.content();
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey(sourceDigest))
                    .contentType("image/jpeg")
                    .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("could not store public cover thumbnail", exception);
        }
    }

    private String objectKey(String sourceDigest) {
        if (sourceDigest == null || !sourceDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("public cover cache key is invalid");
        }
        return "public-covers/" + sourceDigest + ".jpg";
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady.get()) return;
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        bucketReady.set(true);
    }
}
