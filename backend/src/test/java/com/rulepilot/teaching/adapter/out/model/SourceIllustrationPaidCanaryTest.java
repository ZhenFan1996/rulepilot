package com.rulepilot.teaching.adapter.out.model;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.teaching.VisualRegionLocator;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Opt-in source-page observation. Output artifacts require human visual review; schema validity is not image quality. */
@Tag("real-source-illustration")
class SourceIllustrationPaidCanaryTest {
    @Test void observesRealSourcePagesForManualCropReview() throws Exception {
        assumeTrue("true".equals(System.getenv("RULEPILOT_REAL_SOURCE_ILLUSTRATION")), "Opt in with make agent-visual-real");
        var json = JsonMapper.builder().build();
        var manifest = json.readTree(Files.readString(Path.of(System.getenv("RULEPILOT_VISUAL_PAGE_MANIFEST"))));
        var config = mock(RuntimeModelConfiguration.class);
        var chat = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofMinutes(2)).create(
                "qwen", System.getenv("QWEN_API_KEY"), System.getenv("QWEN_BASE_URL"), System.getenv("QWEN_MODEL"));
        when(config.modelFor(Role.VISUAL, "canary")).thenReturn(chat);
        when(config.modelNameFor(Role.VISUAL, "canary")).thenReturn(System.getenv("QWEN_MODEL"));
        when(config.providerFor(Role.VISUAL, "canary")).thenReturn("qwen");
        when(config.supportsVision(Role.VISUAL, "canary")).thenReturn(true);
        var model = new SpringAiVisualRegionLocator(config, new StaticListableBeanFactory()
                .getBeanProvider(com.rulepilot.assistant.AuditedAgentInvocations.class));
        for (var entry : manifest) {
            var path = Path.of(entry.path("file").asText());
            var image = ImageIO.read(path.toFile());
            var available = new java.util.LinkedHashMap<String, com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus>();
            int pageNumber = entry.path("pageNumber").asInt();
            if (entry.hasNonNull("pdf")) {
                try (var input = Files.newInputStream(Path.of(entry.path("pdf").asText()))) {
                    var extraction = new com.rulepilot.visualaid.adapter.out.pdf.PdfImageLayoutExtractor().extract(input);
                    for (var region : extraction.regions()) {
                        if (region.pageNumber() != pageNumber) continue;
                        var focus = new com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus(pageNumber, "Source image",
                                region.x(), region.y(), region.width(), region.height());
                        available.put(focus.imageId(new java.util.UUID(0, 0)), focus);
                    }
                }
            }
            long start = System.nanoTime();
            var result = model.observe(new VisualRegionLocator.Request(entry.path("objective").asText(),
                    List.of(new PageImage(entry.path("pageNumber").asInt(), "image/jpeg", Files.readAllBytes(path), image.getWidth(), image.getHeight())), available, "canary", null, com.rulepilot.shared.PlayerLocale.EN));
            Files.writeString(Path.of(entry.path("output").asText()), json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(java.util.Map.of("elapsedMs", (System.nanoTime() - start) / 1_000_000, "images", result)));
        }
    }
}
