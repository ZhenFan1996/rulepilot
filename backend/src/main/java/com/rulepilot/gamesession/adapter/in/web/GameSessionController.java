package com.rulepilot.gamesession.adapter.in.web;

import com.rulepilot.gamesession.application.GameSessionService;
import com.rulepilot.gamesession.domain.GameSession;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/game-sessions")
public class GameSessionController {

    private final GameSessionService sessions;

    public GameSessionController(GameSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    GameSession start(@RequestBody StartSessionRequest request, Principal principal) {
        return sessions.start(
                request.editionId(), request.documentVersionId(), request.expansionIds(), request.playerCount(),
                request.phase(), request.activePlayer(), principal.getName());
    }

    @GetMapping("/{sessionId}")
    GameSession get(@PathVariable UUID sessionId, Principal principal) {
        return sessions.get(sessionId, principal.getName());
    }

    @PatchMapping("/{sessionId}/turn")
    GameSession updateTurn(
            @PathVariable UUID sessionId, @RequestBody UpdateTurnRequest request, Principal principal) {
        return sessions.updateTurn(
                sessionId, request.roundNumber(), request.phase(), request.activePlayer(), principal.getName());
    }

    record StartSessionRequest(
            UUID editionId,
            UUID documentVersionId,
            Set<UUID> expansionIds,
            int playerCount,
            String phase,
            Integer activePlayer) {}

    record UpdateTurnRequest(int roundNumber, String phase, Integer activePlayer) {}
}
