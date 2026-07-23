package com.rulepilot.teaching.adapter.out.cover;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class HttpPublicCoverImageFetcherTest {

    @Test
    void accepts_only_public_destination_addresses_for_cover_fetches() throws Exception {
        assertThat(HttpPublicCoverImageFetcher.isPublicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(HttpPublicCoverImageFetcher.isPublicAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(HttpPublicCoverImageFetcher.isPublicAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(HttpPublicCoverImageFetcher.isPublicAddress(InetAddress.getByName("100.64.0.1"))).isFalse();
        assertThat(HttpPublicCoverImageFetcher.isPublicAddress(InetAddress.getByName("169.254.1.1"))).isFalse();
        assertThat(HttpPublicCoverImageFetcher.isPublicAddress(InetAddress.getByName("fc00::1"))).isFalse();
    }
}
