package com.rulepilot.catalog.adapter.out.bgg;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
                  <image>https://cf.geekdo-images.com/example-large.jpg</image>
                  <name type="primary" value="Wingspan"/>
                  <name type="alternate" value="展翅翱翔"/>
                  <description>A game about birds &amp; habitats.</description>
                  <yearpublished value="2019"/><minplayers value="1"/><maxplayers value="5"/>
                  <playingtime value="70"/><minage value="10"/>
                  <link type="boardgamecategory" value="Animals"/>
                  <link type="boardgamemechanic" value="Card Drafting"/>
                  <link type="boardgamedesigner" value="Elizabeth Hargrave"/>
                  <link type="boardgamepublisher" value="Stonemaier Games"/>
                  <statistics><ratings><average value="8.1"/><averageweight value="2.5"/></ratings></statistics>
                  <versions>
                    <item type="boardgameversion" id="0">
                      <canonicalname value="翼展"/>
                      <name type="primary" value="Chinese edition"/>
                      <link type="language" value="Chinese"/>
                    </item>
                    <item type="boardgameversion" id="1">
                      <thumbnail>https://example.test/version-only.jpg</thumbnail>
                      <canonicalname value="展翅翱翔"/>
                      <name type="primary" value="Simplified Chinese edition"/>
                      <link type="boardgamepublisher" value="Localized Publisher"/>
                      <link type="language" value="Chinese"/>
                    </item>
                    <item type="boardgameversion" id="2">
                      <canonicalname value="Wingspan"/>
                      <name type="primary" value="English edition"/>
                      <link type="language" value="English"/>
                    </item>
                  </versions>
                </item></items>
                """, 266192);

        assertThat(game.name()).isEqualTo("Wingspan");
        assertThat(game.description()).isEqualTo("A game about birds & habitats.");
        assertThat(game.minPlayers()).isEqualTo(1);
        assertThat(game.maxPlayers()).isEqualTo(5);
        assertThat(game.playingTimeMinutes()).isEqualTo(70);
        assertThat(game.imageUrl()).endsWith("example-large.jpg");
        assertThat(game.averageRating()).isEqualByComparingTo("8.1");
        assertThat(game.averageWeight()).isEqualByComparingTo("2.5");
        assertThat(game.categories()).containsExactly("Animals");
        assertThat(game.mechanics()).containsExactly("Card Drafting");
        assertThat(game.designers()).containsExactly("Elizabeth Hargrave");
        assertThat(game.publishers()).containsExactly("Stonemaier Games");
        assertThat(game.officialChineseNames()).containsExactly("展翅翱翔", "翼展");
    }

    @Test
    void parsesHotGameCoversAndRanks() {
        var games = client.parseHotGames("""
                <items termsofuse="https://boardgamegeek.com/xmlapi/termsofuse">
                  <item id="432123" rank="1">
                    <thumbnail value="https://cf.geekdo-images.com/hot-game.jpg"/>
                    <name value="A Hot Strategy Game"/>
                    <yearpublished value="2026"/>
                  </item>
                </items>
                """);

        assertThat(games).hasSize(1);
        assertThat(games.getFirst().rank()).isEqualTo(1);
        assertThat(games.getFirst().name()).isEqualTo("A Hot Strategy Game");
        assertThat(games.getFirst().thumbnailUrl()).endsWith("hot-game.jpg");
    }

    @Test
    void parsesBatchedHotDetailsForPlayerFitWithoutCommunityComments() {
        var games = client.parseDiscoveryGames(
                """
                <items>
                  <item type="boardgame" id="432123">
                    <thumbnail>https://cf.geekdo-images.com/hot-game-detail.jpg</thumbnail>
                    <name type="primary" value="A Hot Strategy Game"/>
                    <name type="alternate" value="デューン 砂の惑星"/>
                    <name type="alternate" value="未经版本验证的中文别名"/>
                    <yearpublished value="2026"/>
                    <minplayers value="2"/><maxplayers value="4"/><playingtime value="90"/>
                    <link type="boardgamecategory" id="1" value="Strategy"/>
                    <link type="boardgamemechanic" id="2" value="Worker Placement"/>
                    <statistics><ratings><average value="7.81234"/><averageweight value="3.14"/></ratings></statistics>
                    <versions>
                      <item type="boardgameversion" id="9">
                        <canonicalname value="热门策略游戏"/>
                        <name type="primary" value="Simplified Chinese edition"/>
                        <link type="language" value="Chinese"/>
                      </item>
                    </versions>
                  </item>
                </items>
                """,
                List.of(new HotGame(
                        3, 432123, "Hot fallback", 2025, "https://cf.geekdo-images.com/hot-fallback.jpg")));

        assertThat(games).hasSize(1);
        assertThat(games.getFirst().rank()).isEqualTo(3);
        assertThat(games.getFirst().name()).isEqualTo("A Hot Strategy Game");
        assertThat(games.getFirst().chineseName()).isEqualTo("热门策略游戏");
        assertThat(games.getFirst().minPlayers()).isEqualTo(2);
        assertThat(games.getFirst().averageRating()).isEqualByComparingTo(new BigDecimal("7.81234"));
        assertThat(games.getFirst().averageWeight()).isEqualByComparingTo(new BigDecimal("3.14"));
        assertThat(games.getFirst().categories()).containsExactly("Strategy");
        assertThat(games.getFirst().mechanics()).containsExactly("Worker Placement");
    }

    @Test
    void enrichesTheHotSetWithOneBatchedThingRequest() throws Exception {
        AtomicInteger hotCalls = new AtomicInteger();
        AtomicInteger thingCalls = new AtomicInteger();
        AtomicReference<String> thingQuery = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/xmlapi2/hot", exchange -> {
            hotCalls.incrementAndGet();
            respond(exchange, """
                    <items>
                      <item id="101" rank="1"><thumbnail value="https://example.test/101.jpg"/><name value="First"/></item>
                      <item id="102" rank="2"><thumbnail value="https://example.test/102.jpg"/><name value="Second"/></item>
                    </items>
                    """);
        });
        server.createContext("/xmlapi2/thing", exchange -> {
            thingCalls.incrementAndGet();
            thingQuery.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                    <items>
                      <item id="101"><thumbnail>https://example.test/101.jpg</thumbnail><name type="primary" value="First"/></item>
                      <item id="102"><thumbnail>https://example.test/102.jpg</thumbnail><name type="primary" value="Second"/></item>
                    </items>
                    """);
        });
        server.start();
        try {
            BggXmlApiClient local = new BggXmlApiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token", Duration.ZERO);

            assertThat(local.hotGameDetails()).extracting(game -> game.bggId()).containsExactly(101, 102);
            assertThat(hotCalls).hasValue(1);
            assertThat(thingCalls).hasValue(1);
            assertThat(thingQuery.get()).contains("id=101,102", "stats=1", "versions=1");
            assertThat(authorization.get()).isEqualTo("Bearer test-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hydratesAnArbitraryCatalogPageInOneBoundedThingRequest() throws Exception {
        AtomicInteger thingCalls = new AtomicInteger();
        AtomicReference<String> thingQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/xmlapi2/thing", exchange -> {
            thingCalls.incrementAndGet();
            thingQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, """
                    <items>
                      <item id="9">
                        <name type="primary" value="Nine"/>
                        <name type="alternate" value="不应采用的中文别名"/>
                        <versions>
                          <item type="boardgameversion" id="99">
                            <canonicalname value="九号"/>
                            <name type="primary" value="Simplified Chinese edition"/>
                            <link type="language" value="Chinese"/>
                          </item>
                        </versions>
                      </item>
                      <item id="7"><name type="primary" value="Seven"/><name type="alternate" value="七号"/></item>
                    </items>
                    """);
        });
        server.start();
        try {
            BggXmlApiClient local = new BggXmlApiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token", Duration.ZERO);

            var first = local.gameDetails(List.of(7, 9));
            var cached = local.gameDetails(List.of(7, 9));

            assertThat(first).extracting(game -> game.bggId()).containsExactly(7, 9);
            assertThat(first.getFirst().chineseName()).isBlank();
            assertThat(first.get(1).chineseName()).isEqualTo("九号");
            assertThat(cached).isSameAs(first);
            assertThat(thingCalls).hasValue(1);
            assertThat(thingQuery.get()).contains("id=7,9", "stats=1", "versions=1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void prioritizesOperatorProvidedAddressesWithoutChangingTheApiHostname() throws Exception {
        var dns = BggXmlApiClient.preferredDns(
                "https://boardgamegeek.com", "192.0.2.10, 2001:db8::10");

        assertThat(dns.lookup("boardgamegeek.com"))
                .extracting(address -> address.getHostAddress())
                .startsWith("192.0.2.10", "2001:db8:0:0:0:0:0:10");
    }

    @Test
    void rejectsHostnamesInResolvedAddressConfiguration() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new BggXmlApiClient(
                        "https://boardgamegeek.com", "test-token", "proxy.example.com", Duration.ZERO))
                .withMessage("BGG resolved addresses must contain only IP literals");
    }

    @Test
    void boundsCachesAndHydratesExactSearchWithOneDetailRequest() throws Exception {
        AtomicInteger searchCalls = new AtomicInteger();
        AtomicInteger thingCalls = new AtomicInteger();
        AtomicReference<String> searchQuery = new AtomicReference<>();
        AtomicReference<String> thingQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/xmlapi2/search", exchange -> {
            searchCalls.incrementAndGet();
            searchQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, """
                    <items>
                      <item id="1"><name type="primary" value="First"/></item>
                      <item id="2"><name type="primary" value="Second"/></item>
                      <item id="3"><name type="primary" value="Third"/></item>
                      <item id="4"><name type="primary" value="Fourth"/></item>
                      <item id="5"><name type="primary" value="Fifth"/></item>
                      <item id="6"><name type="primary" value="Sixth"/></item>
                    </items>
                    """);
        });
        server.createContext("/xmlapi2/thing", exchange -> {
            thingCalls.incrementAndGet();
            thingQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, """
                    <items>
                      <item id="2"><image>https://example.test/second.jpg</image><name type="primary" value="Second"/></item>
                      <item id="1"><thumbnail>https://example.test/first.jpg</thumbnail><name type="primary" value="First"/><yearpublished value="2024"/><minplayers value="2"/><maxplayers value="5"/><playingtime value="75"/><minage value="12"/></item>
                      <item id="3"><name type="primary" value="Third"/></item>
                      <item id="4"><name type="primary" value="Fourth"/></item>
                      <item id="5"><name type="primary" value="Fifth"/></item>
                    </items>
                    """);
        });
        server.start();
        try {
            BggXmlApiClient local = new BggXmlApiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token", Duration.ZERO);

            assertThat(local.search("First")).hasSize(6);
            var first = local.exactMatches("First");
            var cached = local.exactMatches("first");

            assertThat(first).extracting(game -> game.bggId()).containsExactly(1, 2, 3, 4, 5);
            assertThat(first.getFirst().coverUrl()).endsWith("first.jpg");
            assertThat(first.getFirst().minPlayers()).isEqualTo(2);
            assertThat(first.get(1).coverUrl()).endsWith("second.jpg");
            assertThat(cached).isSameAs(first);
            assertThat(searchCalls).hasValue(2);
            assertThat(thingCalls).hasValue(1);
            assertThat(searchQuery.get()).contains("type=boardgame", "exact=1");
            assertThat(thingQuery.get()).contains("id=1,2,3,4,5").doesNotContain("6");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
