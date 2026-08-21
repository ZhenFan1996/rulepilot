package com.rulepilot.identity.adapter.in.web;

import com.rulepilot.catalog.CatalogGameSelectionLookup.GameSelection;
import com.rulepilot.identity.BoardGameIdentityGrid;
import com.rulepilot.identity.application.BoardGameIdentityGridService;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account/board-game-grid")
public class BoardGameIdentityGridController {

    private final BoardGameIdentityGridService grid;

    public BoardGameIdentityGridController(BoardGameIdentityGridService grid) {
        this.grid = grid;
    }

    @GetMapping
    List<BoardGameIdentityGrid.Selection> read(Principal principal) {
        return grid.read(principal.getName());
    }

    @GetMapping("/search")
    List<GameSelection> search(@RequestParam String q, @RequestParam(defaultValue = "12") int limit) {
        return grid.search(q, limit);
    }

    @PutMapping("/{slot}")
    BoardGameIdentityGrid.Selection select(
            @PathVariable BoardGameIdentityGrid.Slot slot,
            @RequestBody SelectGameRequest request,
            Principal principal) {
        return grid.select(principal.getName(), slot, request.bggId());
    }

    @DeleteMapping("/{slot}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@PathVariable BoardGameIdentityGrid.Slot slot, Principal principal) {
        grid.clear(principal.getName(), slot);
    }

    record SelectGameRequest(int bggId) {}
}
