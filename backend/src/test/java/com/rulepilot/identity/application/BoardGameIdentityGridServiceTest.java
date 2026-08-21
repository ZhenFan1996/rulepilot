package com.rulepilot.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.catalog.CatalogGameSelectionLookup.GameSelection;
import com.rulepilot.identity.BoardGameIdentityGrid;
import com.rulepilot.identity.BoardGameIdentityGrid.Selection;
import com.rulepilot.identity.BoardGameIdentityGrid.Slot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardGameIdentityGridServiceTest {

    private final BoardGameIdentityGrid grid = mock(BoardGameIdentityGrid.class);
    private final CatalogGameSelectionLookup games = mock(CatalogGameSelectionLookup.class);
    private final BoardGameIdentityGridService service = new BoardGameIdentityGridService(grid, games);

    @Test
    void refreshesMissingPersistedCoversFromCurrentCatalogMetadataInOneBatch() {
        Instant selectedAt = Instant.parse("2026-08-01T00:00:00Z");
        when(grid.read("alice"))
                .thenReturn(List.of(new Selection(
                        Slot.FAVORITE_GAME, 342942, "Ark Nova", "方舟动物园", "", "", selectedAt)));
        when(games.findAll(List.of(342942)))
                .thenReturn(List.of(new GameSelection(
                        342942,
                        "Ark Nova",
                        "方舟动物园",
                        2021,
                        "https://example.test/ark-nova-thumb.jpg",
                        "https://example.test/ark-nova.jpg")));

        assertThat(service.read("alice")).singleElement().satisfies(selection -> {
            assertThat(selection.slot()).isEqualTo(Slot.FAVORITE_GAME);
            assertThat(selection.thumbnailUrl()).isEqualTo("https://example.test/ark-nova-thumb.jpg");
            assertThat(selection.imageUrl()).isEqualTo("https://example.test/ark-nova.jpg");
            assertThat(selection.updatedAt()).isEqualTo(selectedAt);
        });
    }

    @Test
    void preservesPersistedCoverWhenCurrentCatalogMetadataIsBlank() {
        Instant selectedAt = Instant.parse("2026-08-01T00:00:00Z");
        when(grid.read("alice"))
                .thenReturn(List.of(new Selection(
                        Slot.FAVORITE_ART,
                        13,
                        "CATAN",
                        "卡坦岛",
                        "https://example.test/persisted-thumb.jpg",
                        "https://example.test/persisted.jpg",
                        selectedAt)));
        when(games.findAll(List.of(13)))
                .thenReturn(List.of(new GameSelection(13, "CATAN", "", 1995, "", "")));

        assertThat(service.read("alice")).singleElement().satisfies(selection -> {
            assertThat(selection.chineseName()).isEqualTo("卡坦岛");
            assertThat(selection.thumbnailUrl()).isEqualTo("https://example.test/persisted-thumb.jpg");
            assertThat(selection.imageUrl()).isEqualTo("https://example.test/persisted.jpg");
        });
    }
}
