package com.rulepilot.document;

/** Produces bounded reader images from a rendered rulebook page. */
public interface DocumentPageImageCropper {

    byte[] crop(DocumentPageImages.PageImage page, int x, int y, int width, int height);

    /** Returns a lightweight whole-page locator preview; it is presentation context, never rule evidence by itself. */
    byte[] preview(DocumentPageImages.PageImage page);

    default byte[] crop(
            DocumentPageImages.PageImage page,
            int x,
            int y,
            int width,
            int height,
            int contextPadding) {
        return crop(page, x, y, width, height);
    }
}
