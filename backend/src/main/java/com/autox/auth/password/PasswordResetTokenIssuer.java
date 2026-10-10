package com.autox.auth.password;

import com.autox.common.Sha256;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.UserStatus;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the reset token row, in its own bean and therefore its own transaction.
 *
 * <p>Separate from {@link PasswordResetService} on purpose. The service has to commit the
 * token and <em>then</em> send the email, and a {@code @Transactional} method called from a
 * sibling method of the same class is not proxied — it would silently run with no
 * transaction at all. This codebase has been bitten by self-invocation enough times to make
 * the boundary a class boundary.
 */
@Service
public class PasswordResetTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenIssuer.class);
    private static final int TOKEN_BYTES = 32;

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetProperties properties;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetTokenIssuer(AppUserRepository users,
                                    PasswordResetTokenRepository tokens,
                                    PasswordResetProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.properties = properties;
    }

    /**
     * @return the token and what is needed to email it, or empty if no account should get one
     */
    @Transactional
    public Optional<Issued> issue(String submittedEmail, String ipAddress) {
        AppUser user = users.findActiveByEmail(submittedEmail.trim()).orElse(null);
        if (user == null || !isResettable(user)) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        // Only the newest link works. Otherwise clicking "forgot password" three times
        // leaves three live keys to the account sitting in three inboxes.
        tokens.supersedeOutstanding(user.getId(), now);

        String value = generateToken();
        tokens.save(PasswordResetToken.issue(user, Sha256.hex(value), now.plus(properties.ttl()), ipAddress));

        log.info("Issued a password reset token for user {}, valid for {}s",
                user.getId(), properties.ttl().toSeconds());
        return Optional.of(new Issued(user.getEmail(), user.getLocale(), value));
    }

    /**
     * A suspended or closed account is not sent a reset link. It could not sign in with the
     * new password anyway, and mailing one invites a support conversation that ends in "your
     * account is suspended" — which is something to say directly, not through a password
     * form. An unverified account <em>is</em> eligible: it can already sign in, so being
     * locked out of it is just as much of a dead end.
     */
    static boolean isResettable(AppUser user) {
        return user.getDeletedAt() == null
                && user.getStatus() != UserStatus.DELETED
                && user.getStatus() != UserStatus.SUSPENDED;
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What the caller needs to send the email: not the entity, which is detached by then. */
    public record Issued(String email, String locale, String token) {
    }
}
