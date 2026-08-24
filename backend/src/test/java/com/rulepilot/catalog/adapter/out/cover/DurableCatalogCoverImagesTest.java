package com.rulepilot.catalog.adapter.out.cover;

import static com.rulepilot.catalog.CatalogCoverImages.Variant.COMPACT;
import static com.rulepilot.catalog.CatalogCoverImages.Variant.DISPLAY;
import static com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile.COMPACT_PROFILE;
import static com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile.DISPLAY_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.CatalogCoverImages.Absent;
import com.rulepilot.catalog.CatalogCoverImages.Ready;
import com.rulepilot.catalog.CatalogCoverImages.Retryable;
import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.catalog.CatalogGameSelectionLookup.GameSelection;
import com.rulepilot.catalog.adapter.out.cover.CoverImageFetcher.SourceAbsentException;
import com.rulepilot.catalog.adapter.out.cover.CoverThumbnailCache.Thumbnail;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DurableCatalogCoverImagesTest {

    private static final String THUMBNAIL = "https://cf.geekdo-images.com/game-thumb.jpg";
    private static final String IMAGE = "https://cf.geekdo-images.com/game-image.jpg";

    private final CatalogGameSelectionLookup games = mock(CatalogGameSelectionLookup.class);
    private final DurableCoverThumbnailService thumbnails = mock(DurableCoverThumbnailService.class);
    private final DurableCatalogCoverImages covers = new DurableCatalogCoverImages(games, thumbnails);

    @Test
    void compactPrefersTheThumbnailSourceAndUsesOnlyTheCompactProfile() {
        givenGame(THUMBNAIL, IMAGE);
        when(thumbnails.thumbnailFor(THUMBNAIL, COMPACT_PROFILE))
                .thenReturn(new Thumbnail(new byte[] {1, 2, 3}));

        assertThat(covers.load(42, COMPACT)).isInstanceOfSatisfying(Ready.class, ready -> {
            assertThat(ready.content()).containsExactly(1, 2, 3);
            assertThat(ready.entityTag()).matches("[0-9a-f]{64}");
        });
        verify(games).findStored(42);
        verify(games, never()).find(42);
        verify(thumbnails).thumbnailFor(THUMBNAIL, COMPACT_PROFILE);
        verify(thumbnails, never()).thumbnailFor(IMAGE, COMPACT_PROFILE);
        verify(thumbnails, never()).thumbnailFor(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(DISPLAY_PROFILE));
    }

    @Test
    void compactFallsBackToTheImageSourceButStillUsesTheCompactProfile() {
        givenGame(THUMBNAIL, IMAGE);
        when(thumbnails.thumbnailFor(THUMBNAIL, COMPACT_PROFILE))
                .thenThrow(new SourceAbsentException("thumbnail is absent"));
        when(thumbnails.thumbnailFor(IMAGE, COMPACT_PROFILE))
                .thenReturn(new Thumbnail(new byte[] {4, 5, 6}));

        assertThat(covers.load(42, COMPACT)).isInstanceOf(Ready.class);
        verify(thumbnails).thumbnailFor(THUMBNAIL, COMPACT_PROFILE);
        verify(thumbnails).thumbnailFor(IMAGE, COMPACT_PROFILE);
    }

    @Test
    void displayNeverTreatsAStoredThumbnailAsSuccessWhenAnImageSourceExists() {
        givenGame(THUMBNAIL, IMAGE);
        when(thumbnails.thumbnailFor(IMAGE, DISPLAY_PROFILE))
                .thenThrow(new IllegalStateException("display cache unavailable"));
        when(thumbnails.thumbnailFor(THUMBNAIL, DISPLAY_PROFILE))
                .thenReturn(new Thumbnail(new byte[] {7, 8, 9}));

        assertThat(covers.load(42, DISPLAY)).isInstanceOf(Retryable.class);
        verify(thumbnails).thumbnailFor(IMAGE, DISPLAY_PROFILE);
        verify(thumbnails, never()).thumbnailFor(THUMBNAIL, DISPLAY_PROFILE);
    }

    @Test
    void displayUsesTheThumbnailAsATerminalFallbackOnlyWhenNoImageSourceExists() {
        givenGame(THUMBNAIL, "");
        when(thumbnails.thumbnailFor(THUMBNAIL, DISPLAY_PROFILE))
                .thenReturn(new Thumbnail(new byte[] {10, 11, 12}));

        assertThat(covers.load(42, DISPLAY)).isInstanceOfSatisfying(
                Ready.class,
                ready -> assertThat(ready.content()).containsExactly(10, 11, 12));
        verify(thumbnails).thumbnailFor(THUMBNAIL, DISPLAY_PROFILE);
    }

    @Test
    void distinguishesAProjectionOrSourceAbsenceFromRetryableInfrastructure() {
        when(games.findStored(42)).thenReturn(Optional.empty());
        assertThat(covers.load(42, COMPACT)).isInstanceOf(Absent.class);

        when(games.findStored(42)).thenThrow(new IllegalStateException("projection unavailable"));
        assertThat(covers.load(42, COMPACT)).isInstanceOf(Retryable.class);
    }

    private void givenGame(String thumbnail, String image) {
        when(games.findStored(42)).thenReturn(Optional.of(new GameSelection(
                42, "Catalog Game", "目录游戏", 2026, thumbnail, image)));
    }
}
