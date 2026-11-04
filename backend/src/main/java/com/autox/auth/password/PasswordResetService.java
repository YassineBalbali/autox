package com.autox.auth.password;

import com.autox.api.error.ApiException;
import com.autox.auth.RefreshTokenService;
import com.autox.common.Sha256;
import com.autox.user.AppUser;
import com.autox.user.TokenRevocationReason;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgotten-password recovery.
 *
 * <p>The design is shaped by one rule: <b>this must not reveal whether an account exists.</b>
 * The request endpoint is unauthenticated and anyone can post any address to it, so a
 * response that differed for a registered address would hand out a list of the site's
 * users. {@link #requestReset} therefore answers identically in every case — unknown
 * address, suspended account, mail provider down — and the only thing that varies is
 * whether an email is written to the outbox.
 *
 * <p>That is also why the throttle counts <em>before</em> the account lookup: a cap that
 * only applied to real accounts would leak existence through the 429.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetTokenIssuer issuer;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetThrottle throttle;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;

    public PasswordResetService(PasswordResetTokenIssuer issuer,
                                PasswordResetTokenRepository tokens,
                                PasswordResetThrottle throttle,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokens) {
        this.issuer = issuer;
        this.tokens = tokens;
        this.throttle = throttle;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
    }

    /**
     * Issues a reset link, if there is an account that can use one.
     *
     * <p>The issuer writes the token and its email in one transaction, and the poller sends it
     * afterwards, so a slow mail provider never holds up the caller, and the time this takes
     * says nothing about whether the address has an account.
     *
     * @throws ApiException 429 if this account or this caller has asked too often
     */
    public void requestReset(String submittedEmail, String ipAddress) {
        PasswordResetThrottle.Decision decision = throttle.record(submittedEmail, ipAddress);
        if (!decision.allowed()) {
            throw ApiException.rateLimited(decision.retryAfterSeconds());
        }

        if (!issuer.issue(submittedEmail, ipAddress)) {
            // Nothing to do, and nothing to say: the caller gets the same answer either way.
            log.info("Password reset requested for an address with no eligible account");
        }
    }

    /**
     * Spends a reset token and sets the new password.
     *
     * <p>Every existing session is revoked. A reset is the answer to "someone else may have
     * my password", so leaving the other sessions alive would defeat the point — including
     * the attacker's, if there is one. The user signs in again afterwards, on every device.
     *
     * @throws ApiException 400 if the token is unknown, expired, already used or superseded
     */
    @Transactional
    public void resetPassword(String presentedToken, String newPassword) {
        PasswordResetToken token = tokens.findByTokenHash(Sha256.hex(presentedToken))
                .orElseThrow(ApiException::invalidResetToken);

        Instant now = Instant.now();
        AppUser user = token.getUser();
        if (!token.isUsable(now) || !PasswordResetTokenIssuer.isResettable(user)) {
            throw ApiException.invalidResetToken();
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        token.markUsed(now);
        refreshTokens.revokeAllSessions(user.getId(), TokenRevocationReason.PASSWORD_CHANGED);

        log.info("Password reset completed for user {}; all sessions revoked", user.getId());
    }

    /** Housekeeping, called by the maintenance sweeper. */
    @Transactional
    public int purgeExpiredTokens(Instant before) {
        return tokens.deleteExpiredBefore(before);
    }
}
