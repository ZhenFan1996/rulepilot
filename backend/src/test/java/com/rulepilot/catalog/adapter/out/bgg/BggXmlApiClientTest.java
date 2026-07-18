package com.rulepilot.catalog.adapter.out.bgg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BggXmlApiClientTest {

    private final BggXmlApiClient client = new BggXmlApiClient("https://boardgamegeek.com", "test-token");

    @Test
    void parsesSearchResultsWithoutRequestingDetails() {
        var results = client.parseSearch("""
                <items total="2">
                  <item type="boardgame" id="266192"><name type="primary" value="Wingspan"/><yearpublished value="2019"/></item>
                  <item type="boardgame" id="329082"><name type="primary" value="Cascadia"/><yearpublished value="2021"/></item>
                </items>
                """);

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().name()).isEqualTo("Wingspan");
        assertThat(results.getFirst().publicationYear()).isEqualTo(2019);
    }

    @Test
    void parsesOnlyControlledGameMetadataFields() {
        var game = client.parseGame("""
                <items><item type="boardgame" id="266192">
                  <thumbnail>https://cf.geekdo-images.com/example.jpg</thumbnail>
                  <name type="primary" value="Wingspan"/>
                  <description>A game about birds &amp; habitats.</description>
                  <yearpublished value="2019"/><minplayers value="1"/><maxplayers value="5"/>
                  <playingtime value="70"/><minage value="10"/>
                </item></items>
                """, 266192);

        assertThat(game.name()).isEqualTo("Wingspan");
        assertThat(game.description()).isEqualTo("A game about birds & habitats.");
        assertThat(game.minPlayers()).isEqualTo(1);
        assertThat(game.maxPlayers()).isEqualTo(5);
        assertThat(game.playingTimeMinutes()).isEqualTo(70);
    }
}
