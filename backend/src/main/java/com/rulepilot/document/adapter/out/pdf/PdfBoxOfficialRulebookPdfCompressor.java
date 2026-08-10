package com.rulepilot.document.adapter.out.pdf;

import com.rulepilot.document.application.OfficialRulebookPdfCompressor;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

@Component
public class PdfBoxOfficialRulebookPdfCompressor implements OfficialRulebookPdfCompressor {

    private static final int MAXIMUM_PAGES = 500;
    private static final int MAXIMUM_IMAGES = 2_000;
    private static final long MAXIMUM_IMAGE_PIXELS = 40_000_000L;
    private static final long MAXIMUM_DECODED_PIXELS = 500_000_000L;
    private static final int MINIMUM_RECOMPRESS_BYTES = 128 * 1024;
    private static final List<CompressionPass> PASSES = List.of(
            new CompressionPass(0, 0),
            new CompressionPass(2_000, 0.82f),
            new CompressionPass(1_600, 0.72f));

    @Override
    public byte[] compress(byte[] sourcePdf, long maximumOutputBytes) {
        if (sourcePdf == null
                || sourcePdf.length < 5
                || maximumOutputBytes <= 0
                || maximumOutputBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("rulebook PDF compression input is invalid");
        }
        for (CompressionPass pass : PASSES) {
            byte[] compressed = attempt(sourcePdf, maximumOutputBytes, pass);
            if (compressed != null) return compressed;
        }
        throw new IllegalArgumentException("rulebook PDF could not be compressed below the configured size limit");
    }

    private byte[] attempt(byte[] sourcePdf, long maximumOutputBytes, CompressionPass pass) {
        try (PDDocument document = Loader.loadPDF(
                sourcePdf, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("encrypted rulebook PDFs cannot be compressed safely");
            }
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > MAXIMUM_PAGES) {
                throw new IllegalArgumentException("rulebook PDF page count is outside the compression limit");
            }
            if (pass.recompressesImages()) recompressImages(document, pass);
            byte[] output = saveBounded(document, maximumOutputBytes);
            if (output == null) return null;
            validateResult(output, pages, maximumOutputBytes);
            return output;
        } catch (SizeLimitExceededException exception) {
            return null;
        } catch (IOException exception) {
            throw new IllegalArgumentException("rulebook PDF could not be parsed for compression", exception);
        }
    }

    private void recompressImages(PDDocument document, CompressionPass pass) throws IOException {
        Map<COSBase, PDImageXObject> replacements = new IdentityHashMap<>();
        Set<COSBase> visitedForms = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        CompressionBudget budget = new CompressionBudget();
        for (var page : document.getPages()) {
            recompressResources(document, page.getResources(), pass, replacements, visitedForms, budget);
        }
    }

    private void recompressResources(
            PDDocument document,
            PDResources resources,
            CompressionPass pass,
            Map<COSBase, PDImageXObject> replacements,
            Set<COSBase> visitedForms,
            CompressionBudget budget)
            throws IOException {
        if (resources == null) return;
        List<COSName> names = new ArrayList<>();
        resources.getXObjectNames().forEach(names::add);
        for (COSName name : names) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                COSBase identity = image.getCOSObject();
                PDImageXObject replacement = replacements.get(identity);
                if (replacement == null) {
                    replacement = recompressImage(document, image, pass, budget);
                    replacements.put(identity, replacement == null ? image : replacement);
                }
                if (replacement != null && replacement != image) resources.put(name, replacement);
            } else if (object instanceof PDFormXObject form && visitedForms.add(form.getCOSObject())) {
                recompressResources(document, form.getResources(), pass, replacements, visitedForms, budget);
            }
        }
    }

    private PDImageXObject recompressImage(
            PDDocument document, PDImageXObject source, CompressionPass pass, CompressionBudget budget) {
        if (source.isStencil()
                || source.getCOSObject().containsKey(COSName.MASK)
                || source.getCOSObject().containsKey(COSName.SMASK)) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        long pixels = (long) width * height;
        int encodedBytes = source.getCOSObject().getInt(COSName.LENGTH, 0);
        if (width < 1
                || height < 1
                || pixels > MAXIMUM_IMAGE_PIXELS
                || encodedBytes < MINIMUM_RECOMPRESS_BYTES && Math.max(width, height) <= pass.maximumEdge()) {
            return null;
        }
        budget.include(pixels);
        try {
            BufferedImage decoded = source.getImage();
            BufferedImage readable = opaqueScaled(decoded, pass.maximumEdge());
            PDImageXObject replacement = JPEGFactory.createFromImage(document, readable, pass.jpegQuality(), 144);
            replacement.setInterpolate(source.getInterpolate());
            replacement.setOptionalContent(source.getOptionalContent());
            return replacement;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private BufferedImage opaqueScaled(BufferedImage source, int maximumEdge) {
        double scale = Math.min(1d, (double) maximumEdge / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] saveBounded(PDDocument document, long maximumOutputBytes) throws IOException {
        var output = new BoundedOutput(maximumOutputBytes);
        document.save(output, CompressParameters.DEFAULT_COMPRESSION);
        return output.toByteArray();
    }

    private void validateResult(byte[] compressed, int expectedPages, long maximumOutputBytes) throws IOException {
        if (compressed.length == 0 || compressed.length > maximumOutputBytes) {
            throw new IllegalArgumentException("compressed rulebook PDF exceeds the configured size limit");
        }
        try (PDDocument result = Loader.loadPDF(
                compressed, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
            if (result.getNumberOfPages() != expectedPages) {
                throw new IllegalArgumentException("rulebook PDF compression changed the page count");
            }
        }
    }

    private record CompressionPass(int maximumEdge, float jpegQuality) {
        boolean recompressesImages() {
            return maximumEdge > 0;
        }
    }

    private static final class CompressionBudget {
        private int images;
        private long decodedPixels;

        void include(long pixels) {
            images++;
            decodedPixels += pixels;
            if (images > MAXIMUM_IMAGES || decodedPixels > MAXIMUM_DECODED_PIXELS) {
                throw new IllegalArgumentException("rulebook PDF image compression budget was exceeded");
            }
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final long maximumBytes;

        private BoundedOutput(long maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(int additionalBytes) throws SizeLimitExceededException {
            if ((long) delegate.size() + additionalBytes > maximumBytes) throw new SizeLimitExceededException();
        }

        byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private static final class SizeLimitExceededException extends IOException {}
}
