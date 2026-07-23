package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.PublicCoverImageFetcher;
import com.rulepilot.teaching.PublicCoverThumbnailCache;
import com.rulepilot.teaching.PublicCoverThumbnailCache.Thumbnail;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
