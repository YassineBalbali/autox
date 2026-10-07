package com.autox.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object storage configuration, bound from {@code autox.storage.*}.
 *
 * <p>Every S3-compatible provider is reachable with these five values. MinIO in
 * docker-compose, Cloudflare R2 or AWS S3 differ only in endpoint, credentials and whether
 * path-style addressing is required — not in code.
 */
@ConfigurationProperties(prefix = "autox.storage")
public record StorageProperties(

        /** Base endpoint. Empty means AWS S3's own regional endpoint. */
        String endpoint,

        String region,
        String bucket,
        String accessKey,
        String secretKey,

        /**
         * {@code true} addresses the bucket as a path segment ({@code host/bucket/key})
         * rather than a subdomain. MinIO needs this; so does any endpoint reached by IP.
         */
        boolean pathStyleAccess,

        /**
         * Public base URL — a CDN or a public bucket — used to build read URLs. When empty,
         * read URLs are signed and time-limited instead.
         */
        String publicBaseUrl,

        /** How long a pre-signed upload URL stays valid. */
        Duration uploadUrlTtl,

        /** How long a signed read URL stays valid, when the bucket is not public. */
        Duration readUrlTtl,

        /** Creates the bucket at startup when missing. For local development only. */
        boolean createBucketIfMissing) {

    public StorageProperties {
        region = region == null || region.isBlank() ? "us-east-1" : region;
        uploadUrlTtl = orDefault(uploadUrlTtl, Duration.ofMinutes(15));
        readUrlTtl = orDefault(readUrlTtl, Duration.ofHours(6));
    }

    public boolean hasPublicBaseUrl() {
        return publicBaseUrl != null && !publicBaseUrl.isBlank();
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
