package com.autox.auth.password;

import com.autox.api.error.ApiException;
import com.autox.auth.AccessTokenService;
import com.autox.auth.RefreshTokenService;
import com.autox.auth.dto.AuthResponse;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.TokenRevocationReason;
import com.autox.user.dto.UserSummary;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing the password of a signed-in account.
 *
 * <p>The current password is required even though the caller already holds a valid access
 * token. A token can be a stolen one, and the single most valuable thing an attacker can do
 * with fifteen minutes of access is take the account permanently — which is exactly what
 * changing the password without proving knowledge of the old one would allow.
 *
 * <p><b>Every session is replaced, including the caller's.</b> The obvious reading is "end
 * the other sessions and leave mine", but an access token carries no reference to the
 * refresh family it was issued alongside, so the server cannot tell which session is
 * calling. The options were to have the client send its refresh token to a profile
 * endpoint, or to revoke the lot and hand back a fresh pair. The second is simpler, has no
 * partial-trust branch, and gives the same guarantee: when this returns, the only session
 * that works anywhere is the one in the response.
 */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;

    public PasswordChangeService(AppUserRepository users,
                                 PasswordEncoder passwordEncoder,
                                 AccessTokenService accessTokens,
                                 RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    /**
     * @return a new token pair; the one the caller used to get here no longer refreshes
     * @throws ApiException 400 if {@code currentPassword} is wrong or the new one is the same
     */
    @Transactional
    public AuthResponse changePassword(UUID callerId, String currentPassword, String newPassword,
                                       RefreshTokenService.SessionContext context) {

        AppUser user = users.findActiveById(callerId).orElseThrow(() -> ApiException.notFound("User"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            // Not throttled here: the caller is already authenticated, so this is not a
            // guessing surface — getting this far needs a valid token for the account.
            throw ApiException.currentPasswordIncorrect();
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.validationFailed("The new password must be different from the current one.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.saveAndFlush(user);

        // The bulk revocation clears the persistence context, which detaches everything
        // loaded above — hence the flush before it and the reload after. Writing the new
        // session against a detached user would be the kind of bug that only shows up
        // under a Hibernate version change.
        int revoked = refreshTokens.revokeAllSessions(callerId, TokenRevocationReason.PASSWORD_CHANGED);
        AppUser reloaded = users.findActiveById(callerId).orElseThrow(() -> ApiException.notFound("User"));

        AccessTokenService.AccessToken access = accessTokens.issue(reloaded);
        RefreshTokenService.IssuedToken refresh = refreshTokens.issueNewSession(reloaded, context);

        log.info("Password changed for user {}; {} session(s) revoked and one re-established",
                callerId, revoked);
        return AuthResponse.of(access.value(), access.expiresInSeconds(), access.expiresAt(),
                refresh.value(), refresh.expiresAt(), UserSummary.from(reloaded));
    }
}
