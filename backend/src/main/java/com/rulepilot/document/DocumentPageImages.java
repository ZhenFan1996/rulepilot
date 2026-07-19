package com.rulepilot.document;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface DocumentPageImages {

    List<PageImage> read(UUID documentVersionId, Set<Integer> pageNumbers);

    record PageImage(int pageNumber, String mediaType, byte[] content, int width, int height) {
        public PageImage {
            if (pageNumber < 1 || mediaType == null || mediaType.isBlank() || content == null || content.length == 0
                    || width < 1 || height < 1) {
                throw new IllegalArgumentException("document page image is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
