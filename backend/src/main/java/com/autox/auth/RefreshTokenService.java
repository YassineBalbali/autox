package com.autox.auth;

import com.autox.api.error.ApiException;
import com.autox.config.AuthProperties;
import com.autox.user.AppUser;
import com.autox.user.RefreshToken;
import com.autox.user.RefreshTokenRepository;
import com.autox.user.TokenRevocationReason;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>The token is 32 random bytes, base64url-encoded, and is the only thing the
 * client ever sees. The database stores its SHA-256, so a dump of
 * {@code refresh_token} yields nothing usable. SHA-256 and not BCrypt because the
 * token is already full-entropy random — slow hashing defends against guessable
 * inputs, and a refresh call would otherwise pay 250 ms for nothing.
 *
 * <p>Rotation: every refresh revokes the presented row and issues a successor in the
 * same family. Presenting a token that was already rotated means a copy is in
 * circulation, so the entire family is revoked and every session descended from
 * that login dies. This is the standard OAuth 2.1 reuse-detection rule, and it is
 * what makes a 90-day token acceptable.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenRevoker revoker;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokens, RefreshTokenRevoker revoker,
                               AuthProperties properties) {
        this.refreshTokens = refreshTokens;
        this.revoker = revoker;
        this.properties = properties;
    }

    /** A brand-new session: starts its own rotation family. */
    @Transactional
    public IssuedToken issueNewSession(AppUser user, SessionContext context) {
        return persist(user, null, context);
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * @throws ApiException if the token is unknown, expired, revoked, or being replayed
     */
    @Transactional
    public Rotation rotate(String presentedToken, SessionContext context) {
        String hash = sha256(presentedToken);
        Instant now = Instant.now();

        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElseThrow(ApiException::invalidRefreshToken);

        if (stored.getRevokedAt() != null) {
            // Already rotated or logged out, yet someone still holds it: assume theft.
            // Committed separately, because the exception below would otherwise roll
            // the revocation back and leave the stolen session usable.
            int killed = revoker.revokeFamilyNow(stored.getFamilyId(), TokenRevocationReason.REUSE_DETECTED);
            log.warn("Refresh token replay detected for user {}; revoked {} token(s) in family {}",
                    stored.getUser().getId(), killed, stored.getFamilyId());
            throw ApiException.invalidRefreshToken();
        }

        if (!stored.getExpiresAt().isAfter(now)) {
            revoker.revokeFamilyNow(stored.getFamilyId(), TokenRevocationReason.EXPIRED);
            throw ApiException.invalidRefreshToken();
        }

        AppUser user = stored.getUser();
        stored.setLastUsedAt(now);

        if (!properties.rotateRefreshTokens()) {
            return new Rotation(user, new IssuedToken(presentedToken, stored.getExpiresAt(), stored.getFamilyId()));
        }

        stored.revoke(TokenRevocationReason.ROTATED, now);
        // Sliding expiry: an actively used session never expires out from under the user.
        IssuedToken successor = persist(user, stored.getFamilyId(), context);
        return new Rotation(user, successor);
    }

    /** Logout: ends this device's session only, leaving other devices signed in. */
    @Transactional
    public void revokeSession(String presentedToken) {
        Optional<RefreshToken> stored = refreshTokens.findByTokenHash(sha256(presentedToken));
        // Unknown or already-revoked tokens are not an error: logout is idempotent,
        // and reporting "no such token" would confirm which tokens exist.
        stored.filter(token -> token.getRevokedAt() == null).ifPresent(token ->
                refreshTokens.revokeFamily(token.getFamilyId(), TokenRevocationReason.LOGOUT, Instant.now()));
    }

    /** Ends every session for an account. Returns how many were live. */
    @Transactional
    public int revokeAllSessions(java.util.UUID userId, TokenRevocationReason reason) {
        return refreshTokens.revokeAllForUser(userId, reason, Instant.now());
    }

    /** Housekeeping: drops token rows whose expiry is long past. */
    @Transactional
    public int purgeExpiredBefore(Instant before) {
        return refreshTokens.deleteExpiredBefore(before);
    }

    /**
     * The account's live sessions, newest first — one row per rotation family, because
     * rotation revokes the predecessor and so a family has exactly one usable token.
     */
    @Transactional(readOnly = true)
    public java.util.List<RefreshToken> activeSessions(java.util.UUID userId) {
        return refreshTokens.findActiveByUser(userId, Instant.now());
    }

    /**
     * Ends one session the user picked out of their own list.
     *
     * <p>Keyed on the family rather than the token row: that is what a user thinks of as "a
     * session", it survives every rotation, and it is therefore the only id stable enough to
     * put in a response and accept back. The user id is part of the statement, so one
     * account cannot end another's session by guessing a family id.
     *
     * @throws ApiException 404 if the family is not a live session of this account
     */
    @Transactional
    public void revokeOwnSession(java.util.UUID userId, java.util.UUID familyId) {
        int revoked = refreshTokens.revokeFamilyForUser(familyId, userId,
                TokenRevocationReason.USER_REVOKED, Instant.now());
        if (revoked == 0) {
            throw ApiException.notFound("Session");
        }
        log.info("User {} ended session {}", userId, familyId);
    }

    private IssuedToken persist(AppUser user, java.util.UUID familyId, SessionContext context) {
        String value = generateToken();
        Instant expiresAt = Instant.now().plus(properties.refreshTokenTtl());

        RefreshToken token = RefreshToken.issue(user, sha256(value), familyId, expiresAt);
        token.setDeviceLabel(context.deviceLabel());
        token.setUserAgent(truncate(context.userAgent()));
        token.setIpAddress(context.ipAddress());
        refreshTokens.save(token);

        return new IssuedToken(value, expiresAt, token.getFamilyId());
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        return com.autox.common.Sha256.hex(value);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    public record IssuedToken(String value, Instant expiresAt, java.util.UUID familyId) {
    }

    public record Rotation(AppUser user, IssuedToken refreshToken) {
    }

    /** Where a session was created from. Recorded so a user can review their sessions. */
    public record SessionContext(String deviceLabel, String userAgent, String ipAddress) {

        public static SessionContext empty() {
            return new SessionContext(null, null, null);
        }
    }
}
