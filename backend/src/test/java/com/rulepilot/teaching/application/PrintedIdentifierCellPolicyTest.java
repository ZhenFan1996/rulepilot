package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierLocation;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PrintedIdentifierCellPolicyTest {

    @Test
    void discoversOnlyRepeatedShortIdentifiersFromDocumentDerivedText() {
        assertThat(PrintedIdentifierCellPolicy.identifiers(
                        "Reference A-01, A-02, B#03 and B#04. Page 17 and year 2026 are ordinary numbers."))
                .containsExactly("A-01", "A-02", "B#03", "B#04");

        assertThat(PrintedIdentifierCellPolicy.identifiers("Round 1, round 2 and page 3"))
                .isEmpty();
    }

    @Test
    void bindsOnlyRequestedLocationsAndBuildsOneBoundedCellPerVerifiedIdentifier() throws Exception {
        List<String> requested = List.of("A-01", "A-02", "B#03", "B#04");
        List<IdentifierLocation> proposed = List.of(
                new IdentifierLocation("A-01", 80, 280, 40, 12),
                new IdentifierLocation("A-02", 520, 280, 40, 12),
                new IdentifierLocation("B#03", 80, 620, 40, 12),
                new IdentifierLocation("B#04", 520, 620, 40, 12),
                new IdentifierLocation("C-99", 80, 900, 40, 12));

        var verified = PrintedIdentifierCellPolicy.verifiedLocations(requested, proposed);
        assertThat(verified).extracting(IdentifierLocation::identifier).containsExactlyElementsOf(requested);

        BufferedImage image = new BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        var cells = PrintedIdentifierCellPolicy.cells(
                new PageImage(8, "image/png", bytes.toByteArray(), 1_000, 1_000), verified);

        assertThat(cells).extracting(cell -> cell.identifier()).containsExactlyElementsOf(requested);
        assertThat(cells).allSatisfy(cell -> {
            assertThat(cell.image().pageNumber()).isEqualTo(8);
            assertThat(cell.image().content()).isNotEmpty();
        });

        var reference = PrintedIdentifierCellPolicy.referenceCrop(
                new PageImage(8, "image/png", bytes.toByteArray(), 1_000, 1_000),
                100, 500, 400, 240);
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(reference.content()));
        assertThat(decoded.getWidth()).isLessThan(1_000);
        assertThat(decoded.getHeight()).isLessThan(1_000);
        assertThat(Math.min(decoded.getWidth(), decoded.getHeight())).isGreaterThanOrEqualTo(512);
    }
}
