package com.rulepilot.document.adapter.out.source;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Rejects local, reserved, documentation, carrier NAT, and multicast rulebook-source destinations. */
final class OfficialRulebookNetworkAddressPolicy {

    private OfficialRulebookNetworkAddressPolicy() {}

    static boolean isPublic(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet6Address ipv6) {
            byte first = ipv6.getAddress()[0];
            return (first & 0xfe) != 0xfc;
        }
        if (!(address instanceof Inet4Address)) return false;
        byte[] value = address.getAddress();
        int first = Byte.toUnsignedInt(value[0]);
        int second = Byte.toUnsignedInt(value[1]);
        int third = Byte.toUnsignedInt(value[2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && second == 0 && (third == 0 || third == 2)) return false;
        if (first == 192 && second == 88 && third == 99) return false;
        if (first == 192 && second == 168) return false;
        if (first == 198 && (second == 18 || second == 19)) return false;
        if (first == 198 && second == 51 && third == 100) return false;
        return first != 203 || second != 0 || third != 113;
    }
}
