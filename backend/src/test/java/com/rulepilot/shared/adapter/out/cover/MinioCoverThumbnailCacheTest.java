package com.rulepilot.shared.adapter.out.cover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;

class MinioCoverThumbnailCacheTest {

    private static final String CACHE_KEY = "a".repeat(64);

    private final MinioClient client = mock(MinioClient.class);
    private final MinioCoverThumbnailCache cache = new MinioCoverThumbnailCache(client, "rulepilot-test");

    @Test
    void treatsOnlyAnExplicitMissingObjectResponseAsACacheMiss() throws Exception {
        ErrorResponseException missing = minioError("NoSuchKey");
        when(client.getObject(any())).thenThrow(missing);

        assertThat(cache.find(CACHE_KEY)).isEmpty();
    }

    @Test
    void preservesAnUnavailableCacheAsAnInfrastructureFailure() throws Exception {
        ErrorResponseException unavailable = minioError("AccessDenied");
        when(client.getObject(any())).thenThrow(unavailable);

        assertThatThrownBy(() -> cache.find(CACHE_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable cover cache");
    }

    private ErrorResponseException minioError(String code) {
        ErrorResponse response = mock(ErrorResponse.class);
        when(response.code()).thenReturn(code);
        ErrorResponseException exception = mock(ErrorResponseException.class);
        when(exception.errorResponse()).thenReturn(response);
        return exception;
    }
}
