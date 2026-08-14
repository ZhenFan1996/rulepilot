package com.rulepilot.document.application;

import java.net.URI;

public interface OfficialRulebookSourceFetcher {

    FetchedRulebook fetch(URI source);

    default FetchedRulebook fetch(URI source, ProgressListener progress) {
        FetchedRulebook fetched = fetch(source);
        progress.downloadStarted((long) fetched.content().length);
        progress.downloaded(fetched.content().length, (long) fetched.content().length);
        progress.downloadCompleted();
        progress.verifying();
        return fetched;
    }

    interface ProgressListener {
        void downloadStarted(Long totalBytes);

        void downloaded(long downloadedBytes, Long totalBytes);

        /** All remote bytes are local; PDF assembly, compression, validation, and storage may still follow. */
        default void downloadCompleted() {}

        default void compressing() {}

        void verifying();

        default void saving() {}

        static ProgressListener none() {
            return new ProgressListener() {
                @Override public void downloadStarted(Long totalBytes) {}
                @Override public void downloaded(long downloadedBytes, Long totalBytes) {}
                @Override public void verifying() {}
            };
        }
    }

    record FetchedRulebook(URI finalSource, byte[] content) {
        public FetchedRulebook {
            if (finalSource == null || content == null || content.length == 0) {
                throw new IllegalArgumentException("fetched official rulebook is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
