package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.PublicRulebookReferenceLookup.Reference;
import com.rulepilot.document.adapter.out.pdf.PdfBoxPhotographedRulebookAssembler;
import com.rulepilot.document.adapter.out.source.HttpGstoneRulebookCatalogLookup;
import com.rulepilot.document.adapter.out.source.HttpOfficialRulebookSourceFetcher;
import com.rulepilot.document.adapter.out.source.HttpOfficialRulebookSourceInspector;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.domain.OfficialRulebookImportJob.TeachingHandoff;
import com.rulepilot.document.domain.OfficialRulebookImportJob.TeachingHandoffState;
import com.rulepilot.document.domain.RuleDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-rulebook-acquisition-evaluation")
class OfficialRulebookTwentyGameRealChainEvaluationTest {

    private static final long MAXIMUM_PDF_BYTES = 50L * 1024 * 1024;
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final String OWNER = "p24-07-real-chain-evaluation";
    private static final List<GameCase> CASES = List.of(
            game(29568, 342942, "Ark Nova", "方舟动物园", 2021, "zh-CN", 4505, 20),
            game(10128, 266192, "Wingspan", "展翅翱翔", 2019, "zh-CN", 1149, 12),
            game(1170, 230802, "Azul", "花砖物语", 2017, "zh-CN", 509, 5),
            game(545, 13, "Catan", "卡坦岛", 1995, "zh-CN", 37, 3),
            game(585, 822, "Carcassonne", "卡卡颂", 2000, "zh-CN", 51, 7),
            game(583, 9209, "Ticket to Ride", "铁路环游", 2004, "en", 309, 4),
            game(564, 30549, "Pandemic", "瘟疫危机", 2008, "zh-CN", 1031, 8),
            game(550, 167791, "Terraforming Mars", "重塑火星", 2016, "zh-CN", 2082, 16),
            game(1305, 237182, "Root", "茂林源记：森林的权力之战", 2018, "en", 1548, 12),
            game(599, 169786, "Scythe", "镰刀战争", 2016, "en", 1750, 32),
            game(566, 31260, "Agricola", "农家乐", 2007, "en", 1333, 12),
            game(4320, 224517, "Brass: Birmingham", "工业革命：伯明翰", 2018, "en", 392, 12),
            game(919, 162886, "Spirit Island", "灵迹岛", 2017, "en", 1850, 32),
            game(24210, 295947, "Cascadia", "卡斯卡迪亚之旅", 2021, "zh-CN", 4597, 16),
            game(568, 39856, "Dixit", "妙语说书人", 2008, "zh-CN", 402, 2),
            game(578, 163412, "Patchwork", "拼布艺术", 2014, "en", 383, 6),
            game(560, 124361, "Concordia", "康考迪娅", 2013, "en", 350, 4),
            game(22677, 312484, "Lost Ruins of Arnak", "阿纳克遗迹", 2020, "zh-CN", 4407, 24),
            game(33628, 366013, "Heat: Pedal to the Metal", "火爆狂飙：全速前进", 2022, "zh-CN", 5573, 7),
            game(24495, 316554, "Dune: Imperium", "沙丘：帝国", 2020, "zh-CN", 4417, 20));

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void discoversBuildsStoresAndQueuesTwentyPublicRulebooksThroughTheCurrentImportChain() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULEBOOK_TWENTY_GAME_EVAL")));
        assertThat(CASES).hasSizeGreaterThanOrEqualTo(20);
        assertThat(CASES.stream().map(GameCase::gameName)).doesNotHaveDuplicates();

