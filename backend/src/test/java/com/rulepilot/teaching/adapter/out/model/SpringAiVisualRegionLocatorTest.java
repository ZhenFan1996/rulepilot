package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.*;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpringAiVisualRegionLocatorTest {
    private static final Map<String, VisualFocus> SOURCES = Map.of("portrait", new VisualFocus(3, "Source image", 120, 80, 600, 700));
    private static final String IMAGE = """
            {"images":[{"imageId":"portrait","label":"Board layout","visibleDescription":"Whole board and its legend"}]}
            """;

    @Test void selectionPreservesSourceGeometryAndAllowsNoIllustration() {
        assertThat(SpringAiVisualRegionLocator.parse(IMAGE, SOURCES)).singleElement().satisfies(image -> {
            assertThat(image.label()).isEqualTo("Board layout");
            assertThat(image.x()).isEqualTo(120);
            assertThat(image.width()).isEqualTo(600);
        });
        assertThat(SpringAiVisualRegionLocator.parse("{\"images\":[]}", SOURCES)).isEmpty();
    }

    @Test void rejectsUnownedImagesAndModelAuthoredGeometry() {
        assertThatThrownBy(() -> SpringAiVisualRegionLocator.parse(IMAGE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a supplied source image");
        assertThatThrownBy(() -> SpringAiVisualRegionLocator.parse(IMAGE.replace("\"imageId\"", "\"x\":0,\"imageId\""), SOURCES))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
