package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Deterministic guards for turning a visual-model rectangle into a player-facing rulebook crop.
 *
 * <p>The Spring adapter still owns provider calls and model-output parsing. This policy owns only rules that can be
 * decided from the request, the returned rectangle, and rendered pixels.</p>
 */
final class VisualCropAcceptancePolicy {

    private static final long MAX_READER_CROP_AREA = 600_000L;
    private static final int CROP_SAMPLE_EDGE = 160;
    private static final int CROP_FOREGROUND_DISTANCE = 40;
    private static final double MIN_CATALOGED_LEGEND_SIGNAL = 0.22;

    private VisualCropAcceptancePolicy() {}

    static Optional<LocatedRegion> catalogedAnchorRegion(VisualLocationRequest request, Candidate candidate) {
        if (request == null || candidate == null || candidate.catalogedAnchor() == null) return Optional.empty();
        var anchor = candidate.catalogedAnchor();
        List<VisualRegionLocator.Claim> supportedClaims = request.claims().stream()
                .filter(claim -> sourceIncludes(claim, candidate.pageNumber()))
                .toList();
        if (supportedClaims.isEmpty() || supportedClaims.stream().anyMatch(claim -> claim.stepPosition() < 1)) {
            return Optional.empty();
        }
        return Optional.of(new LocatedRegion(
                candidate.pageNumber(),
                anchor.label(),
                anchor.visibleDescription(),
                anchor.x(),
                anchor.y(),
                anchor.width(),
                anchor.height(),
                supportedClaims.stream().map(VisualRegionLocator.Claim::evidenceId).distinct().toList(),
                supportedClaims.stream().map(VisualRegionLocator.Claim::stepPosition).distinct().toList()));
    }

    static List<VisualRegionLocator.Claim> claimsForExactCrop(
            VisualLocationRequest request, LocatedRegion region) {
        java.util.Set<UUID> supportedEvidence = java.util.Set.copyOf(region.supportedEvidenceIds());
        return request.claims().stream()
                .filter(claim -> supportedEvidence.contains(claim.evidenceId()))
                .filter(claim -> region.supportedStepPositions().isEmpty()
                        || region.supportedStepPositions().contains(claim.stepPosition()))
                .filter(claim -> sourceIncludes(claim, region.pageNumber()))
                .toList();
    }

    static byte[] croppedPng(VisualRegionLocator.PageImage page, LocatedRegion region) {
        if (page == null) return null;
        try (var input = new ByteArrayInputStream(page.content()); var output = new ByteArrayOutputStream()) {
            BufferedImage source = ImageIO.read(input);
            if (source == null) return null;
            int x = region.x() * source.getWidth() / 1_000;
            int y = region.y() * source.getHeight() / 1_000;
            int right = Math.min(
                    source.getWidth(), Math.max(x + 1, (region.x() + region.width()) * source.getWidth() / 1_000));
            int bottom = Math.min(
                    source.getHeight(), Math.max(y + 1, (region.y() + region.height()) * source.getHeight() / 1_000));
            BufferedImage crop = source.getSubimage(x, y, right - x, bottom - y);
            if (!ImageIO.write(crop, "png", output)) return null;
            return output.toByteArray();
        } catch (IOException | RasterFormatException unreadable) {
            return null;
        }
    }

    static boolean hasEnoughRenderedVisualSignal(VisualLocationRequest request, LocatedRegion region) {
        VisualRegionLocator.PageImage page = request.pages().stream()
                .filter(candidate -> candidate.pageNumber() == region.pageNumber())
                .findFirst()
                .orElse(null);
        byte[] content = croppedPng(page, region);
        if (content == null) return true;
        try (var input = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) return true;
            return foregroundShare(image) >= MIN_CATALOGED_LEGEND_SIGNAL;
        } catch (IOException unreadable) {
            return true;
        }
    }

    static List<LocatedRegion> withoutOversizedReaderViewports(List<LocatedRegion> regions) {
        return regions.stream().filter(region -> !requiresTighterReaderViewport(region)).toList();
    }

    static boolean requiresTighterReaderViewport(LocatedRegion region) {
        return (long) region.width() * region.height() > MAX_READER_CROP_AREA;
    }

    static String tightReaderViewportInstruction() {
        return "The previous crop occupied too much of the page. Return a compact rectangle around only the visible "
                + "region that directly supports the cited claim and step. Exclude unrelated surrounding content. If "
                + "no compact player-facing crop is available, return an empty regions array.";
    }

    static VisualLocatorResponsePolicy.ModelRegion normalizedGeometry(
            VisualLocatorResponsePolicy.ModelRegion region) {
        int x = Math.max(0, Math.min(980, region.x()));
        int y = Math.max(0, Math.min(980, region.y()));
        int width = Math.max(20, Math.min(region.width(), 1_000 - x));
        int height = Math.max(20, Math.min(region.height(), 1_000 - y));
        return new VisualLocatorResponsePolicy.ModelRegion(
                region.pageNumber(),
                region.label(),
                region.visibleDescription(),
                x,
                y,
                width,
                height,
                region.supportedClaimRefs());
    }

    static List<VisualRegionLocator.Claim> pageScopedClaims(
            int pageNumber,
            List<VisualRegionLocator.Claim> referencedClaims,
            List<VisualRegionLocator.Claim> availableClaims) {
        List<VisualRegionLocator.Claim> samePageReferences = referencedClaims.stream()
                .filter(claim -> sourceIncludes(claim, pageNumber))
                .toList();
        if (!samePageReferences.isEmpty()) return samePageReferences;
        List<VisualRegionLocator.Claim> pageClaims = availableClaims.stream()
                .filter(claim -> sourceIncludes(claim, pageNumber))
                .toList();
        return pageClaims.size() == 1 ? pageClaims : List.of();
    }

    private static double foregroundShare(BufferedImage image) {
        int stepX = Math.max(1, image.getWidth() / CROP_SAMPLE_EDGE);
        int stepY = Math.max(1, image.getHeight() / CROP_SAMPLE_EDGE);
        int[] histogram = new int[16 * 16 * 16];
        int samples = 0;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                histogram[quantizedRgb(image.getRGB(x, y))]++;
                samples++;
            }
        }
        if (samples == 0) return 1.0;
        int dominant = 0;
        for (int index = 1; index < histogram.length; index++) {
            if (histogram[index] > histogram[dominant]) dominant = index;
        }
        int red = ((dominant >> 8) & 15) * 16;
        int green = ((dominant >> 4) & 15) * 16;
        int blue = (dominant & 15) * 16;
        int foreground = 0;
        int threshold = CROP_FOREGROUND_DISTANCE * CROP_FOREGROUND_DISTANCE;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                Color color = new Color(image.getRGB(x, y));
                int distance = (color.getRed() - red) * (color.getRed() - red)
                        + (color.getGreen() - green) * (color.getGreen() - green)
                        + (color.getBlue() - blue) * (color.getBlue() - blue);
                if (distance > threshold) foreground++;
            }
        }
        return (double) foreground / samples;
    }

    private static int quantizedRgb(int rgb) {
        Color color = new Color(rgb);
        return (color.getRed() >> 4) * 256 + (color.getGreen() >> 4) * 16 + (color.getBlue() >> 4);
    }

    private static boolean sourceIncludes(VisualRegionLocator.Claim claim, int pageNumber) {
        return claim.sourcePages().isEmpty() || claim.sourcePages().contains(pageNumber);
    }
}
