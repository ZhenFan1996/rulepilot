package com.rulepilot.visualaid.adapter.out.docling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import com.rulepilot.visualaid.application.VisualLayoutExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class DoclingVisualLayoutExtractorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void normalizesProviderGeometryWithoutAskingTheLanguageModelForCoordinates() throws Exception {
        var extraction = DoclingVisualLayoutExtractor.mapDocument(json.readTree("""
                {
                  "pages": {
                    "1": {"page_no": 1, "size": {"width": 100, "height": 200}},
                    "2": {"page_no": 2, "size": {"width": 200, "height": 100}}
                  },
                  "pictures": [
                    {"prov": [{"page_no": 1, "bbox": {
                      "l": 10, "r": 50, "b": 20, "t": 120, "coord_origin": "BOTTOMLEFT"
                    }}]},
                    {"prov": [{"page_no": 1, "bbox": {
                      "l": 10, "r": 11, "b": 20, "t": 21, "coord_origin": "BOTTOMLEFT"
                    }}]},
                    {"prov": [{"page_no": 2, "bbox": {
                      "l": 0, "r": 200, "t": 0, "b": 100, "coord_origin": "TOPLEFT"
                    }}]}
                  ],
                  "tables": [
                    {"prov": [{"page_no": 2, "bbox": {
                      "l": 20, "r": 80, "t": 30, "b": 70, "coord_origin": "TOPLEFT"
                    }}]},
                    {"prov": [{"page_no": 2, "bbox": {
                      "l": 20, "r": 80, "t": 30, "b": 70, "coord_origin": "CENTER"
                    }}]}
                  ]
                }
                """));

        assertThat(extraction.source()).isEqualTo("docling:ibm-managed");
        assertThat(extraction.pageCount()).isEqualTo(2);
        assertThat(extraction.regions()).containsExactly(
                new Region(1, "PICTURE", 100, 400, 400, 500),
                new Region(2, "TABLE", 100, 300, 300, 400));
    }

    @Test
    void rejectsDocumentsWithoutTrustedPageDimensions() throws Exception {
        assertThatThrownBy(() -> DoclingVisualLayoutExtractor.mapDocument(json.readTree("""
                {"pages": {}, "pictures": [], "tables": []}
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page dimensions");
    }

    @Test
    void disabledConfigurationCanRemainCredentialFreeButEnabledConfigurationCannot() {
        var disabled = properties("", "");

        assertThat(disabled.serviceUrl()).isEmpty();
        assertThatThrownBy(() -> new DoclingVisualLayoutConfiguration().doclingVisualLayoutExtractor(disabled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a service URL and API key");
        assertThatThrownBy(() -> properties("http://layout.example", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RULEPILOT_ALLOW_PAID_CANARY", matches = "true")
    void realManagedServiceReturnsBoundedGeometryForAPdf() throws Exception {
        String pdfPath = System.getenv("DOCLING_CANARY_PDF");
        var properties = new DoclingVisualLayoutProperties(
                System.getenv("DOCLING_SERVICE_URL"),
                System.getenv("DOCLING_API_KEY"),
                Duration.ofMinutes(5),
                Duration.ofSeconds(1),
                100 * 1_024 * 1_024L,
                32 * 1_024 * 1_024);
        var extractor = new DoclingVisualLayoutConfiguration().doclingVisualLayoutExtractor(properties);

        VisualLayoutExtractor.Extraction extraction;
        try (var input = Files.newInputStream(Path.of(pdfPath))) {
            extraction = extractor.extract(input);
        }

        assertThat(extraction.pageCount()).isPositive();
        assertThat(extraction.regions()).allSatisfy(region -> {
            assertThat(region.pageNumber()).isBetween(1, extraction.pageCount());
            assertThat(region.x() + region.width()).isLessThanOrEqualTo(1_000);
            assertThat(region.y() + region.height()).isLessThanOrEqualTo(1_000);
        });
    }

    private DoclingVisualLayoutProperties properties(String serviceUrl, String apiKey) {
        return new DoclingVisualLayoutProperties(
                serviceUrl,
                apiKey,
                Duration.ofMinutes(5),
                Duration.ofSeconds(1),
                1_024,
                1_024);
    }
}
