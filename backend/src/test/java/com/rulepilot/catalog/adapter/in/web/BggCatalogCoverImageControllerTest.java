package com.rulepilot.catalog.adapter.in.web;

import static com.rulepilot.catalog.CatalogCoverImages.ContentType.JPEG;
import static com.rulepilot.catalog.CatalogCoverImages.Variant.COMPACT;
import static com.rulepilot.catalog.CatalogCoverImages.Variant.DISPLAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.CatalogCoverImages;
import com.rulepilot.catalog.CatalogCoverImages.Absent;
import com.rulepilot.catalog.CatalogCoverImages.Ready;
import com.rulepilot.catalog.CatalogCoverImages.Retryable;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class BggCatalogCoverImageControllerTest {

    private final CatalogCoverImages coverImages = mock(CatalogCoverImages.class);
    private final BggCatalogCoverImageController controller = new BggCatalogCoverImageController(coverImages);

    @Test
    void projectsOneTypedDisplayLoadAsCacheRevalidatableJpeg() {
        when(coverImages.load(342942, DISPLAY))
                .thenReturn(new Ready(new byte[] {1, 2, 3}, JPEG, "a".repeat(64)));

        var response = controller.image(342942);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(1, 2, 3);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + "a".repeat(64) + "\"");
        assertThat(response.getHeaders().getCacheControl())
                .contains("public", "max-age=")
                .doesNotContain("immutable", "no-store");
        verify(coverImages).load(342942, DISPLAY);
        verifyNoMoreInteractions(coverImages);
    }

    @Test
    void projectsOneTypedCompactLoadWithoutInspectingSourcesInTheController() {
        when(coverImages.load(342942, COMPACT))
                .thenReturn(new Ready(new byte[] {4, 5, 6}, JPEG, "b".repeat(64)));

        var response = controller.thumbnail(342942);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(4, 5, 6);
        verify(coverImages).load(342942, COMPACT);
        verifyNoMoreInteractions(coverImages);
    }

    @Test
    void projectsAnAbsentAssetAsA404ThatBrowsersMustNotCache() {
        when(coverImages.load(404, COMPACT)).thenReturn(new Absent());

        var response = controller.thumbnail(404);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(coverImages).load(404, COMPACT);
        verifyNoMoreInteractions(coverImages);
    }

    @Test
    void projectsARetryableAssetAs503WithNoStoreAndRetryAfter() {
        when(coverImages.load(342942, DISPLAY)).thenReturn(new Retryable(Duration.ofSeconds(7)));

        var response = controller.image(342942);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        verify(coverImages).load(342942, DISPLAY);
        verifyNoMoreInteractions(coverImages);
    }
}
