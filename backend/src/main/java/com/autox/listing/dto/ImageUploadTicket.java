package com.autox.listing.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a client needs to upload one photo, and nothing about our storage layout.
 *
 * <p>The client PUTs the bytes to {@code uploadUrl} with exactly {@code requiredHeaders},
 * then calls the confirm endpoint with {@code imageId}. The bytes never touch the API.
 */
public record ImageUploadTicket(
        UUID imageId,
        String uploadUrl,
        String method,
        Map<String, String> requiredHeaders,
        Instant expiresAt,
        long maxBytes,
        String confirmPath) {
}
