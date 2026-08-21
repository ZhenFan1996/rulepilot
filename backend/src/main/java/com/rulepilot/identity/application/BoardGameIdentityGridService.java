package com.rulepilot.identity.application;

import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.identity.BoardGameIdentityGrid;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardGameIdentityGridService {

    private final BoardGameIdentityGrid grid;
    private final CatalogGameSelectionLookup games;

    public BoardGameIdentityGridService(BoardGameIdentityGrid grid, CatalogGameSelectionLookup games) {
        this.grid = grid;
        this.games = games;
    }

    public List<BoardGameIdentityGrid.Selection> read(String username) {
        return grid.read(username);
    }

    public List<CatalogGameSelectionLookup.GameSelection> search(String query, int maximum) {
        return games.search(query, maximum);
    }

    @Transactional
    public BoardGameIdentityGrid.Selection select(String username, BoardGameIdentityGrid.Slot slot, int bggId) {
        CatalogGameSelectionLookup.GameSelection game = games.find(bggId)
                .orElseThrow(() -> new IllegalArgumentException("Selected game is not available in the local BGG catalog"));
        return grid.select(
                username,
                slot,
                game.bggId(),
                game.name(),
                game.chineseName(),
                game.thumbnailUrl(),
                game.imageUrl(),
                Instant.now());
    }

    @Transactional
    public void clear(String username, BoardGameIdentityGrid.Slot slot) {
        grid.clear(username, slot);
    }
}
