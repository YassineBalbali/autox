package com.autox.storage;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the S3 client and presigner from {@link StorageProperties}.
 *
 * <p>Holds no dependency on {@link ObjectStorage} itself: injecting the thing these beans
 * are used to build would be a cycle. The readiness check lives in
 * {@link StorageReadinessCheck} for that reason.
 */
@Configuration
public class StorageConfig {

    @Bean
    S3Client s3Client(StorageProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .httpClientBuilder(ApacheHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        // The SDK sends a putObject body in aws-chunked form by default.
                        // MinIO, AWS and Supabase Storage accept that; Cloudflare R2 rejects
                        // it with a 403, and other S3-compatible stores answer 501. A plain
                        // body works on every one of them, and the bodies written here are a
                        // few hundred kilobytes already in memory. Checked against Supabase's
                        // storage server, over HTTPS and behind its /storage/v1 prefix: every
                        // call this application makes, with byte-identical round trips.
                        .chunkedEncodingEnabled(false)
                        .build());

        endpointOf(properties).ifPresent(builder::endpointOverride);
        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties properties) {
        var builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build());

        endpointOf(properties).ifPresent(builder::endpointOverride);
        return builder.build();
    }

    /** Empty for AWS itself, which resolves its own regional endpoint. */
    private static java.util.Optional<URI> endpointOf(StorageProperties properties) {
        return properties.endpoint() == null || properties.endpoint().isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(URI.create(properties.endpoint()));
    }

    private static StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
