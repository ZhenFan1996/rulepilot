package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.AcquisitionMode;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.CapabilityEvidence;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.Candidate;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.SourceAction;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.SourceCapability;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.SourceType;
import com.rulepilot.document.application.OfficialRulebookSourceInspector.PageSignal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class OfficialRulebookDiscoveryServiceTest {

    private static final UUID EDITION_ID = UUID.fromString("26ba0d70-51e3-445b-9245-d50754562a13");

    @Test
    void rejectsAnImportActionWhenCapabilityAndAcquisitionModeDisagree() {
        assertThatThrownBy(() -> new Candidate(
                        "Opaque source",
                        "https://publisher.example/asset/42",
                        "Opaque Studio",
                        "en",
                        "First",
                        "publisher.example",
                        true,
                        true,
                        SourceType.PUBLISHER,
                        AcquisitionMode.SOURCE_PAGE,
                        SourceCapability.DIRECT_DOCUMENT,
                        List.of(CapabilityEvidence.DOCUMENT_RESPONSE_CONFIRMED),
                        Instant.parse("2026-08-15T12:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capability and acquisition mode");
    }

    @Test
    void validatesClassifiesAndRanksCandidatesWithoutTrustingModelUrls() {
        var finder = new FakeFinder(List.of(
                new OfficialRulebookCandidateFinder.Candidate(
                        "Rules", "https://cdn.example.net/rules.pdf", "Unknown Publisher", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Official Rules", "https://stonemaiergames.com/files/rules.pdf", "Stonemaier Games", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Trusted source page", "https://trusted.example/wingspan", "", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Private", "http://127.0.0.1/rules.pdf", "Stonemaier Games", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Publisher page", "https://stonemaiergames.com/rules", "Stonemaier Games", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Observed BGG rulebook download",
                        "https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/wingspan-rules.pdf",
                        "Community uploader",
                        "en",
                        "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Guessed BGG download",
                        "https://boardgamegeek.com/file/download_redirect/88465/wingspan-rules.pdf",
                        "",
                        "en",
                        "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Untrusted page", "https://random.example/rules", "", "en", "First")));
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), emptyInspector(), "trusted.example");

        var result = service.discover(EDITION_ID, "en");

        assertThat(result.configured()).isTrue();
        assertThat(result.candidates()).hasSize(7);
        assertThat(result.candidates()).extracting(Candidate::url)
                .doesNotContain(
                        "http://127.0.0.1/rules.pdf",
                        "https://boardgamegeek.com/file/download_redirect/88465/wingspan-rules.pdf");
        assertThat(result.candidates())
                .filteredOn(candidate -> candidate.url().equals("https://stonemaiergames.com/files/rules.pdf"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.officialDomainVerified()).isTrue();
                    assertThat(candidate.sourceType()).isEqualTo(SourceType.PUBLISHER);
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.SOURCE_PAGE);
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.UNVERIFIED_PAGE);
                    assertThat(candidate.nextAction()).isEqualTo(SourceAction.REVIEW_OR_UPLOAD);
                });
        assertThat(result.candidates())
                .filteredOn(candidate -> candidate.title().equals("Observed BGG rulebook download"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.SOURCE_PAGE);
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.UNVERIFIED_PAGE);
                });
        assertThat(result.candidates())
                .noneMatch(candidate -> candidate.capability() == SourceCapability.DIRECT_DOCUMENT);
        assertThat(result.candidates()).extracting(Candidate::url)
                .contains("https://boardgamegeek.com/files/thing/266192");
    }

    @Test
    void degradesWithoutCallingAnUnconfiguredFinder() {
        FakeFinder finder = new FakeFinder(List.of());
        finder.configured = false;
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), emptyInspector(), "");

        var result = service.discover(EDITION_ID, "zh-CN");

        assertThat(result.configured()).isFalse();
        assertThat(result.candidates()).isEmpty();
        assertThat(finder.calls).isZero();
    }

    @Test
    void normalizesAnUnverifiedHumanLanguageLabelWithoutPromotingItToSourceEvidence() {
        var finder = new FakeFinder(List.of(new OfficialRulebookCandidateFinder.Candidate(
                "Publisher rulebook",
                "https://stonemaiergames.com/files/wingspan-rules.pdf",
                "Stonemaier Games",
                "English",
                "First")));
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), emptyInspector(), "");

        Candidate candidate = service.discover(EDITION_ID, "en").candidates().getFirst();

        assertThat(candidate.language()).isEqualTo("en");
        assertThat(candidate.languageVerified()).isFalse();
    }

    @Test
    void followsABoundedDownloadControlAndPromotesASuffixlessPdfOnlyAfterInspection() {
        var finder = new FakeFinder(List.of(new OfficialRulebookCandidateFinder.Candidate(
                "Publisher rules",
                "https://stonemaiergames.com/catalog-game/rules",
                "Stonemaier Games",
                "en",
                "First")));
        OfficialRulebookSourceInspector inspector = source -> {
            if (source.getPath().equals("/catalog-game/rules")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(new OfficialRulebookSourceInspector.Link(
                                URI.create("https://stonemaiergames.com/api/download?id=42"), "Download"))));
            }
            if (source.getPath().equals("/api/download")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source, OfficialRulebookSourceInspector.MediaType.PDF, List.of()));
            }
            return Optional.empty();
        };
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), inspector, "");

        var result = service.discover(EDITION_ID, "en");

        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.url()).isEqualTo("https://stonemaiergames.com/api/download?id=42");
                    assertThat(candidate.sourceType()).isEqualTo(SourceType.PUBLISHER);
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.DIRECT_PDF);
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.DIRECT_DOCUMENT);
                    assertThat(candidate.nextAction()).isEqualTo(SourceAction.IMPORT_DOCUMENT);
                });
        assertThat(finder.refinementCalls).isEqualTo(1);
    }

    @Test
    void derivesSourceCapabilityFromObservedContentAndLetsNegativeSignalsOverrideNamesAndSuffixes() {
        var finder = new FakeFinder(
                List.of(
                        new OfficialRulebookCandidateFinder.Candidate(
                                "Opaque document A",
                                "https://publisher.example/confirmed.pdf",
                                "Opaque Studio",
                                "en",
                                "First"),
                        new OfficialRulebookCandidateFinder.Candidate(
                                "Opaque file collection",
                                "https://publisher.example/files",
                                "Opaque Studio",
                                "en",
                                "First"),
                        new OfficialRulebookCandidateFinder.Candidate(
                                "Rules PDF download",
                                "https://publisher.example/not-a-document.pdf",
                                "Opaque Studio",
                                "en",
                                "First"),
                        new OfficialRulebookCandidateFinder.Candidate(
                                "Opaque protected page",
                                "https://publisher.example/sign-in",
                                "Opaque Studio",
                                "en",
                                "First")),
                "Opaque Atlas",
                "Opaque Studio");
        OfficialRulebookSourceInspector inspector = source -> switch (source.getPath()) {
            case "/confirmed.pdf" -> Optional.of(new OfficialRulebookSourceInspector.Inspection(
                    source, OfficialRulebookSourceInspector.MediaType.PDF, List.of()));
            case "/files" -> Optional.of(new OfficialRulebookSourceInspector.Inspection(
                    source,
                    OfficialRulebookSourceInspector.MediaType.HTML,
                    List.of(new OfficialRulebookSourceInspector.Link(
                            URI.create("https://publisher.example/download/opaque-manual.pdf"),
                            "Download")),
                    java.util.Set.of(PageSignal.DOWNLOADABLE_DOCUMENT_LINKS)));
            case "/not-a-document.pdf" -> Optional.of(new OfficialRulebookSourceInspector.Inspection(
                    source,
                    OfficialRulebookSourceInspector.MediaType.HTML,
                    List.of(),
                    java.util.Set.of(
                            PageSignal.EXPLICIT_EMPTY_DOCUMENT_COLLECTION,
                            PageSignal.STRUCTURED_GAME_INFORMATION)));
            case "/sign-in" -> Optional.of(new OfficialRulebookSourceInspector.Inspection(
                    source,
                    OfficialRulebookSourceInspector.MediaType.HTML,
                    List.of(),
                    java.util.Set.of(PageSignal.LOGIN_REQUIRED)));
            default -> Optional.empty();
        };
        var service = new OfficialRulebookDiscoveryService(
                opaqueCatalog(), opaqueSourceIdentity(), finder, request -> List.of(), inspector, "");

        var candidates = service.discover(EDITION_ID, "en").candidates();

        assertThat(candidates)
                .filteredOn(candidate -> candidate.url().endsWith("/confirmed.pdf"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.DIRECT_DOCUMENT);
                    assertThat(candidate.capabilityEvidence())
                            .containsExactly(CapabilityEvidence.DOCUMENT_RESPONSE_CONFIRMED);
                    assertThat(candidate.capabilityCheckedAt()).isNotNull();
                    assertThat(candidate.nextAction()).isEqualTo(SourceAction.IMPORT_DOCUMENT);
                });
        assertThat(candidates)
                .filteredOn(candidate -> candidate.url().endsWith("/files"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.DOCUMENT_LISTING);
                    assertThat(candidate.capabilityEvidence())
                            .contains(CapabilityEvidence.DOWNLOADABLE_DOCUMENT_LINKS_OBSERVED);
                    assertThat(candidate.nextAction()).isEqualTo(SourceAction.CONTINUE_ON_SOURCE);
                });
        assertThat(candidates)
                .filteredOn(candidate -> candidate.url().endsWith("/not-a-document.pdf"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.GAME_INFO_ONLY);
                    assertThat(candidate.capabilityEvidence())
                            .contains(CapabilityEvidence.EXPLICIT_EMPTY_DOCUMENT_COLLECTION);
                    assertThat(candidate.nextAction()).isEqualTo(SourceAction.USE_FOR_IDENTITY_ONLY);
                });
        assertThat(candidates)
                .filteredOn(candidate -> candidate.url().endsWith("/sign-in"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.UNVERIFIED_PAGE);
                    assertThat(candidate.capabilityEvidence()).contains(CapabilityEvidence.ACCESS_REQUIRES_LOGIN);
                    assertThat(candidate.nextAction()).isEqualTo(SourceAction.REVIEW_OR_UPLOAD);
                });
        assertThat(candidates)
                .filteredOn(candidate -> candidate.url().equals("https://boardgamegeek.com/files/thing/424242"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.DOCUMENT_LISTING);
                    assertThat(candidate.capabilityEvidence())
                            .contains(CapabilityEvidence.KNOWN_DOCUMENT_LISTING_ROUTE);
                });
        assertThat(candidates)
                .filteredOn(candidate -> candidate.capability() == SourceCapability.DIRECT_DOCUMENT)
                .allSatisfy(candidate -> assertThat(candidate.url()).endsWith("/confirmed.pdf"));
    }

    @Test
    void followsAGstoneRulebookLinkAndPromotesItsExplicitPageImageViewerForImport() {
        var finder = new FakeFinder(List.of());
        GstoneRulebookCatalogLookup gstoneCatalog = request -> {
            assertThat(request.gameName()).isEqualTo("Wingspan");
            return List.of(new OfficialRulebookCandidateFinder.Candidate(
                    "目录游戏",
                    "https://www.gstonegames.com/game/info-1234.html",
                    "集石",
                    "zh-CN",
                    "基础版"));
        };
        OfficialRulebookSourceInspector inspector = source -> {
            if (source.getPath().equals("/game/info-1234.html")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(
                                new OfficialRulebookSourceInspector.Link(
                                        URI.create("https://www.gstonegames.com/game/doc-1111.html"),
                                        "English Rulebook"),
                                new OfficialRulebookSourceInspector.Link(
                                        URI.create("https://www.gstonegames.com/game/doc-4321.html"),
                                        "简体中文规则书"),
                                new OfficialRulebookSourceInspector.Link(
                                        URI.create("https://www.gstonegames.com/game/doc-2222.html"),
                                        "目录游戏图标概览 / 术语表"))));
            }
            if (source.getPath().equals("/game/doc-1111.html")
                    || source.getPath().equals("/game/doc-4321.html")
                    || source.getPath().equals("/game/doc-2222.html")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source, OfficialRulebookSourceInspector.MediaType.IMAGE_GALLERY, List.of()));
            }
            return Optional.empty();
        };
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, gstoneCatalog, inspector, "");

        var result = service.discover(EDITION_ID, "zh-CN");

        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.title()).isEqualTo("简体中文规则书");
                    assertThat(candidate.url()).isEqualTo("https://www.gstonegames.com/game/doc-4321.html");
                    assertThat(candidate.language()).isEqualTo("zh-CN");
                    assertThat(candidate.languageVerified()).isTrue();
                    assertThat(candidate.sourceType()).isEqualTo(SourceType.COMMUNITY_PLATFORM);
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.IMAGE_GALLERY);
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.CONTIGUOUS_RULE_PAGES);
                    assertThat(candidate.nextAction()).isEqualTo(SourceAction.IMPORT_PAGE_SEQUENCE);
                });
        assertThat(result.candidates())
                .filteredOn(candidate -> candidate.url().endsWith("/game/doc-1111.html"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.language()).isEqualTo("en");
                    assertThat(candidate.languageVerified()).isTrue();
                });
        assertThat(result.candidates())
                .noneMatch(candidate -> candidate.url().endsWith("/game/doc-2222.html"));
        assertThat(finder.refinementCalls).isZero();
        assertThat(finder.calls).isZero();
    }

    @Test
    void doesNotTreatTheRequestedLanguageOrAGenericRulebookLabelAsSourceEvidence() {
        GstoneRulebookCatalogLookup gstoneCatalog = request -> List.of(
                new OfficialRulebookCandidateFinder.Candidate(
                        "目录游戏", "https://www.gstonegames.com/game/info-1234.html", "集石", "", "基础版"));
        OfficialRulebookSourceInspector inspector = source -> {
            if (source.getPath().equals("/game/info-1234.html")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(new OfficialRulebookSourceInspector.Link(
                                URI.create("https://www.gstonegames.com/game/doc-1111.html"),
                                "Official Rulebook"))));
            }
            if (source.getPath().equals("/game/doc-1111.html")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source, OfficialRulebookSourceInspector.MediaType.IMAGE_GALLERY, List.of()));
            }
            return Optional.empty();
        };
        FakeFinder finder = new FakeFinder(List.of());
        finder.configured = false;
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, gstoneCatalog, inspector, "");

        var candidate = service.discover(EDITION_ID, "zh-CN").candidates().stream()
                .filter(value -> value.url().endsWith("/game/doc-1111.html"))
                .findFirst()
                .orElseThrow();

        assertThat(candidate.language()).isBlank();
        assertThat(candidate.languageVerified()).isFalse();
    }

    @Test
    void fallsBackToModelSearchWhenGstoneOnlyHasADifferentLanguage() {
        var finder = new FakeFinder(List.of(new OfficialRulebookCandidateFinder.Candidate(
                "Chinese publisher rulebook",
                "https://stonemaiergames.com/files/wingspan-zh-rules.pdf",
                "Stonemaier Games",
                "zh-CN",
                "First")));
        GstoneRulebookCatalogLookup gstoneCatalog = request -> List.of(
                new OfficialRulebookCandidateFinder.Candidate(
                        "目录游戏",
                        "https://www.gstonegames.com/game/info-1234.html",
                        "集石",
                        "zh-CN",
                        "基础版"));
        OfficialRulebookSourceInspector inspector = source -> {
            if (source.getPath().equals("/game/info-1234.html")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(new OfficialRulebookSourceInspector.Link(
                                URI.create("https://www.gstonegames.com/game/doc-1111.html"),
                                "Official Rulebook"))));
            }
            if (source.getPath().equals("/game/doc-1111.html")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source, OfficialRulebookSourceInspector.MediaType.IMAGE_GALLERY, List.of()));
            }
            if (source.getPath().equals("/files/wingspan-zh-rules.pdf")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source, OfficialRulebookSourceInspector.MediaType.PDF, List.of()));
            }
            return Optional.empty();
        };
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, gstoneCatalog, inspector, "");

        var result = service.discover(EDITION_ID, "zh-CN");

        assertThat(finder.calls).isEqualTo(1);
        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.url())
                            .isEqualTo("https://stonemaiergames.com/files/wingspan-zh-rules.pdf");
                    assertThat(candidate.language()).isEqualTo("zh-CN");
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.DIRECT_PDF);
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.DIRECT_DOCUMENT);
                    assertThat(candidate.capabilityEvidence())
                            .containsExactly(CapabilityEvidence.DOCUMENT_RESPONSE_CONFIRMED);
                });
    }

    @Test
    void followsAOneJourRulebookPageAndKeepsCommunityProvenanceForItsObservedPdf() {
        var finder = new FakeFinder(List.of(new OfficialRulebookCandidateFinder.Candidate(
                "Wingspan (2019)",
                "https://en.1jour-1jeu.com/table-game/2019-wingspan",
                "1jour-1jeu",
                "en",
                "First")));
        OfficialRulebookSourceInspector inspector = source -> {
            if (source.getHost().equals("en.1jour-1jeu.com")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(new OfficialRulebookSourceInspector.Link(
                                URI.create("https://cdn.1j1ju.com/medias/12/34/wingspan-rulebook.pdf"),
                                "English"))));
            }
            if (source.getHost().equals("cdn.1j1ju.com")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source, OfficialRulebookSourceInspector.MediaType.PDF, List.of()));
            }
            return Optional.empty();
        };
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), inspector, "");

        var result = service.discover(EDITION_ID, "en");

        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.url())
                            .isEqualTo("https://cdn.1j1ju.com/medias/12/34/wingspan-rulebook.pdf");
                    assertThat(candidate.sourceDomain()).isEqualTo("cdn.1j1ju.com");
                    assertThat(candidate.sourceType()).isEqualTo(SourceType.COMMUNITY_PLATFORM);
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.DIRECT_PDF);
                });
    }

    @Test
    void sourceInspectionBudgetReturnsTheCandidateAsUnverifiedInsteadOfBlockingDiscovery() {
        var releaseInspection = new CountDownLatch(1);
        OfficialRulebookSourceInspector slowInspector = source -> {
            awaitIgnoringInterrupt(releaseInspection);
            return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                    source, OfficialRulebookSourceInspector.MediaType.PDF, List.of()));
        };
        GstoneRulebookCatalogLookup catalogLookup = request -> List.of(
                new OfficialRulebookCandidateFinder.Candidate(
                        "Opaque catalog entry",
                        "https://catalog.example/item/42",
                        "Opaque Studio",
                        "en",
                        "First"));
        FakeFinder finder = new FakeFinder(List.of());
        finder.configured = false;
        var service = new OfficialRulebookDiscoveryService(
                opaqueCatalog(),
                opaqueSourceIdentity(),
                finder,
                catalogLookup,
                slowInspector,
                "",
                Duration.ofMillis(100),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(250));

        OfficialRulebookDiscoveryService.Result result;
        try {
            result = service.discover(EDITION_ID, "en");
        } finally {
            releaseInspection.countDown();
        }

        assertThat(result.candidates())
                .filteredOn(candidate -> candidate.url().equals("https://catalog.example/item/42"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.capability()).isEqualTo(SourceCapability.UNVERIFIED_PAGE);
                    assertThat(candidate.capabilityEvidence())
                            .containsExactly(CapabilityEvidence.SOURCE_PROBE_UNAVAILABLE);
                });
        assertThat(result.discovery().completion())
                .isEqualTo(OfficialRulebookDiscoveryService.DiscoveryCompletion.PARTIAL);
        assertThat(result.discovery().providers())
                .filteredOn(provider -> provider.provider()
                        == OfficialRulebookDiscoveryService.DiscoveryProvider.SOURCE_INSPECTION)
                .singleElement()
                .satisfies(provider -> assertThat(provider.state())
                        .isEqualTo(OfficialRulebookDiscoveryService.DiscoveryProviderState.TIMED_OUT));
    }

    @Test
    void returnsCompletedCatalogEvidenceWhenWebSearchExceedsItsProviderBudget() {
        var releaseWebSearch = new CountDownLatch(1);
        OfficialRulebookCandidateFinder slowFinder = new OfficialRulebookCandidateFinder() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public List<OfficialRulebookCandidateFinder.Candidate> find(Request request) {
                awaitIgnoringInterrupt(releaseWebSearch);
                return List.of();
            }
        };
        GstoneRulebookCatalogLookup catalogLookup = request -> List.of(
                new OfficialRulebookCandidateFinder.Candidate(
                        "Opaque catalog entry",
                        "https://catalog.example/files",
                        "Opaque Studio",
                        "en",
                        "First",
                        OfficialRulebookCandidateFinder.SourcePageHint.DOCUMENT_LISTING));
        OfficialRulebookSourceInspector inspector = source -> Optional.of(
                new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(),
                        java.util.Set.of(PageSignal.DOWNLOADABLE_DOCUMENT_LINKS)));
        var service = new OfficialRulebookDiscoveryService(
                opaqueCatalog(),
                opaqueSourceIdentity(),
                slowFinder,
                catalogLookup,
                inspector,
                "",
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(50),
                Duration.ofMillis(250));

        OfficialRulebookDiscoveryService.Result result;
        long started = System.nanoTime();
        try {
            result = service.discover(EDITION_ID, "en");
        } finally {
            releaseWebSearch.countDown();
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(1_000);
        assertThat(result.candidates())
                .filteredOn(candidate -> candidate.url().equals("https://catalog.example/files"))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.capability())
                        .isEqualTo(SourceCapability.DOCUMENT_LISTING));
        assertThat(result.discovery().completion())
                .isEqualTo(OfficialRulebookDiscoveryService.DiscoveryCompletion.PARTIAL);
        assertThat(result.discovery().providers())
                .filteredOn(provider -> provider.provider()
                        == OfficialRulebookDiscoveryService.DiscoveryProvider.WEB_SEARCH)
                .singleElement()
                .satisfies(provider -> assertThat(provider.state())
                        .isEqualTo(OfficialRulebookDiscoveryService.DiscoveryProviderState.TIMED_OUT));
    }

    @Test
    void totalBudgetStopsASlowFirstProviderEvenWhenItsOwnBudgetIsLarger() {
        var releaseCatalog = new CountDownLatch(1);
        GstoneRulebookCatalogLookup slowCatalog = request -> {
            awaitIgnoringInterrupt(releaseCatalog);
            return List.of();
        };
        FakeFinder finder = new FakeFinder(List.of());
        finder.configured = false;
        var service = new OfficialRulebookDiscoveryService(
                opaqueCatalog(),
                opaqueSourceIdentity(),
                finder,
                slowCatalog,
                emptyInspector(),
                "",
                Duration.ofMillis(500),
                Duration.ofMillis(500),
                Duration.ofMillis(500),
                Duration.ofMillis(75));

        OfficialRulebookDiscoveryService.Result result;
        long started = System.nanoTime();
        try {
            result = service.discover(EDITION_ID, "en");
        } finally {
            releaseCatalog.countDown();
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(1_000);
        assertThat(result.discovery().completion())
                .isEqualTo(OfficialRulebookDiscoveryService.DiscoveryCompletion.TIMED_OUT);
        assertThat(result.discovery().elapsedMs()).isLessThan(1_000);
        assertThat(result.discovery().providers())
                .filteredOn(provider -> provider.provider()
                        == OfficialRulebookDiscoveryService.DiscoveryProvider.CATALOG)
                .singleElement()
                .satisfies(provider -> assertThat(provider.state())
                        .isEqualTo(OfficialRulebookDiscoveryService.DiscoveryProviderState.TIMED_OUT));
        assertThat(finder.calls).isZero();
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private CatalogGamePresentationLookup catalog() {
        return editionId -> Optional.of(new CatalogGamePresentationLookup.Presentation(
                EDITION_ID,
                "Wingspan",
                "BGG 基础版",
                "und",
                2019,
                266192,
                "https://example.test/cover.jpg",
                1,
                5,
                70,
                10,
                "https://boardgamegeek.com/boardgame/266192"));
    }

    private CatalogGamePresentationLookup opaqueCatalog() {
        return editionId -> Optional.of(new CatalogGamePresentationLookup.Presentation(
                EDITION_ID,
                "Opaque Atlas",
                "First",
                "en",
                2026,
                424242,
                "https://example.test/opaque.jpg",
                1,
                4,
                90,
                12,
                "https://boardgamegeek.com/boardgame/424242"));
    }

    private CatalogGameSourceIdentityLookup sourceIdentity() {
        return bggId -> Optional.of(new CatalogGameSourceIdentityLookup.Identity(
                "Wingspan", List.of("Wingspan", "展翅翱翔"), List.of("Stonemaier Games")));
    }

    private CatalogGameSourceIdentityLookup opaqueSourceIdentity() {
        return bggId -> Optional.of(new CatalogGameSourceIdentityLookup.Identity(
                "Opaque Atlas", List.of("Opaque Atlas"), List.of("Opaque Studio")));
    }

    private OfficialRulebookSourceInspector emptyInspector() {
        return source -> Optional.empty();
    }

    private static final class FakeFinder implements OfficialRulebookCandidateFinder {
        private final List<OfficialRulebookCandidateFinder.Candidate> candidates;
        private boolean configured = true;
        private int calls;
        private int refinementCalls;
        private final String expectedGame;
        private final String expectedPublisher;

        private FakeFinder(List<OfficialRulebookCandidateFinder.Candidate> candidates) {
            this(candidates, "Wingspan", "Stonemaier Games");
        }

        private FakeFinder(
                List<OfficialRulebookCandidateFinder.Candidate> candidates,
                String expectedGame,
                String expectedPublisher) {
            this.candidates = candidates;
            this.expectedGame = expectedGame;
            this.expectedPublisher = expectedPublisher;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public List<OfficialRulebookCandidateFinder.Candidate> find(Request request) {
            calls++;
            assertThat(request.gameName()).isEqualTo(expectedGame);
            assertThat(request.publishers()).containsExactly(expectedPublisher);
            return candidates;
        }

        @Override
        public List<OfficialRulebookCandidateFinder.Candidate> findAfterSourcePages(
                Request request, List<OfficialRulebookCandidateFinder.Candidate> observedSourcePages) {
            refinementCalls++;
            return List.of();
        }
    }
}
