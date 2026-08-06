package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class OfficialRulebookNetworkAddressPolicyTest {

    @Test
    void rejectsPrivateMetadataCarrierNatAndDocumentationDestinations() throws Exception {
        assertThat(isPublic("127.0.0.1")).isFalse();
        assertThat(isPublic("10.0.0.1")).isFalse();
        assertThat(isPublic("100.64.0.1")).isFalse();
        assertThat(isPublic("169.254.169.254")).isFalse();
        assertThat(isPublic("192.0.2.1")).isFalse();
        assertThat(isPublic("198.51.100.1")).isFalse();
        assertThat(isPublic("203.0.113.1")).isFalse();
        assertThat(isPublic("fc00::1")).isFalse();
    }

    @Test
    void allowsOrdinaryPublicIpv4AndIpv6Destinations() throws Exception {
        assertThat(isPublic("1.1.1.1")).isTrue();
        assertThat(isPublic("192.1.1.1")).isTrue();
        assertThat(isPublic("198.50.1.1")).isTrue();
        assertThat(isPublic("2606:4700:4700::1111")).isTrue();
    }

    private boolean isPublic(String address) throws Exception {
        return OfficialRulebookNetworkAddressPolicy.isPublic(InetAddress.getByName(address));
    }
}