        Map<UUID, GameCase> byEdition = new LinkedHashMap<>();
        CASES.forEach(case_ -> byEdition.put(case_.editionId(), case_));
        var storage = new DiscardingPdfStorage();
        var documents = new InMemoryRuleDocumentRepository();
        var processingQueue = new RecordingProcessingQueue();
        CatalogEditionLookup editions = editionId -> Optional.ofNullable(byEdition.get(editionId))
                .map(case_ -> new CatalogEditionLookup.EditionReference(
                        case_.editionId(), case_.gameId(), case_.gameName(), "Base", case_.language(), Set.of()));
        var storageService = new RuleDocumentStorageService(storage, storageProperties());
        var uploadService = new UploadRuleDocumentService(
                editions,
                storageService,
                storage,
                documents,
                processingQueue,
                mock(UploadedRulebookTeachingHandoffService.class));
        var fetcher = new HttpOfficialRulebookSourceFetcher(
                storageProperties(),
                new PdfBoxPhotographedRulebookAssembler(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(90),
                Duration.ofMinutes(10),
                1024 * 1024);
        var imports = new OfficialRulebookImportService(fetcher, uploadService);
        var jobs = new InMemoryImportJobs();
        var importJobs = new OfficialRulebookImportJobService(
                jobs, imports, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
        var gstone = new HttpGstoneRulebookCatalogLookup(json, true, Duration.ofSeconds(8));
        var inspector = new HttpOfficialRulebookSourceInspector(Duration.ofSeconds(8), 1024 * 1024);
        OfficialRulebookCandidateFinder configuredModelFinder = unreachableConfiguredModelFinder();
        var results = new ArrayList<Map<String, Object>>();
        var failures = new ArrayList<String>();
        long totalPdfBytes = 0;
        int totalPages = 0;

        for (GameCase case_ : CASES) {
            long started = System.nanoTime();
            var result = baseResult(case_);
            try {
                var discovery = discovery(case_, gstone, inspector, configuredModelFinder);
                var discovered = discovery.discover(case_.editionId(), case_.language());
                String expectedGamePage = "https://www.gstonegames.com/game/info-%d.html".formatted(case_.gstoneId());
                String expectedDocument = "https://www.gstonegames.com/game/doc-%d.html".formatted(case_.documentId());
                assertThat(discovered.configured()).isTrue();
                assertThat(discovered.candidates())
                        .extracting(OfficialRulebookDiscoveryService.Candidate::url)
                        .contains(expectedGamePage, expectedDocument);
                var selected = discovered.candidates().stream()
                        .filter(candidate -> candidate.url().equals(expectedDocument))
                        .findFirst()
                        .orElseThrow();
                assertThat(selected.sourceType())
                        .isEqualTo(OfficialRulebookDiscoveryService.SourceType.COMMUNITY_PLATFORM);
                assertThat(selected.acquisitionMode())
                        .isEqualTo(OfficialRulebookDiscoveryService.AcquisitionMode.IMAGE_GALLERY);
                assertThat(selected.language()).isEqualTo(case_.language());

                storage.expect(case_);
                var launch = importJobs.enqueue(
                        new OfficialRulebookImportJobService.Command(
                                case_.editionId(),
                                case_.gameName() + " Rulebook",
                                DocumentSourceType.BASE_RULEBOOK,
                                selected.url(),
                                true),
                        OWNER);
                var completed = importJobs.requireOwned(launch.job().id(), OWNER);
                assertThat(launch.reused()).isFalse();
                assertThat(completed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.COMPLETED);
                assertThat(completed.errorCode()).isNull();
                assertThat(completed.documentVersionId()).isNotNull();
                assertThat(completed.downloadedBytes()).isPositive();
                assertThat(jobs.stages(completed.id()))
                        .containsSubsequence(
                                OfficialRulebookImportJob.Stage.CONNECTING,
                                OfficialRulebookImportJob.Stage.DOWNLOADING,
                                OfficialRulebookImportJob.Stage.VERIFYING_FILE,
                                OfficialRulebookImportJob.Stage.SAVING,
                                OfficialRulebookImportJob.Stage.COMPLETED);
                assertThat(processingQueue.versionIds()).contains(completed.documentVersionId());

                UploadEvidence evidence = storage.require(case_);
                assertThat(evidence.pages()).isEqualTo(case_.expectedPages());
                assertThat(evidence.bytes()).isBetween(1L, MAXIMUM_PDF_BYTES);
                totalPdfBytes += evidence.bytes();
                totalPages += evidence.pages();
                result.put("outcome", "SUCCESS");
                result.put("gamePage", expectedGamePage);
                result.put("documentPage", expectedDocument);
                result.put("candidateCount", discovered.candidates().size());
                result.put("acquisitionMode", selected.acquisitionMode().name());
                result.put("sourceImageTransform", "same observed Gstone OSS object; max 2000px JPEG q88");
                result.put("pdfPages", evidence.pages());
                result.put("pdfBytes", evidence.bytes());
                result.put("pdfSha256", evidence.sha256());
                result.put("downloadedImageBytes", completed.downloadedBytes());
                result.put("processingQueued", true);
            } catch (RuntimeException | AssertionError exception) {
                result.put("outcome", "FAILED");
                result.put("failureType", exception.getClass().getSimpleName());
                result.put("failureMessage", safeMessage(exception));
                failures.add(case_.gameName() + ": " + safeMessage(exception));
            }
            result.put("elapsedMs", elapsedMillis(started));
            results.add(Map.copyOf(result));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put(
                "currentChain",
                List.of(
                        "recommendation-selected catalog edition identity",
                        "OfficialRulebookDiscoveryService",
                        "public Gstone App exact-name lookup",
                        "bounded source-page inspection",
                        "explicit ordered image-gallery acquisition",
                        "HttpOfficialRulebookSourceFetcher",
                        "PdfBoxPhotographedRulebookAssembler",
                        "OfficialRulebookImportJobService",
                        "OfficialRulebookImportService",
                        "RuleDocumentStorageService",
                        "DocumentProcessingQueue"));
        report.put("gamesAttempted", CASES.size());
        report.put("gamesSucceeded", CASES.size() - failures.size());
        report.put("allGamesConstructedPdf", failures.isEmpty());
        report.put("totalPdfPages", totalPages);
        report.put("totalPdfBytes", totalPdfBytes);
        report.put("results", results);
        report.put(
                "controls",
                Map.of(
                        "explicitConsentUsed", true,
                        "configuredModelSearchSkipped", true,
                        "credentialsUsed", false,
                        "loginStateUsed", false,
                        "rawPdfPersisted", false,
                        "rawRulebookTextPersisted", false,
                        "documentStorageWasDiscardingEvaluationPort", true,
                        "processingWasQueued", true,
                        "gstoneImageCompressionUsed", true,
                        "maximumPdfBytes", MAXIMUM_PDF_BYTES));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path output = root.resolve(".local/agent-evaluation/rulebook-acquisition-twenty-game-real-chain.json");
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);

        assertThat(failures).as("every selected game must build and enter the current import chain").isEmpty();
        assertThat(storage.evidenceCount()).isEqualTo(CASES.size());
        assertThat(processingQueue.versionIds()).hasSize(CASES.size());
    }

    private OfficialRulebookDiscoveryService discovery(
            GameCase case_,
            GstoneRulebookCatalogLookup gstone,
            OfficialRulebookSourceInspector inspector,
            OfficialRulebookCandidateFinder finder) {
        CatalogGamePresentationLookup catalog = editionId -> editionId.equals(case_.editionId())
                ? Optional.of(new CatalogGamePresentationLookup.Presentation(
                        case_.editionId(),
                        case_.gameName(),
                        "Base",
                        case_.language(),
                        case_.publicationYear(),
                        case_.bggId(),
                        "",
                        null,
                        null,
                        null,
                        null,
                        "https://boardgamegeek.com/boardgame/" + case_.bggId()))
                : Optional.empty();
        CatalogGameSourceIdentityLookup identities = bggId -> bggId == case_.bggId()
                ? Optional.of(new CatalogGameSourceIdentityLookup.Identity(
                        case_.gameName(), List.of(case_.gameName(), case_.chineseName()), List.of()))
                : Optional.empty();
        return new OfficialRulebookDiscoveryService(catalog, identities, finder, gstone, inspector, "");
    }

    private OfficialRulebookCandidateFinder unreachableConfiguredModelFinder() {
        return new OfficialRulebookCandidateFinder() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public List<Candidate> find(Request request) {
                throw new AssertionError("a matching Gstone rulebook must bypass configured model search");
            }
        };
    }

