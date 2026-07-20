package com.rulepilot.catalog.application;

import com.rulepilot.catalog.CatalogEditionProvisioning;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
class DefaultCatalogEditionProvisioner implements CatalogEditionProvisioning {

    private static final String DEFAULT_EDITION_NAME = "规则书自动版本";
    private final CatalogRepository repository;
    private final Clock clock = Clock.systemUTC();

    DefaultCatalogEditionProvisioner(CatalogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public UUID provisionDefaultEdition(String suggestedGameName) {
        String gameName = boundedGameName(suggestedGameName);
        Game game = repository.findGameByName(gameName)
                .orElseGet(() -> repository.save(Game.create(gameName, Instant.now(clock))));
        return repository.findEdition(game.id(), DEFAULT_EDITION_NAME, "und")
                .orElseGet(() -> repository.save(GameEdition.create(
                        game.id(), DEFAULT_EDITION_NAME, "und", null, Instant.now(clock))))
                .id();
    }

    private String boundedGameName(String suggestedGameName) {
        String value = suggestedGameName == null ? "" : suggestedGameName.strip();
        if (value.isBlank()) {
            return "未命名规则书";
        }
        return value.length() <= 120 ? value : value.substring(0, 120).stripTrailing();
    }
}
