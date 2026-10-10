package com.autox.user.dto;

import com.autox.user.RefreshToken;
import java.time.Instant;
import java.util.UUID;

/**
 * One live session of the signed-in account.
 *
 * <p>{@code id} is the rotation family, not the token row. A session's token is replaced on
 * every refresh, so the row id would change under the client between two reads of the list;
 * the family is stable for the life of the session and is what the revoke endpoint takes.
 *
 * <p>Nothing here is a credential. The token itself is not in the database in a usable form
 * at all, let alone in this response.
 *
 * @param id           the session identifier, to pass to {@code DELETE /me/sessions/{id}}
 * @param deviceLabel  what the client called itself at sign-in; {@code null} if it said nothing
 * @param userAgent    raw User-Agent header, for a client that wants to name the device itself
 * @param ipAddress    where the session was created from, so an unexpected one is noticeable
 * @param createdAt    when the session began
 * @param lastUsedAt   when it last refreshed; {@code null} if it never has
 * @param expiresAt    when it dies on its own
 */
public record SessionResponse(UUID id,
                              String deviceLabel,
                              String userAgent,
                              String ipAddress,
                              Instant createdAt,
                              Instant lastUsedAt,
                              Instant expiresAt) {

    public static SessionResponse from(RefreshToken token) {
        return new SessionResponse(token.getFamilyId(), token.getDeviceLabel(), token.getUserAgent(),
                token.getIpAddress(), token.getIssuedAt(), token.getLastUsedAt(), token.getExpiresAt());
    }
}
