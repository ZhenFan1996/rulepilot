package com.rulepilot.document;

/** Produces a bounded reader crop from a rendered rulebook page. */
public interface DocumentPageImageCropper {

    byte[] crop(DocumentPageImages.PageImage page, int x, int y, int width, int height);
}
