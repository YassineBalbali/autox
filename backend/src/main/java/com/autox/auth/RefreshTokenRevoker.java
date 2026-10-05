package com.autox.auth;

import com.autox.user.RefreshTokenRepository;
import com.autox.user.TokenRevocationReason;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes tokens in a transaction of its own.
 *
 * <p>This exists because of an ordering trap: detecting a replayed refresh token must
 * both revoke the family <em>and</em> fail the request. Doing the revocation in the
 * caller's transaction and then throwing rolls the revocation back — the attacker
 * gets an error, and the stolen session stays alive. {@code REQUIRES_NEW} commits the
 * revocation before the caller's exception unwinds.
 *
 * <p>It is a separate bean rather than a method on {@link RefreshTokenService} because
 * Spring's transaction proxy does not intercept self-invocation, so an annotation on a
 * sibling method would be silently ignored.
 */
@Component
public class RefreshTokenRevoker {

    private final RefreshTokenRepository refreshTokens;

    public RefreshTokenRevoker(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamilyNow(UUID familyId, TokenRevocationReason reason) {
        return refreshTokens.revokeFamily(familyId, reason, Instant.now());
    }
}
