package com.rulepilot.catalog.adapter.out.bgg;

import com.rulepilot.catalog.application.BoardGameGeekCatalog;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class BggXmlApiClient implements BoardGameGeekCatalog {

    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final long MIN_REQUEST_INTERVAL_NANOS = Duration.ofSeconds(5).toNanos();

    private final OkHttpClient http;
    private final String baseUrl;
    private final String token;
    private final XMLInputFactory xml;
    private final Map<String, CacheEntry<List<SearchResult>>> searchCache = new ConcurrentHashMap<>();
    private final Map<Integer, CacheEntry<GameDetails>> gameCache = new ConcurrentHashMap<>();
    private volatile CacheEntry<List<HotGame>> hotCache;
    private final AtomicLong nextRequestAt = new AtomicLong();

    public BggXmlApiClient(
            @Value("${rulepilot.bgg.base-url:https://boardgamegeek.com}") String baseUrl,
            @Value("${rulepilot.bgg.api-token:}") String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token == null ? "" : token.trim();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        this.xml = secureXmlFactory();
    }

    @Override
    public boolean configured() {
        return !token.isBlank();
    }

    @Override
    public List<SearchResult> search(String query) {
        requireConfigured();
        String key = query.toLowerCase(java.util.Locale.ROOT);
        CacheEntry<List<SearchResult>> cached = searchCache.get(key);
        if (cached != null && cached.valid()) return cached.value();
        String url = baseUrl + "/xmlapi2/search?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=boardgame";
        List<SearchResult> results = parseSearch(get(url));
        searchCache.put(key, new CacheEntry<>(results, Instant.now().plus(CACHE_TTL)));
        return results;
    }

    @Override
    public List<HotGame> hotGames() {
        requireConfigured();
        CacheEntry<List<HotGame>> cached = hotCache;
        if (cached != null && cached.valid()) return cached.value();
        List<HotGame> games = parseHotGames(get(baseUrl + "/xmlapi2/hot?type=boardgame"));
        hotCache = new CacheEntry<>(games, Instant.now().plus(Duration.ofHours(1)));
        return games;
    }

    @Override
    public GameDetails game(int bggId) {
        requireConfigured();
        CacheEntry<GameDetails> cached = gameCache.get(bggId);
        if (cached != null && cached.valid()) return cached.value();
        GameDetails details = parseGame(get(baseUrl + "/xmlapi2/thing?id=" + bggId + "&type=boardgame"), bggId);
        gameCache.put(bggId, new CacheEntry<>(details, Instant.now().plus(CACHE_TTL)));
        return details;
    }

    private String get(String url) {
        throttle();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/xml")
                .header("User-Agent", "RulePilot/0.1 BGG catalog integration")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("BGG request failed with status " + response.code());
            }
            byte[] bytes = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("BGG response is too large");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("BGG is temporarily unavailable", exception);
        }
    }

    private synchronized void throttle() {
        long now = System.nanoTime();
        long wait = nextRequestAt.get() - now;
        if (wait > 0) LockSupport.parkNanos(wait);
        nextRequestAt.set(System.nanoTime() + MIN_REQUEST_INTERVAL_NANOS);
    }

    List<SearchResult> parseSearch(String body) {
        List<SearchResult> results = new ArrayList<>();
        try {
            XMLStreamReader reader = xml.createXMLStreamReader(new StringReader(body));
            Integer id = null;
            String name = null;
            Integer year = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "item".equals(reader.getLocalName())) {
                    id = integer(reader.getAttributeValue(null, "id"));
                    name = null;
                    year = null;
                } else if (event == XMLStreamConstants.START_ELEMENT && "name".equals(reader.getLocalName())) {
                    name = reader.getAttributeValue(null, "value");
                } else if (event == XMLStreamConstants.START_ELEMENT && "yearpublished".equals(reader.getLocalName())) {
                    year = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.END_ELEMENT && "item".equals(reader.getLocalName())
                        && id != null && name != null) {
                    results.add(new SearchResult(id, name, year));
                }
            }
            reader.close();
            return List.copyOf(results);
        } catch (XMLStreamException exception) {
            throw new IllegalStateException("BGG returned invalid XML", exception);
        }
    }

    List<HotGame> parseHotGames(String body) {
        List<HotGame> results = new ArrayList<>();
        try {
            XMLStreamReader reader = xml.createXMLStreamReader(new StringReader(body));
            Integer rank = null;
            Integer id = null;
            String name = null;
            String thumbnail = "";
            Integer year = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "item".equals(reader.getLocalName())) {
                    rank = integer(reader.getAttributeValue(null, "rank"));
                    id = integer(reader.getAttributeValue(null, "id"));
                    name = null;
                    thumbnail = "";
                    year = null;
                } else if (event == XMLStreamConstants.START_ELEMENT && "name".equals(reader.getLocalName())) {
                    name = reader.getAttributeValue(null, "value");
                } else if (event == XMLStreamConstants.START_ELEMENT && "thumbnail".equals(reader.getLocalName())) {
                    thumbnail = reader.getAttributeValue(null, "value");
                } else if (event == XMLStreamConstants.START_ELEMENT && "yearpublished".equals(reader.getLocalName())) {
                    year = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.END_ELEMENT && "item".equals(reader.getLocalName())
                        && rank != null && id != null && name != null) {
                    results.add(new HotGame(rank, id, name, year, thumbnail));
                }
            }
            reader.close();
            return List.copyOf(results);
        } catch (XMLStreamException exception) {
            throw new IllegalStateException("BGG returned invalid XML", exception);
        }
    }

    GameDetails parseGame(String body, int expectedId) {
        try {
            XMLStreamReader reader = xml.createXMLStreamReader(new StringReader(body));
            String name = null;
            String description = "";
            String thumbnail = "";
            Integer year = null;
            Integer minPlayers = null;
            Integer maxPlayers = null;
            Integer playingTime = null;
            Integer minimumAge = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                String element = reader.getLocalName();
                if ("name".equals(element) && "primary".equals(reader.getAttributeValue(null, "type"))) {
                    name = reader.getAttributeValue(null, "value");
                } else if ("description".equals(element)) {
                    description = reader.getElementText();
                } else if ("thumbnail".equals(element)) {
                    thumbnail = reader.getElementText();
                } else if ("yearpublished".equals(element)) {
                    year = integer(reader.getAttributeValue(null, "value"));
                } else if ("minplayers".equals(element)) {
                    minPlayers = integer(reader.getAttributeValue(null, "value"));
                } else if ("maxplayers".equals(element)) {
                    maxPlayers = integer(reader.getAttributeValue(null, "value"));
                } else if ("playingtime".equals(element)) {
                    playingTime = integer(reader.getAttributeValue(null, "value"));
                } else if ("minage".equals(element)) {
                    minimumAge = integer(reader.getAttributeValue(null, "value"));
                }
            }
            reader.close();
            if (name == null) throw new IllegalArgumentException("BGG game does not exist: " + expectedId);
            return new GameDetails(
                    expectedId, name, description, thumbnail, year, minPlayers, maxPlayers, playingTime, minimumAge);
        } catch (XMLStreamException exception) {
            throw new IllegalStateException("BGG returned invalid XML", exception);
        }
    }

    private Integer integer(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void requireConfigured() {
        if (!configured()) throw new IllegalStateException("BGG application token is not configured");
    }

    private XMLInputFactory secureXmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        return factory;
    }

    private record CacheEntry<T>(T value, Instant expiresAt) {
        boolean valid() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
