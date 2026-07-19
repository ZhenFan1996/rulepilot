package com.rulepilot.document;

import java.util.UUID;

public interface DocumentPageImageStore {

    void store(UUID documentVersionId, RenderedPageImage image);

    record RenderedPageImage(int pageNumber, byte[] content, int width, int height) {
        public RenderedPageImage {
            if (pageNumber < 1 || content == null || content.length == 0 || width < 1 || height < 1) {
                throw new IllegalArgumentException("rendered page image is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
