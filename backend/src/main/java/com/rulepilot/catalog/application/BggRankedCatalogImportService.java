package com.rulepilot.catalog.application;

import com.rulepilot.catalog.application.BggRankedCatalog.GameType;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class BggRankedCatalogImportService {

    static final List<String> EXPECTED_HEADERS = List.of(
            "id",
            "name",
            "yearpublished",
            "rank",
            "bayesaverage",
            "average",
            "usersrated",
            "is_expansion",
            "abstracts_rank",
            "cgs_rank",
            "childrensgames_rank",
            "familygames_rank",
            "partygames_rank",
            "strategygames_rank",
            "thematic_rank",
            "wargames_rank");
    private static final Pattern SOURCE_DATE = Pattern.compile("boardgames_ranks_(\\d{4}-\\d{2}-\\d{2})\\.zip");
    private static final int BATCH_SIZE = 500;
    private static final long MAX_UNCOMPRESSED_BYTES = 100_000_000;

    private final BggRankedCatalogRepository repository;
    private final Clock clock;
    private final int minimumRows;
    private final int maximumRows;

    @Autowired
    public BggRankedCatalogImportService(
            BggRankedCatalogRepository repository,
            @Value("${rulepilot.bgg.ranked-catalog.minimum-rows:100000}") int minimumRows,
            @Value("${rulepilot.bgg.ranked-catalog.maximum-rows:500000}") int maximumRows) {
        this(repository, Clock.systemUTC(), minimumRows, maximumRows);
    }

    BggRankedCatalogImportService(
            BggRankedCatalogRepository repository, Clock clock, int minimumRows, int maximumRows) {
        this.repository = repository;
        this.clock = clock;
        this.minimumRows = minimumRows;
        this.maximumRows = maximumRows;
    }

    @Transactional
    public Snapshot importDump(InputStream input, String filename) {
        if (input == null) throw new IllegalArgumentException("BGG ranked catalog file is required");
        String checkedFilename = filename == null ? "" : filename.strip();
        if (!checkedFilename.endsWith(".zip") && !checkedFilename.endsWith(".csv")) {
            throw new IllegalArgumentException("BGG ranked catalog must be a .zip or .csv file");
        }
        MessageDigest digest = sha256();
        UUID importId = UUID.randomUUID();
        int count;
        try (DigestInputStream digested = new DigestInputStream(new BufferedInputStream(input), digest)) {
            count = checkedFilename.endsWith(".zip")
                    ? importZip(importId, digested)
                    : importCsv(importId, new LimitedInputStream(digested, MAX_UNCOMPRESSED_BYTES));
        } catch (IOException exception) {
            throw new IllegalArgumentException("BGG ranked catalog could not be read", exception);
        }
        if (count < minimumRows || count > maximumRows) {
            throw new IllegalArgumentException(
                    "BGG ranked catalog row count must be between " + minimumRows + " and " + maximumRows);
        }
        Snapshot snapshot = new Snapshot(
                Instant.now(clock), sourceDate(checkedFilename), count, HexFormat.of().formatHex(digest.digest()));
        repository.publish(importId, snapshot);
        return snapshot;
    }

    private int importZip(UUID importId, InputStream input) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && (name.equals("boardgames_ranks.csv") || name.endsWith("/boardgames_ranks.csv"))) {
                    int count = importCsv(
                            importId, new LimitedInputStream(new NonClosingInputStream(zip), MAX_UNCOMPRESSED_BYTES));
                    zip.closeEntry();
                    while (zip.getNextEntry() != null) {
                        while (zip.read() >= 0) {
                            // Consume the complete archive so the uploaded-file digest covers every byte.
                        }
                    }
                    return count;
                }
            }
        }
        throw new IllegalArgumentException("BGG ranked catalog archive does not contain boardgames_ranks.csv");
    }

    private int importCsv(UUID importId, InputStream input) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .get();
        try (CSVParser parser = format.parse(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            if (!EXPECTED_HEADERS.equals(parser.getHeaderNames())) {
                throw new IllegalArgumentException("BGG ranked catalog headers do not match the official schema");
            }
            List<RankedGame> batch = new ArrayList<>(BATCH_SIZE);
            int count = 0;
            for (CSVRecord record : parser) {
                batch.add(parse(record));
                count++;
                if (count > maximumRows) {
                    throw new IllegalArgumentException("BGG ranked catalog contains too many rows");
                }
                if (batch.size() == BATCH_SIZE) {
                    repository.stage(importId, batch);
                    batch = new ArrayList<>(BATCH_SIZE);
                }
            }
            repository.stage(importId, batch);
            return count;
        }
    }

    private RankedGame parse(CSVRecord record) {
        String name = record.get("name");
        if (name == null || name.isBlank()) throw invalid(record, "name is missing");
        int id = requiredPositiveInteger(record, "id");
        Integer year = optionalPositiveInteger(record, "yearpublished");
        Integer rank = optionalPositiveInteger(record, "rank");
        BigDecimal bayesAverage = requiredDecimal(record, "bayesaverage");
        BigDecimal averageRating = requiredDecimal(record, "average");
        int usersRated = requiredNonNegativeInteger(record, "usersrated");
        boolean expansion = switch (record.get("is_expansion")) {
            case "1" -> true;
            case "0" -> false;
            default -> throw invalid(record, "is_expansion must be 0 or 1");
        };
        Map<GameType, Integer> typeRanks = new EnumMap<>(GameType.class);
        addRank(record, typeRanks, GameType.ABSTRACT, "abstracts_rank");
        addRank(record, typeRanks, GameType.CUSTOMIZABLE, "cgs_rank");
        addRank(record, typeRanks, GameType.CHILDREN, "childrensgames_rank");
        addRank(record, typeRanks, GameType.FAMILY, "familygames_rank");
        addRank(record, typeRanks, GameType.PARTY, "partygames_rank");
        addRank(record, typeRanks, GameType.STRATEGY, "strategygames_rank");
        addRank(record, typeRanks, GameType.THEMATIC, "thematic_rank");
        addRank(record, typeRanks, GameType.WAR, "wargames_rank");
        if (expansion) typeRanks.put(GameType.EXPANSION, 1);
        return new RankedGame(
                id, name, year, rank, bayesAverage, averageRating, usersRated, expansion, typeRanks);
    }

    private void addRank(CSVRecord record, Map<GameType, Integer> ranks, GameType type, String column) {
        Integer value = optionalPositiveInteger(record, column);
        if (value != null) ranks.put(type, value);
    }

    private int requiredPositiveInteger(CSVRecord record, String column) {
        Integer value = optionalPositiveInteger(record, column);
        if (value == null) throw invalid(record, column + " must be a positive integer");
        return value;
    }

    private int requiredNonNegativeInteger(CSVRecord record, String column) {
        String value = record.get(column).strip();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw invalid(record, column + " must not be negative");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(record, column + " must be an integer");
        }
    }

    private Integer optionalPositiveInteger(CSVRecord record, String column) {
        String value = record.get(column).strip();
        if (value.isEmpty() || value.equalsIgnoreCase("Not Ranked")) return null;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            throw invalid(record, column + " must be an integer or empty");
        }
    }

    private BigDecimal requiredDecimal(CSVRecord record, String column) {
        try {
            BigDecimal value = new BigDecimal(record.get(column).strip());
            if (value.signum() < 0 || value.compareTo(BigDecimal.TEN) > 0) {
                throw invalid(record, column + " must be between 0 and 10");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw invalid(record, column + " must be a decimal");
        }
    }

    private IllegalArgumentException invalid(CSVRecord record, String message) {
        return new IllegalArgumentException("Invalid BGG ranked catalog row " + record.getRecordNumber() + ": " + message);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private LocalDate sourceDate(String filename) {
        Matcher matcher = SOURCE_DATE.matcher(filename);
        return matcher.find() ? LocalDate.parse(matcher.group(1)) : null;
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long consumed;

        private LimitedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) consumed(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) consumed(read);
            return read;
        }

        private void consumed(int bytes) throws IOException {
            consumed += bytes;
            if (consumed > maximum) throw new IOException("BGG ranked catalog is too large");
        }
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        private NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
            // The ZIP reader owns this stream and must continue through the central directory.
        }
    }
}
