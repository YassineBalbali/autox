package com.autox.auth.password;

import com.autox.api.error.ApiException;
import com.autox.auth.RefreshTokenService;
import com.autox.common.Sha256;
import com.autox.mail.EmailMessage;
import com.autox.mail.EmailSender;
import com.autox.mail.MailProperties;
import com.autox.user.AppUser;
import com.autox.user.TokenRevocationReason;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
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
 * whether an email is actually sent.
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
    private final PasswordResetProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final EmailSender email;
    private final MailProperties mail;

    public PasswordResetService(PasswordResetTokenIssuer issuer,
                                PasswordResetTokenRepository tokens,
                                PasswordResetThrottle throttle,
                                PasswordResetProperties properties,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokens,
                                EmailSender email,
                                MailProperties mail) {
        this.issuer = issuer;
        this.tokens = tokens;
        this.throttle = throttle;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.email = email;
        this.mail = mail;
    }

    /**
     * Issues a reset link, if there is an account that can use one.
     *
     * <p>Deliberately not transactional here. The token is committed by the issuer and the
     * email goes out afterwards, so a slow mail provider never holds a database connection
     * open and a token is never emailed that a later rollback would have invalidated.
     *
     * @throws ApiException 429 if this account or this caller has asked too often
     */
    public void requestReset(String submittedEmail, String ipAddress) {
        PasswordResetThrottle.Decision decision = throttle.record(submittedEmail, ipAddress);
        if (!decision.allowed()) {
            throw ApiException.rateLimited(decision.retryAfterSeconds());
        }

        Optional<PasswordResetTokenIssuer.Issued> issued = issuer.issue(submittedEmail, ipAddress);
        if (issued.isEmpty()) {
            // Nothing to do, and nothing to say: the caller gets the same answer either way.
            log.info("Password reset requested for an address with no eligible account");
            return;
        }

        email.send(resetEmail(issued.get()));
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

    private EmailMessage resetEmail(PasswordResetTokenIssuer.Issued issued) {
        String link = mail.passwordResetUrl() + "?token="
                + URLEncoder.encode(issued.token(), StandardCharsets.UTF_8);

        // English, and not localised: there is no message-bundle infrastructure, and a
        // half-built one would be worse than an obvious gap. The recipient's locale travels
        // with the message, so adding templates later changes the sender, not this.
        String body = """
                Someone asked to reset the password for your AutoX account.

                To choose a new one, open this link within %d minutes:

                %s

                The link works once. If you did not ask for this you can ignore this email —
                your password has not been changed."""
                .formatted(properties.ttl().toMinutes(), link);

        // The hash of the token doubles as the idempotency key: unique per request, and
        // already not a credential, so it is safe to hand to a provider and to log.
        return new EmailMessage(issued.email(), "Reset your AutoX password", body,
                issued.locale(), Sha256.hex(issued.token()));
    }
}
