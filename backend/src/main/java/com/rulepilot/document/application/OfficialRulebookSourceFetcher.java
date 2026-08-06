package com.rulepilot.document.application;

import java.net.URI;

public interface OfficialRulebookSourceFetcher {

    FetchedRulebook fetch(URI source);

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
