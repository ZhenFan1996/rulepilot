package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.PublicCoverImageFetcher;
import com.rulepilot.teaching.PublicCoverThumbnailCache;
import com.rulepilot.teaching.PublicCoverThumbnailCache.Thumbnail;
import com.rulepilot.document.DocumentPageImages.PageImage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicCoverThumbnailServiceTest {

    @Test
    void serves_a_persisted_thumbnail_without_contacting_its_origin_again() {
        Map<String, Thumbnail> stored = new HashMap<>();
        RecordingFetcher fetcher = new RecordingFetcher();
        PublicCoverThumbnailService service = new PublicCoverThumbnailService(cache(stored), fetcher);

        Thumbnail first = service.thumbnailFor("https://images.example/cover.png");
        Thumbnail second = service.thumbnailFor("https://images.example/cover.png");

        assertThat(fetcher.calls).isEqualTo(1);
        assertThat(second.content()).containsExactly(first.content());
        assertThat(stored).hasSize(1);
    }

    @Test
    void keeps_the_current_reader_working_when_thumbnail_storage_cannot_write() {
        RecordingFetcher fetcher = new RecordingFetcher();
        PublicCoverThumbnailCache failingCache = new PublicCoverThumbnailCache() {
            @Override
            public Optional<Thumbnail> find(String sourceDigest) {
                return Optional.empty();
            }

            @Override
            public void store(String sourceDigest, Thumbnail thumbnail) {
                throw new IllegalStateException("object storage unavailable");
            }
        };
        PublicCoverThumbnailService service = new PublicCoverThumbnailService(failingCache, fetcher);

        Thumbnail thumbnail = service.thumbnailFor("https://images.example/cover.png");

        assertThat(fetcher.calls).isEqualTo(1);
        assertThat(thumbnail.content()).containsExactly(fetcher.thumbnail.content());
    }

    @Test
    void caches_a_small_rulebook_front_page_without_contacting_an_external_cover_host() throws Exception {
        Map<String, Thumbnail> stored = new HashMap<>();
        RecordingFetcher fetcher = new RecordingFetcher();
        PublicCoverThumbnailService service = new PublicCoverThumbnailService(cache(stored), fetcher);
        BufferedImage original = new BufferedImage(1_200, 1_600, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        graphics.setColor(new Color(50, 70, 90));
        graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
        graphics.dispose();
        var content = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(original, "png", content);
        PageImage firstPage = new PageImage(1, "image/png", content.toByteArray(), 1_200, 1_600);

        UUID documentVersionId = UUID.randomUUID();
        Thumbnail first = service.thumbnailForRulebookCover(documentVersionId, firstPage);
        Thumbnail second = service.thumbnailForRulebookCover(documentVersionId, firstPage);

        BufferedImage decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(first.content()));
        assertThat(fetcher.calls).isZero();
        assertThat(decoded.getWidth()).isEqualTo(480);
        assertThat(decoded.getHeight()).isEqualTo(640);
        assertThat(second.content()).containsExactly(first.content());
        assertThat(stored).hasSize(1);
    }

    private PublicCoverThumbnailCache cache(Map<String, Thumbnail> stored) {
        return new PublicCoverThumbnailCache() {
            @Override
            public Optional<Thumbnail> find(String sourceDigest) {
                return Optional.ofNullable(stored.get(sourceDigest));
            }

            @Override
            public void store(String sourceDigest, Thumbnail thumbnail) {
                stored.put(sourceDigest, thumbnail);
            }
        };
    }

    private static final class RecordingFetcher implements PublicCoverImageFetcher {
        private final Thumbnail thumbnail = new Thumbnail(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
        private int calls;

        @Override
        public Thumbnail fetch(URI source) {
            calls++;
            return thumbnail;
        }
    }
}
