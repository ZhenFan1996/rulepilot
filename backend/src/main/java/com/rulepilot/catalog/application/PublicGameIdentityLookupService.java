package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.PublicGameIdentityLookup;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class PublicGameIdentityLookupService implements PublicGameIdentityLookup {

    private final BggRankedCatalogRepository rankedGames;

    PublicGameIdentityLookupService(BggRankedCatalogRepository rankedGames) {
        this.rankedGames = rankedGames;
    }

    @Override
    public Optional<Identity> findByTitle(String title) {
        String checked = checkedTitle(title);
        String expected = normalized(checked);
        return rankedGames.find(new Query(checked, BggGameType.ALL, Sort.RANK, 0, 5, java.util.List.of())).games().stream()
                .filter(game -> normalized(game.sourceName()).equals(expected))
                .findFirst()
                .map(game -> new Identity(
                        game.bggId(), game.sourceName(), "https://boardgamegeek.com/boardgame/" + game.bggId()));
    }

    @Override
    public Map<String, Identity> findByTitles(Collection<String> titles) {
        if (titles == null || titles.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Identity>();
        titles.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(title -> !title.isBlank())
                .distinct()
                .limit(60)
                .forEach(title -> safeFindByTitle(title).ifPresent(identity -> result.put(title, identity)));
        return Map.copyOf(result);
    }

    private Optional<Identity> safeFindByTitle(String title) {
        try {
            return findByTitle(title);
        } catch (RuntimeException unavailableOptionalMetadata) {
            return Optional.empty();
        }
    }

    private String checkedTitle(String title) {
        String checked = title == null ? "" : title.strip().replaceAll("\\s+", " ");
        if (checked.length() < 2 || checked.length() > 120) {
            throw new IllegalArgumentException("public game title must contain 2 to 120 characters");
        }
        return checked;
    }

    private String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
