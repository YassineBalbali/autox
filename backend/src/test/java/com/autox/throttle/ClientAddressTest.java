package com.autox.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The throttle key. A plain unit test: this is arithmetic on an address and needs no
 * database, which is also why it is the one place in the suite without a container.
 */
class ClientAddressTest {

    @Test
    @DisplayName("an IPv4 address is its own key")
    void ipv4IsUnchanged() {
        assertThat(ClientAddress.throttleKey("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.throttleKey("127.0.0.1")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("an IPv6 address is reduced to its /64 prefix")
    void ipv6IsMaskedToThePrefix() {
        assertThat(ClientAddress.throttleKey("2001:db8:85a3:1:1234:5678:9abc:def0"))
                .isEqualTo("2001:db8:85a3:1:0:0:0:0/64");
    }

    @Test
    @DisplayName("the whole /64 shares one key — the point of the exercise")
    void addressesInOneSubnetShareAKey() {
        // This is the defect being fixed: keyed on the full address, a scraper walks its
        // own subnet and is never counted twice.
        String first = ClientAddress.throttleKey("2001:db8:85a3:1::1");
        String second = ClientAddress.throttleKey("2001:db8:85a3:1:ffff:ffff:ffff:ffff");
        assertThat(first).isEqualTo(second);

        // A different /64 is a different caller, and still counted separately.
        assertThat(ClientAddress.throttleKey("2001:db8:85a3:2::1")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("IPv6 loopback collapses to a prefix key, as the live test showed it would")
    void ipv6LoopbackIsHandled() {
        // A request to localhost arrives as 0:0:0:0:0:0:0:1, which is how the gap was found.
        assertThat(ClientAddress.throttleKey("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0:0:0:0:0:0/64");
        assertThat(ClientAddress.throttleKey("::1")).isEqualTo("0:0:0:0:0:0:0:0/64");
    }

    @Test
    @DisplayName("an IPv4-mapped address is treated as the IPv4 host it is")
    void ipv4MappedIsNotMasked() {
        // Masking ::ffff:203.0.113.7 to a /64 would merge every mapped IPv4 address in the
        // world into one counter.
        assertThat(ClientAddress.throttleKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("brackets and a zone index are not part of the caller's identity")
    void bracketsAndZoneAreStripped() {
        assertThat(ClientAddress.throttleKey("[2001:db8:85a3:1::1]")).isEqualTo("2001:db8:85a3:1:0:0:0:0/64");
        assertThat(ClientAddress.throttleKey("fe80:0:0:1::1%eth0")).isEqualTo("fe80:0:0:1:0:0:0:0/64");
    }

    @Test
    @DisplayName("no address means no key, and anything unparseable keys on itself")
    void unusableInput() {
        assertThat(ClientAddress.throttleKey(null)).isNull();
        assertThat(ClientAddress.throttleKey("   ")).isNull();
        // Not an address, but still one identifiable caller: counted, not waved through,
        // and not merged with every other unrecognised value.
        assertThat(ClientAddress.throttleKey("unknown-proxy")).isEqualTo("unknown-proxy");
        assertThat(ClientAddress.throttleKey("2001:db8:::::zzz")).isEqualTo("2001:db8:::::zzz");
    }
}
