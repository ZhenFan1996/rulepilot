package com.rulepilot.identity.application;

import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.identity.BoardGameIdentityGrid;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
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
        List<BoardGameIdentityGrid.Selection> selections = grid.read(username);
        Map<Integer, CatalogGameSelectionLookup.GameSelection> currentGames = games.findAll(
                        selections.stream().map(BoardGameIdentityGrid.Selection::bggId).toList())
                .stream()
                .collect(Collectors.toMap(
                        CatalogGameSelectionLookup.GameSelection::bggId,
                        Function.identity(),
                        (first, ignored) -> first));
        return selections.stream().map(selection -> refresh(selection, currentGames.get(selection.bggId()))).toList();
    }

    private BoardGameIdentityGrid.Selection refresh(
            BoardGameIdentityGrid.Selection selection,
            CatalogGameSelectionLookup.GameSelection current) {
        if (current == null) return selection;
        return new BoardGameIdentityGrid.Selection(
                selection.slot(),
                selection.bggId(),
                present(current.name(), selection.gameName()),
                present(current.chineseName(), selection.chineseName()),
                present(current.thumbnailUrl(), selection.thumbnailUrl()),
                present(current.imageUrl(), selection.imageUrl()),
                selection.updatedAt());
    }

    private String present(String current, String persisted) {
        return current == null || current.isBlank() ? persisted : current;
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
