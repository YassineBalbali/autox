package com.autox.config.env;

import java.util.List;

/**
 * One piece of configuration the application cannot start without.
 *
 * <p>Keyed on the <b>Spring property</b> rather than on the environment variable, which is
 * the distinction that makes this check correct rather than merely approximate. The
 * requirement is "the application is configured", not "this variable is set": the
 * integration tests supply {@code autox.auth.jwt-secret} from
 * {@code application-test.yml} and the datasource from Testcontainers, and never set an
 * {@code AUTOX_*} variable at all. Checking the variable would fail all of them; checking
 * the property asks the real question and still lets the message name the variable that
 * normally answers it.
 *
 * @param property    the Spring property that must resolve to a non-blank value
 * @param variable    the environment variable {@code application.yml} wires it to, named in
 *                    the failure message because that is what a developer has to go and set
 * @param requirement what a valid value looks like, in a sentence
 * @param mustStartWith a required prefix, or {@code null}. Exists for the datasource URL:
 *                    {@code 'url' must start with "jdbc"} is the Hikari assertion this
 *                    whole class was written to pre-empt, so it is worth catching the
 *                    malformed case here rather than only the missing one.
 */
record RequiredSetting(String property, String variable, String requirement, String mustStartWith) {

    RequiredSetting(String property, String variable, String requirement) {
        this(property, variable, requirement, null);
    }

    /**
     * The settings with no default, and no default on purpose.
     *
     * <p>Everything absent from this list has a working local default in
     * {@code application.yml}. Everything on it is a credential, and the project's standing
     * rule is that credentials come from the environment with no fallback — a default
     * password is a password in version control, and one that works locally is one that
     * reaches a server eventually.
     *
     * <p>So the answer to "add sensible local defaults" is split: the non-secret
     * coordinates got defaults, and these four got a failure message good enough that the
     * absence of a default costs a developer ten seconds instead of ten minutes.
     */
    static List<RequiredSetting> all() {
        return List.of(
                new RequiredSetting(
                        "spring.datasource.url",
                        "AUTOX_DB_URL",
                        "a JDBC URL, e.g. jdbc:postgresql://localhost:5433/autox",
                        "jdbc:"),
                new RequiredSetting(
                        "spring.datasource.password",
                        "AUTOX_DB_PASSWORD",
                        "the PostgreSQL password; must match POSTGRES_PASSWORD, which the "
                                + "same file gives to the container"),
                new RequiredSetting(
                        "autox.auth.jwt-secret",
                        "AUTOX_JWT_SECRET",
                        "an HS256 signing key of at least 32 bytes — generate one with "
                                + "`openssl rand -base64 48`"),
                new RequiredSetting(
                        "autox.storage.access-key",
                        "AUTOX_S3_ACCESS_KEY",
                        "the object storage access key; locally this is MINIO_ROOT_USER"),
                new RequiredSetting(
                        "autox.storage.secret-key",
                        "AUTOX_S3_SECRET_KEY",
                        "the object storage secret key; locally this is MINIO_ROOT_PASSWORD"));
    }
}