    private Map<String, Object> baseResult(GameCase case_) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gameName", case_.gameName());
        result.put("chineseName", case_.chineseName());
        result.put("bggId", case_.bggId());
        result.put("gstoneId", case_.gstoneId());
        result.put("documentId", case_.documentId());
        result.put("requestedLanguage", case_.language());
        result.put("expectedPages", case_.expectedPages());
        return result;
    }

    private MinioStorageProperties storageProperties() {
        return new MinioStorageProperties(
                "http://127.0.0.1:9000",
                "evaluation-access",
                "evaluation-secret",
                "rulepilot-evaluation",
                MAXIMUM_PDF_BYTES);
    }

    private static GameCase game(
            int gstoneId,
            int bggId,
            String gameName,
            String chineseName,
            int publicationYear,
            String language,
            int documentId,
            int expectedPages) {
        UUID editionId = UUID.nameUUIDFromBytes(("p24-07-edition:" + gameName).getBytes(StandardCharsets.UTF_8));
        UUID gameId = UUID.nameUUIDFromBytes(("p24-07-game:" + gameName).getBytes(StandardCharsets.UTF_8));
        return new GameCase(
                gstoneId,
                bggId,
                gameName,
                chineseName,
                publicationYear,
                language,
                documentId,
                expectedPages,
                editionId,
                gameId);
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        String normalized = message.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record GameCase(
            int gstoneId,
            int bggId,
            String gameName,
            String chineseName,
            int publicationYear,
            String language,
            int documentId,
            int expectedPages,
            UUID editionId,
            UUID gameId) {}

    private record UploadEvidence(String objectKey, int pages, long bytes, String sha256) {}

    private static final class DiscardingPdfStorage implements DocumentStorage {
        private final Map<String, UploadEvidence> evidenceByGame = new LinkedHashMap<>();
        private final Map<String, String> gameByObjectKey = new LinkedHashMap<>();
        private GameCase expected;

        void expect(GameCase case_) {
            expected = case_;
        }

        UploadEvidence require(GameCase case_) {
            UploadEvidence evidence = evidenceByGame.get(case_.gameName());
            if (evidence == null) throw new IllegalStateException("PDF storage evidence is missing");
            return evidence;
        }

        int evidenceCount() {
            return evidenceByGame.size();
        }

        @Override
        public StoredDocument store(String objectKey, InputStream content, long size, String contentType) {
            if (expected == null) throw new IllegalStateException("expected game was not selected before storage");
            try {
                byte[] pdf = content.readAllBytes();
                if (pdf.length != size || size <= 0 || size > MAXIMUM_PDF_BYTES) {
                    throw new IllegalArgumentException("stored PDF size does not match the bounded import");
                }
                int pages;
                try (var loaded = Loader.loadPDF(pdf)) {
                    pages = loaded.getNumberOfPages();
                }
                if (pages != expected.expectedPages()) {
                    throw new IllegalArgumentException(
                            "constructed PDF page count changed: expected " + expected.expectedPages() + " but was " + pages);
                }
                String sha256 = digest(pdf);
                UploadEvidence evidence = new UploadEvidence(objectKey, pages, size, sha256);
                evidenceByGame.put(expected.gameName(), evidence);
                gameByObjectKey.put(objectKey, expected.gameName());
                return new StoredDocument(objectKey, size, contentType, sha256);
            } catch (IOException exception) {
                throw new IllegalStateException("could not validate the constructed PDF", exception);
            }
        }

        @Override
        public InputStream open(String objectKey) {
            throw new UnsupportedOperationException("real evaluation intentionally does not retain PDF bytes");
        }

        @Override
        public void delete(String objectKey) {
            String gameName = gameByObjectKey.remove(objectKey);
            if (gameName != null) evidenceByGame.remove(gameName);
        }
    }

    private static final class RecordingProcessingQueue implements DocumentProcessingQueue {
        private final Set<UUID> versionIds = new LinkedHashSet<>();

        @Override
        public void enqueue(UUID documentVersionId, Instant occurredAt) {
            versionIds.add(documentVersionId);
        }

        Set<UUID> versionIds() {
            return Set.copyOf(versionIds);
        }
    }

    private static final class InMemoryRuleDocumentRepository implements RuleDocumentRepository {
        private final Map<UUID, RuleDocument> documents = new LinkedHashMap<>();
        private final Map<UUID, DocumentVersion> versions = new LinkedHashMap<>();

        @Override
        public Optional<RuleDocument> findDocument(
                UUID editionId, String createdBy, String title, DocumentSourceType sourceType) {
            return documents.values().stream()
                    .filter(document -> java.util.Objects.equals(document.gameEditionId(), editionId))
                    .filter(document -> document.createdBy().equals(createdBy))
                    .filter(document -> document.title().equals(title) && document.sourceType() == sourceType)
                    .findFirst();
        }

        @Override
        public Optional<RuleDocument> findUnassignedDocument(
                String createdBy, String title, DocumentSourceType sourceType) {
            return findDocument(null, createdBy, title, sourceType);
        }

        @Override
        public Optional<RuleDocument> findDocument(UUID documentId) {
            return Optional.ofNullable(documents.get(documentId));
        }

        @Override
        public RuleDocument save(RuleDocument document) {
            documents.put(document.id(), document);
            return document;
        }

        @Override
        public void update(RuleDocument document) {
            documents.put(document.id(), document);
        }

        @Override
        public Optional<DocumentVersion> findVersionByChecksum(UUID documentId, String checksum) {
            return versions.values().stream()
                    .filter(version -> version.documentId().equals(documentId) && version.checksum().equals(checksum))
                    .findFirst();
        }

        @Override
        public int nextVersionNumber(UUID documentId) {
            return Math.toIntExact(
                    versions.values().stream().filter(version -> version.documentId().equals(documentId)).count() + 1);
        }

        @Override
        public DocumentVersion save(DocumentVersion version) {
            versions.put(version.id(), version);
            return version;
        }

        @Override
        public Optional<DocumentVersion> findVersion(UUID versionId) {
            return Optional.ofNullable(versions.get(versionId));
        }

        @Override
        public List<DocumentVersion> findVersions(UUID documentId) {
            return versions.values().stream()
                    .filter(version -> version.documentId().equals(documentId))
                    .toList();
        }

        @Override
        public long ruleDataVersion(UUID versionId) {
            return 0;
        }

        @Override
        public long incrementRuleDataVersion(UUID versionId) {
            return 1;
        }

        @Override
        public void update(DocumentVersion version) {
            versions.put(version.id(), version);
        }

        @Override
        public void replacePages(UUID versionId, List<DocumentProcessing.ExtractedPage> pages) {
            throw new UnsupportedOperationException("processing workers are outside this acquisition evaluation");
        }

        @Override
        public List<DocumentProcessing.PageView> findPages(UUID versionId) {
            return List.of();
        }

        @Override
        public void updatePageImage(UUID versionId, int pageNumber, String objectKey, int width, int height) {
            throw new UnsupportedOperationException("processing workers are outside this acquisition evaluation");
        }

        @Override
        public List<PageImageMetadata> findPageImages(UUID versionId, Set<Integer> pageNumbers) {
            return List.of();
        }

        @Override
        public List<PageImageMetadata> findAllPageImages(UUID versionId) {
            return List.of();
        }

        @Override
        public void deleteDocument(UUID documentId) {
            documents.remove(documentId);
        }

        @Override
        public List<DocumentSummary> findByEdition(UUID editionId, String createdBy) {
            return List.of();
        }

        @Override
        public List<DocumentSummary> findByOwner(String createdBy) {
            return List.of();
        }

        @Override
        public Map<UUID, Reference> findReferences(Collection<UUID> documentVersionIds) {
            return Map.of();
        }
    }

    private static final class InMemoryImportJobs implements OfficialRulebookImportJobRepository {
        private final Map<UUID, OfficialRulebookImportJob> jobs = new LinkedHashMap<>();
        private final Map<UUID, List<OfficialRulebookImportJob.Stage>> stages = new LinkedHashMap<>();

        List<OfficialRulebookImportJob.Stage> stages(UUID jobId) {
            return List.copyOf(stages.getOrDefault(jobId, List.of()));
        }

        @Override
        public void insert(OfficialRulebookImportJob job) {
            jobs.put(job.id(), job);
            stages.put(job.id(), new ArrayList<>());
        }

        @Override
        public Optional<OfficialRulebookImportJob> findOwned(UUID jobId, String ownerUsername) {
            return Optional.ofNullable(jobs.get(jobId)).filter(job -> job.ownerUsername().equals(ownerUsername));
        }

        @Override
        public Optional<OfficialRulebookImportJob> findActiveOwnedBySource(String ownerUsername, String sourceUrl) {
            return jobs.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .filter(job -> job.sourceUrl().equals(sourceUrl) && !job.stage().terminal())
                    .findFirst();
        }

        @Override
        public Optional<OfficialRulebookImportJob> findCompletedOwnedBySourceAndEdition(
                String ownerUsername, String sourceUrl, UUID editionId) {
            return jobs.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .filter(job -> job.sourceUrl().equals(sourceUrl))
                    .filter(job -> java.util.Objects.equals(job.editionId(), editionId))
                    .filter(job -> job.stage() == OfficialRulebookImportJob.Stage.COMPLETED)
                    .findFirst();
        }

        @Override
        public List<OfficialRulebookImportJob> findRecentOwned(String ownerUsername, int limit) {
            return jobs.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void requestTeaching(UUID jobId, String learningGoal, Instant now) {
            var job = jobs.get(jobId);
            jobs.put(jobId, copy(
                    job,
                    job.stage(),
                    job.downloadedBytes(),
                    job.totalBytes(),
                    job.documentVersionId(),
                    job.duplicate(),
                    job.errorCode(),
                    TeachingHandoff.requested(learningGoal, now),
                    now,
                    job.completedAt()));
        }

        @Override
        public List<OfficialRulebookImportJob> claimReadyTeaching(int limit, Instant now) {
            List<OfficialRulebookImportJob> ready = jobs.values().stream()
                    .filter(job -> job.stage() == OfficialRulebookImportJob.Stage.COMPLETED)
                    .filter(job -> job.teachingHandoff().state() == TeachingHandoffState.WAITING_FOR_DOCUMENT)
                    .limit(limit)
                    .toList();
            ready.forEach(job -> jobs.put(job.id(), copy(
                    job,
                    job.stage(),
                    job.downloadedBytes(),
                    job.totalBytes(),
                    job.documentVersionId(),
                    job.duplicate(),
                    job.errorCode(),
                    new TeachingHandoff(
                            TeachingHandoffState.LAUNCHING,
                            job.teachingHandoff().learningGoal(),
                            null,
                            null,
                            now),
                    now,
                    job.completedAt())));
            return ready.stream().map(job -> jobs.get(job.id())).toList();
        }

        @Override
        public int failTeachingForUnusableDocuments(Instant now) {
            return 0;
        }

        @Override
        public void completeTeachingLaunch(UUID jobId, UUID preparationRunId, Instant now) {
            var job = jobs.get(jobId);
            jobs.put(jobId, copy(
                    job,
                    job.stage(),
                    job.downloadedBytes(),
                    job.totalBytes(),
                    job.documentVersionId(),
                    job.duplicate(),
                    job.errorCode(),
                    new TeachingHandoff(
                            TeachingHandoffState.LAUNCHED,
                            job.teachingHandoff().learningGoal(),
                            preparationRunId,
                            null,
                            now),
                    now,
                    job.completedAt()));
        }

        @Override
        public void failTeachingLaunch(UUID jobId, String errorCode, Instant now) {
            var job = jobs.get(jobId);
            jobs.put(jobId, copy(
                    job,
                    job.stage(),
                    job.downloadedBytes(),
                    job.totalBytes(),
                    job.documentVersionId(),
                    job.duplicate(),
                    job.errorCode(),
                    new TeachingHandoff(
                            TeachingHandoffState.FAILED,
                            job.teachingHandoff().learningGoal(),
                            null,
                            errorCode,
                            now),
                    now,
                    job.completedAt()));
        }

        @Override
        public int failInterruptedTeachingLaunches(Instant now) {
            return 0;
        }

        @Override
        public void updateProgress(
                UUID jobId,
                OfficialRulebookImportJob.Stage stage,
                long downloadedBytes,
                Long totalBytes,
                Instant now) {
            var job = jobs.get(jobId);
            jobs.put(jobId, copy(
                    job,
                    stage,
                    downloadedBytes,
                    totalBytes,
                    null,
                    false,
                    null,
                    job.teachingHandoff(),
                    now,
                    null));
            stages.get(jobId).add(stage);
        }

        @Override
        public void complete(UUID jobId, UUID documentVersionId, boolean duplicate, Instant now) {
            var job = jobs.get(jobId);
            jobs.put(
                    jobId,
                    copy(
                            job,
                            OfficialRulebookImportJob.Stage.COMPLETED,
                            job.downloadedBytes(),
                            job.totalBytes(),
                            documentVersionId,
                            duplicate,
                            null,
                            job.teachingHandoff(),
                            now,
                            now));
            stages.get(jobId).add(OfficialRulebookImportJob.Stage.COMPLETED);
        }

        @Override
        public void fail(UUID jobId, String errorCode, Instant now) {
            var job = jobs.get(jobId);
            jobs.put(
                    jobId,
                    copy(
                            job,
                            OfficialRulebookImportJob.Stage.FAILED,
                            job.downloadedBytes(),
                            job.totalBytes(),
                            null,
                            false,
                            errorCode,
                            job.teachingHandoff(),
                            now,
                            now));
            stages.get(jobId).add(OfficialRulebookImportJob.Stage.FAILED);
        }

        @Override
        public int failInterrupted(Instant now) {
            return 0;
        }

        private OfficialRulebookImportJob copy(
                OfficialRulebookImportJob job,
                OfficialRulebookImportJob.Stage stage,
                long downloadedBytes,
                Long totalBytes,
                UUID documentVersionId,
                boolean duplicate,
                String errorCode,
                TeachingHandoff teachingHandoff,
                Instant updatedAt,
                Instant completedAt) {
            return new OfficialRulebookImportJob(
                    job.id(),
                    job.ownerUsername(),
                    job.editionId(),
                    job.title(),
                    job.sourceType(),
                    job.sourceUrl(),
                    stage,
                    downloadedBytes,
                    totalBytes,
                    documentVersionId,
                    duplicate,
                    errorCode,
                    teachingHandoff,
                    job.createdAt(),
                    updatedAt,
                    completedAt);
        }
    }
}
