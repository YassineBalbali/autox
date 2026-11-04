package com.autox.auth.verification;

import com.autox.api.error.ApiException;
import com.autox.common.Sha256;
import com.autox.mail.EmailOutbox;
import com.autox.mail.EmailTemplates;
import com.autox.mail.EmailType;
import com.autox.mail.MailProperties;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.UserStatus;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proving that an account's address is its owner's.
 *
 * <p>A link is sent on sign-up, and on request from the account page. Opening it verifies the
 * address and makes the account ACTIVE. What an unverified account cannot do is decided by
 * {@link #requireVerified}, which reads the account rather than the access token: a token outlives
 * the click by up to its lifetime, and could say "unverified" for fifteen minutes after it.
 *
 * <p>The page behind the link posts the token; a GET spends nothing, so a mail scanner fetching
 * the link does not use it up.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int TOKEN_BYTES = 32;

    /** The Angular route behind the link. */
    private static final String PAGE = "/verifier-email";

    private final EmailVerificationTokenRepository tokens;
    private final AppUserRepository users;
    private final EmailVerificationThrottle throttle;
    private final EmailVerificationProperties properties;
    private final EmailOutbox outbox;
    private final MailProperties mail;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailVerificationTokenRepository tokens,
                                    AppUserRepository users,
                                    EmailVerificationThrottle throttle,
                                    EmailVerificationProperties properties,
                                    EmailOutbox outbox,
                                    MailProperties mail) {
        this.tokens = tokens;
        this.users = users;
        this.throttle = throttle;
        this.properties = properties;
        this.outbox = outbox;
        this.mail = mail;
    }

    /** Writes a new link and its email in the caller's transaction, replacing any earlier link. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(AppUser user) {
        Instant now = Instant.now();
        tokens.supersedeOutstanding(user.getId(), now);

        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(EmailVerificationToken.issue(user, Sha256.hex(value), now.plus(properties.ttl())));

        String link = mail.siteUrl() + PAGE + "?token=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        outbox.enqueue(new EmailOutbox.Draft(user.getEmail(), EmailType.EMAIL_VERIFICATION,
                EmailTemplates.emailVerification(link, properties.ttl().toHours())));
    }

    /** "Renvoyer le lien". An address that is already verified gets nothing, and no error. */
    @Transactional
    public void resend(UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User " + userId));
        if (user.isEmailVerified()) {
            return;
        }
        long retryAfter = throttle.record(userId);
        if (retryAfter > 0) {
            throw ApiException.rateLimited(retryAfter);
        }
        issue(user);
    }

    /**
     * Spends a link. A suspended account's address is verified but its status stays, and
     * reactivation works out the rest.
     *
     * @throws ApiException 400 if the link is unknown, expired, spent or replaced
     */
    @Transactional
    public void verify(String presentedToken) {
        Instant now = Instant.now();
        EmailVerificationToken token = tokens.findByTokenHash(Sha256.hex(presentedToken))
                .filter(found -> found.isUsable(now))
                .orElseThrow(ApiException::invalidVerificationToken);
        AppUser user = token.getUser();
        if (user.getDeletedAt() != null || user.getStatus() == UserStatus.DELETED
                || tokens.markUsed(token.getId(), now) == 0) {
            throw ApiException.invalidVerificationToken();
        }

        user.setEmailVerifiedAt(now);
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        log.info("Address verified for user {}", user.getId());
    }

    /** Refuses an account held back until its address is verified. */
    @Transactional(readOnly = true)
    public void requireVerified(UUID userId) {
        users.findById(userId)
                .filter(AppUser::isEmailVerificationPending)
                .ifPresent(pending -> {
                    throw ApiException.emailNotVerified();
                });
    }

    /** Housekeeping, called by the maintenance sweeper. */
    @Transactional
    public int purgeExpiredTokens(Instant before) {
        return tokens.deleteExpiredBefore(before);
    }
}
