package com.rulepilot.catalog.adapter.in.web;

import com.rulepilot.catalog.application.GameCatalogService;
import com.rulepilot.catalog.application.GameCatalogView;
import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
public class GameCatalogController {

    private final GameCatalogService catalogService;

    public GameCatalogController(GameCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/games")
    List<GameResponse> listGames() {
        return catalogService.listCatalog().stream().map(GameResponse::from).toList();
    }

    @PostMapping("/games")
    @ResponseStatus(HttpStatus.CREATED)
    GameDetails createGame(@RequestBody CreateGameRequest request) {
        return GameDetails.from(catalogService.createGame(request.name()));
    }

    @PostMapping("/games/{gameId}/editions")
    @ResponseStatus(HttpStatus.CREATED)
    EditionDetails createEdition(@PathVariable UUID gameId, @RequestBody CreateEditionRequest request) {
        return EditionDetails.from(catalogService.createEdition(
                gameId, request.name(), request.language(), request.publicationYear()));
    }

    @PostMapping("/games/{gameId}/expansions")
    @ResponseStatus(HttpStatus.CREATED)
    ExpansionDetails createExpansion(@PathVariable UUID gameId, @RequestBody CreateExpansionRequest request) {
        return ExpansionDetails.from(catalogService.createExpansion(
                gameId, request.name(), request.compatibleEditionIds()));
    }

    record CreateGameRequest(String name) {}

    record CreateEditionRequest(String name, String language, Integer publicationYear) {}

    record CreateExpansionRequest(String name, Set<UUID> compatibleEditionIds) {}

    record GameResponse(GameDetails game, List<EditionDetails> editions, List<ExpansionDetails> expansions) {
        static GameResponse from(GameCatalogView view) {
            return new GameResponse(
                    GameDetails.from(view.game()),
                    view.editions().stream().map(EditionDetails::from).toList(),
                    view.expansions().stream().map(ExpansionDetails::from).toList());
        }
    }

    record GameDetails(UUID id, String name, Instant createdAt) {
        static GameDetails from(Game game) {
            return new GameDetails(game.id(), game.name(), game.createdAt());
        }
    }

    record EditionDetails(UUID id, UUID gameId, String name, String language, Integer publicationYear) {
        static EditionDetails from(GameEdition edition) {
            return new EditionDetails(
                    edition.id(), edition.gameId(), edition.name(), edition.language(), edition.publicationYear());
        }
    }

    record ExpansionDetails(UUID id, UUID gameId, String name, Set<UUID> compatibleEditionIds) {
        static ExpansionDetails from(Expansion expansion) {
            return new ExpansionDetails(
                    expansion.id(), expansion.gameId(), expansion.name(), expansion.compatibleEditionIds());
        }
    }
}
