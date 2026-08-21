package com.rulepilot.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.catalog.CatalogCoverImages;
import com.rulepilot.catalog.CatalogGameSelectionLookup.GameSelection;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BggCatalogCoverImageControllerTest {

    private final CatalogGameSelectionLookup games = mock(CatalogGameSelectionLookup.class);
    private final CatalogCoverImages coverImages = mock(CatalogCoverImages.class);
    private final BggCatalogCoverImageController controller = new BggCatalogCoverImageController(games, coverImages);

    @Test
    void servesThePersistedLocalThumbnailInsteadOfRedirectingTheBrowserToBgg() {
        when(games.find(342942)).thenReturn(Optional.of(new GameSelection(
                342942,
                "Ark Nova",
                "方舟动物园",
                2021,
                "https://cf.geekdo-images.com/ark-nova-thumb.jpg",
                "https://cf.geekdo-images.com/ark-nova.jpg")));
        when(coverImages.read("https://cf.geekdo-images.com/ark-nova.jpg"))
                .thenReturn(new byte[] {1, 2, 3});

        var response = controller.image(342942);

        assertThat(response.getBody()).containsExactly(1, 2, 3);
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=2592000", "immutable");
        verify(coverImages).read("https://cf.geekdo-images.com/ark-nova.jpg");
    }
}
