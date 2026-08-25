package com.rulepilot.catalog.adapter.out.cover;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class HttpCoverImageFetcherTest {

    @Test
    void allowsPublicAddressesAndRejectsPrivateLinkLocalAndDocumentationNetworks() throws Exception {
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("100.64.0.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("169.254.1.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("172.16.0.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("192.168.0.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("192.0.2.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("198.51.100.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("203.0.113.1"))).isFalse();
        assertThat(HttpCoverImageFetcher.isPublicAddress(InetAddress.getByName("fc00::1"))).isFalse();
    }
}
