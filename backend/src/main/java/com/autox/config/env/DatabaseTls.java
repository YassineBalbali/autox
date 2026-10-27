package com.autox.config.env;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a PostgreSQL JDBC URL checks who is on the other end.
 *
 * <p>{@code sslmode=require} encrypts the connection and checks nothing: the driver accepts
 * any certificate, so anything on the path to the database can stand in for it, read every
 * query and every row, and take the password. It is what Neon's console hands out, which is
 * how the production URL came to use it. {@code verify-full} checks that the certificate
 * chains to a trusted root and that it names the host. The PostgreSQL driver's default
 * trust source is a file, {@code ~/.postgresql/root.crt}, which the container does not have,
 * so the URL also names {@code sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory}: the
 * JVM's truststore. A {@code sslrootcert} naming a file is accepted too.
 *
 * <p>A database on this machine is exempt: local development and its Docker containers
 * listen on the loopback address, where there is no path to intercept. Every other host
 * must verify.
 */
final class DatabaseTls {

    static final String REQUIRED = "sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory";

    private static final String PREFIX = "jdbc:postgresql://";
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");

    private DatabaseTls() {
    }

    /**
     * Why this URL does not verify the server, naming the host and the settings found and never
     * the rest of the URL, which can carry a password; empty when it verifies, points at this
     * machine, or is not a PostgreSQL URL at all.
     */
    static Optional<String> problem(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = url.substring(PREFIX.length());
        int query = rest.indexOf('?');
        String authority = (query < 0 ? rest : rest.substring(0, query));
        int slash = authority.indexOf('/');
        List<String> hosts = List.of((slash < 0 ? authority : authority.substring(0, slash)).split(","));
        Map<String, String> parameters = parameters(query < 0 ? "" : rest.substring(query + 1));

        List<String> remote = hosts.stream().map(DatabaseTls::hostOf).filter(host -> !LOOPBACK.contains(host)).toList();
        if (remote.isEmpty()) {
            return Optional.empty();
        }

        String mode = parameters.getOrDefault("sslmode", "prefer").toLowerCase(Locale.ROOT);
        boolean trustSource = parameters.containsKey("sslfactory") || parameters.containsKey("sslrootcert");
        if (mode.equals("verify-full") && trustSource) {
            return Optional.empty();
        }
        String found = "sslmode=" + mode + (mode.equals("verify-full") ? ", but no sslfactory or sslrootcert" : "");
        return Optional.of("points at " + String.join(", ", remote) + " with " + found);
    }

    /** "host", "host:5432" or "[::1]:5432" to the host alone, lower-cased. */
    private static String hostOf(String hostAndPort) {
        String value = hostAndPort.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close < 0 ? value : value.substring(1, close);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> parameters = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            parameters.put(key.toLowerCase(Locale.ROOT), value);
        }
        return parameters;
    }
}
