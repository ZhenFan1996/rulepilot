package com.rulepilot.catalog.adapter.out.bgg;

import com.rulepilot.catalog.application.BoardGameGeekCatalog;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameMatch;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class BggXmlApiClient implements BoardGameGeekCatalog {

    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private final OkHttpClient http;
    private final String baseUrl;
    private final String token;
    private final long minRequestIntervalNanos;
    private final XMLInputFactory xml;
    private final Map<String, CacheEntry<List<SearchResult>>> searchCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<GameMatch>>> exactMatchCache = new ConcurrentHashMap<>();
    private final Map<Integer, CacheEntry<GameDetails>> gameCache = new ConcurrentHashMap<>();
    private volatile CacheEntry<List<HotGame>> hotCache;
    private volatile CacheEntry<List<DiscoveryGame>> discoveryCache;
    private final AtomicLong nextRequestAt = new AtomicLong();

    @Autowired
    public BggXmlApiClient(
            @Value("${rulepilot.bgg.base-url:https://boardgamegeek.com}") String baseUrl,
            @Value("${rulepilot.bgg.api-token:}") String token) {
        this(baseUrl, token, Duration.ofSeconds(5));
    }

    BggXmlApiClient(String baseUrl, String token, Duration minRequestInterval) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token == null ? "" : token.trim();
        this.minRequestIntervalNanos = minRequestInterval.toNanos();
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
        String key = "broad:" + query.toLowerCase(java.util.Locale.ROOT);
        CacheEntry<List<SearchResult>> cached = searchCache.get(key);
        if (cached != null && cached.valid()) return cached.value();
        String url = baseUrl + "/xmlapi2/search?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=boardgame";
        List<SearchResult> results = parseSearch(get(url));
        searchCache.put(key, new CacheEntry<>(results, Instant.now().plus(CACHE_TTL)));
        return results;
    }

    @Override
    public List<GameMatch> exactMatches(String query) {
        requireConfigured();
        String key = "exact:" + query.toLowerCase(java.util.Locale.ROOT);
        CacheEntry<List<GameMatch>> cached = exactMatchCache.get(key);
        if (cached != null && cached.valid()) return cached.value();
        String searchUrl = baseUrl + "/xmlapi2/search?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=boardgame&exact=1";
        List<SearchResult> candidates = parseSearch(get(searchUrl)).stream().limit(5).toList();
        if (candidates.isEmpty()) {
            exactMatchCache.put(key, new CacheEntry<>(List.of(), Instant.now().plus(CACHE_TTL)));
            return List.of();
        }
        String ids = candidates.stream()
                .map(candidate -> Integer.toString(candidate.bggId()))
                .collect(java.util.stream.Collectors.joining(","));
        List<GameMatch> parsed = parseGameMatches(
                get(baseUrl + "/xmlapi2/thing?id=" + ids + "&type=boardgame"), candidates);
        exactMatchCache.put(key, new CacheEntry<>(parsed, Instant.now().plus(CACHE_TTL)));
        return parsed;
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
    public List<DiscoveryGame> hotGameDetails() {
        requireConfigured();
        CacheEntry<List<DiscoveryGame>> cached = discoveryCache;
        if (cached != null && cached.valid()) return cached.value();
        List<HotGame> candidates = hotGames().stream()
                .filter(game -> !game.thumbnailUrl().isBlank())
                .limit(12)
                .toList();
        if (candidates.isEmpty()) return List.of();
        String ids = candidates.stream()
                .map(game -> Integer.toString(game.bggId()))
                .collect(java.util.stream.Collectors.joining(","));
        List<DiscoveryGame> parsed = parseDiscoveryGames(
                get(baseUrl + "/xmlapi2/thing?id=" + ids + "&type=boardgame&stats=1"), candidates);
        Map<Integer, DiscoveryGame> byId = parsed.stream().collect(java.util.stream.Collectors.toMap(
                DiscoveryGame::bggId, game -> game, (first, ignored) -> first, LinkedHashMap::new));
        List<DiscoveryGame> ordered = candidates.stream()
                .map(candidate -> byId.get(candidate.bggId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        discoveryCache = new CacheEntry<>(ordered, Instant.now().plus(Duration.ofHours(1)));
        return ordered;
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
        nextRequestAt.set(System.nanoTime() + minRequestIntervalNanos);
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

    List<DiscoveryGame> parseDiscoveryGames(String body, List<HotGame> hotGames) {
        Map<Integer, HotGame> hotById = hotGames.stream()
                .collect(java.util.stream.Collectors.toMap(HotGame::bggId, game -> game));
        List<DiscoveryGame> results = new ArrayList<>();
        try {
            XMLStreamReader reader = xml.createXMLStreamReader(new StringReader(body));
            Integer id = null;
            String name = null;
            String thumbnail = "";
            Integer year = null;
            Integer minPlayers = null;
            Integer maxPlayers = null;
            Integer playingTime = null;
            BigDecimal averageRating = null;
            BigDecimal averageWeight = null;
            List<String> categories = new ArrayList<>();
            List<String> mechanics = new ArrayList<>();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "item".equals(reader.getLocalName())) {
                    id = integer(reader.getAttributeValue(null, "id"));
                    name = null;
                    thumbnail = "";
                    year = null;
                    minPlayers = null;
                    maxPlayers = null;
                    playingTime = null;
                    averageRating = null;
                    averageWeight = null;
                    categories = new ArrayList<>();
                    mechanics = new ArrayList<>();
                } else if (event == XMLStreamConstants.START_ELEMENT
                        && "name".equals(reader.getLocalName())
                        && "primary".equals(reader.getAttributeValue(null, "type"))) {
                    name = reader.getAttributeValue(null, "value");
                } else if (event == XMLStreamConstants.START_ELEMENT && "thumbnail".equals(reader.getLocalName())) {
                    thumbnail = reader.getElementText();
                } else if (event == XMLStreamConstants.START_ELEMENT && "yearpublished".equals(reader.getLocalName())) {
                    year = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "minplayers".equals(reader.getLocalName())) {
                    minPlayers = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "maxplayers".equals(reader.getLocalName())) {
                    maxPlayers = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "playingtime".equals(reader.getLocalName())) {
                    playingTime = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "average".equals(reader.getLocalName())) {
                    averageRating = positiveDecimal(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "averageweight".equals(reader.getLocalName())) {
                    averageWeight = positiveDecimal(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "link".equals(reader.getLocalName())) {
                    String type = reader.getAttributeValue(null, "type");
                    String value = reader.getAttributeValue(null, "value");
                    if (value != null && "boardgamecategory".equals(type)) categories.add(value);
                    if (value != null && "boardgamemechanic".equals(type)) mechanics.add(value);
                } else if (event == XMLStreamConstants.END_ELEMENT && "item".equals(reader.getLocalName())
                        && id != null && name != null && hotById.containsKey(id)) {
                    HotGame hot = hotById.get(id);
                    results.add(new DiscoveryGame(
                            hot.rank(),
                            id,
                            name,
                            year,
                            thumbnail.isBlank() ? hot.thumbnailUrl() : thumbnail,
                            minPlayers,
                            maxPlayers,
                            playingTime,
                            averageRating,
                            averageWeight,
                            categories,
                            mechanics));
                }
            }
            reader.close();
            return List.copyOf(results);
        } catch (XMLStreamException exception) {
            throw new IllegalStateException("BGG returned invalid XML", exception);
        }
    }

    List<GameMatch> parseGameMatches(String body, List<SearchResult> candidates) {
        Map<Integer, SearchResult> candidateById = candidates.stream()
                .collect(java.util.stream.Collectors.toMap(SearchResult::bggId, candidate -> candidate));
        Map<Integer, GameMatch> parsedById = new LinkedHashMap<>();
        try {
            XMLStreamReader reader = xml.createXMLStreamReader(new StringReader(body));
            Integer id = null;
            String name = null;
            String image = "";
            String thumbnail = "";
            Integer year = null;
            Integer minPlayers = null;
            Integer maxPlayers = null;
            Integer playingTime = null;
            Integer minimumAge = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "item".equals(reader.getLocalName())) {
                    id = integer(reader.getAttributeValue(null, "id"));
                    name = null;
                    image = "";
                    thumbnail = "";
                    year = null;
                    minPlayers = null;
                    maxPlayers = null;
                    playingTime = null;
                    minimumAge = null;
                } else if (event == XMLStreamConstants.START_ELEMENT
                        && "name".equals(reader.getLocalName())
                        && "primary".equals(reader.getAttributeValue(null, "type"))) {
                    name = reader.getAttributeValue(null, "value");
                } else if (event == XMLStreamConstants.START_ELEMENT && "image".equals(reader.getLocalName())) {
                    image = reader.getElementText();
                } else if (event == XMLStreamConstants.START_ELEMENT && "thumbnail".equals(reader.getLocalName())) {
                    thumbnail = reader.getElementText();
                } else if (event == XMLStreamConstants.START_ELEMENT && "yearpublished".equals(reader.getLocalName())) {
                    year = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "minplayers".equals(reader.getLocalName())) {
                    minPlayers = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "maxplayers".equals(reader.getLocalName())) {
                    maxPlayers = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "playingtime".equals(reader.getLocalName())) {
                    playingTime = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.START_ELEMENT && "minage".equals(reader.getLocalName())) {
                    minimumAge = integer(reader.getAttributeValue(null, "value"));
                } else if (event == XMLStreamConstants.END_ELEMENT && "item".equals(reader.getLocalName())
                        && id != null && name != null && candidateById.containsKey(id)) {
                    SearchResult search = candidateById.get(id);
                    parsedById.put(id, new GameMatch(
                            id,
                            name,
                            year == null ? search.publicationYear() : year,
                            image.isBlank() ? thumbnail : image,
                            minPlayers,
                            maxPlayers,
                            playingTime,
                            minimumAge));
                }
            }
            reader.close();
            return candidates.stream()
                    .map(candidate -> parsedById.get(candidate.bggId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
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

    private BigDecimal positiveDecimal(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) return null;
        try {
            BigDecimal parsed = new BigDecimal(value);
            return parsed.signum() > 0 ? parsed : null;
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
