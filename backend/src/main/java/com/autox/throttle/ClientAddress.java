package com.autox.throttle;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a client address into the key a throttle counts against.
 *
 * <p>An IPv4 address is one host, so it is its own key. An IPv6 address is not: a single
 * residential or hosted connection is routinely handed a <b>/64</b>, which is 18 billion
 * billion addresses the same caller can pick from at will. Keying on the full address
 * therefore means an IPv6 client is never throttled at all — it simply uses the next one.
 * This was not theoretical: the first live test of the public rate limit recorded the key
 * {@code 0:0:0:0:0:0:0:1}, because a loopback request arrives over IPv6.
 *
 * <p>So IPv6 is masked to its /64 prefix. That is the smallest block an operator is
 * expected to assign as a unit, which makes it the smallest unit that behaves like "one
 * subscriber". Masking wider (a /48, say) would start grouping unrelated customers of the
 * same provider together.
 *
 * <p>The keys are deliberately distinguishable — {@code 203.0.113.7} against
 * {@code 2001:db8:1:2:0:0:0:0/64} — so a counter row says what it is counting.
 */
public final class ClientAddress {

    /** How many leading bytes of an IPv6 address the key keeps. */
    private static final int IPV6_PREFIX_BYTES = 8;

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
    /** Characters an IPv6 literal can contain, including the embedded-IPv4 form. */
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private ClientAddress() {
    }

    /**
     * @param address the client address as the servlet container reports it
     * @return the throttle key, or {@code null} if there is no usable address
     */
    public static String throttleKey(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }

        String candidate = normalise(address);
        if (candidate.isEmpty()) {
            return null;
        }
        if (IPV4.matcher(candidate).matches()) {
            return candidate;
        }
        if (candidate.indexOf(':') < 0 || !IPV6_LITERAL.matcher(candidate).matches()) {
            // Not an address we recognise. Key on it verbatim rather than returning null:
            // unknown callers must not all collapse into one shared bucket, and they must
            // not all escape counting either.
            return candidate;
        }

        try {
            InetAddress parsed = InetAddress.getByName(candidate);
            if (parsed instanceof Inet4Address) {
                // ::ffff:203.0.113.7 — an IPv4 host wearing an IPv6 spelling.
                return parsed.getHostAddress();
            }
            if (parsed instanceof Inet6Address) {
                return prefixKey(parsed.getAddress());
            }
            return candidate;
        } catch (UnknownHostException notAnAddress) {
            return candidate;
        }
    }

    /** Zeroes everything after the /64 and renders the result in full, uncompressed form. */
    private static String prefixKey(byte[] bytes) {
        StringBuilder key = new StringBuilder(39);
        for (int group = 0; group < 8; group++) {
            if (group > 0) {
                key.append(':');
            }
            if (group * 2 >= IPV6_PREFIX_BYTES) {
                key.append('0');
                continue;
            }
            int value = ((bytes[group * 2] & 0xFF) << 8) | (bytes[group * 2 + 1] & 0xFF);
            key.append(Integer.toHexString(value).toLowerCase(Locale.ROOT));
        }
        return key.append("/64").toString();
    }

    /**
     * Drops the brackets a literal may arrive in and the {@code %eth0} zone index, neither
     * of which identifies the caller: the same host behind two interfaces is one host.
     */
    private static String normalise(String address) {
        String trimmed = address.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        int zone = trimmed.indexOf('%');
        return zone < 0 ? trimmed : trimmed.substring(0, zone);
    }
}
