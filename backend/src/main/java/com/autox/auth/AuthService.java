package com.autox.auth;

import com.autox.api.error.ApiException;
import com.autox.auth.dto.AuthResponse;
import com.autox.auth.dto.LoginRequest;
import com.autox.auth.dto.RegisterRequest;
import com.autox.auth.throttle.LoginThrottleService;
import com.autox.reference.Country;
import com.autox.reference.CountryRepository;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.ProProfile;
import com.autox.user.ProProfileRepository;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.autox.user.dto.UserSummary;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, refresh and logout.
 *
 * <p>Credentials are verified here rather than through an {@code AuthenticationManager}:
 * with a single credential type and no session, the provider chain adds indirection
 * without adding behaviour. Authorisation of subsequent requests is still entirely
 * Spring Security's, driven by the access token.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A BCrypt hash of a throwaway value, used to spend the same time on a login for
     * an unknown email as for a known one. Without it, response time tells an
     * attacker which addresses are registered.
     */
    private final String dummyHash;

    private final AppUserRepository users;
    private final ProProfileRepository proProfiles;
    private final CountryRepository countries;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final LoginThrottleService loginThrottle;

    public AuthService(AppUserRepository users,
                       ProProfileRepository proProfiles,
                       CountryRepository countries,
                       PasswordEncoder passwordEncoder,
                       AccessTokenService accessTokens,
                       RefreshTokenService refreshTokens,
                       LoginThrottleService loginThrottle) {
        this.users = users;
        this.proProfiles = proProfiles;
        this.countries = countries;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.loginThrottle = loginThrottle;
        this.dummyHash = passwordEncoder.encode("unused-placeholder-for-constant-time-login");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, RefreshTokenService.SessionContext context) {
        String email = request.email().trim();

        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.emailAlreadyRegistered();
        }

        Country country = countries.findEnabledByCode(request.countryCode())
                .orElseThrow(() -> ApiException.validationFailed(
                        "countryCode '" + request.countryCode() + "' is not an enabled market."));

        String locale = request.locale() != null ? request.locale() : country.getDefaultLocale();

        AppUser user = AppUser.register(email, passwordEncoder.encode(request.password()),
                request.role(), request.displayName().trim(), country, locale);
        user.setFirstName(trimToNull(request.firstName()));
        user.setLastName(trimToNull(request.lastName()));
        user.setPhoneE164(trimToNull(request.phone()));

        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException raced) {
            // Two registrations for the same address in flight at once: the unique
            // index is the authority, not the check above.
            throw ApiException.emailAlreadyRegistered();
        }

        if (request.role() == UserRole.PROFESSIONNEL) {
            proProfiles.save(ProProfile.forUser(user,
                    request.companyName().trim(), request.registrationNumber().trim()));
        }

        log.info("Registered user {} with role {}", user.getId(), user.getRole());
        return issueTokens(user, context);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, RefreshTokenService.SessionContext context) {
        String email = request.email().trim();

        // Before any lookup or hashing: a throttled attempt must cost us nothing.
        loginThrottle.checkAllowed(email, context.ipAddress());

        AppUser user = users.findActiveByEmail(email).orElse(null);

        if (user == null) {
            // Hash anyway, so timing does not reveal whether the account exists.
            passwordEncoder.matches(request.password(), dummyHash);
            loginThrottle.recordFailure(email, context.ipAddress());
            throw ApiException.invalidCredentials();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginThrottle.recordFailure(email, context.ipAddress());
            throw ApiException.invalidCredentials();
        }

        requireLoginAllowed(user);

        // Only a genuine sign-in clears the counter.
        loginThrottle.recordSuccess(email);
        user.setLastLoginAt(Instant.now());

        RefreshTokenService.SessionContext labelled = new RefreshTokenService.SessionContext(
                request.deviceLabel(), context.userAgent(), context.ipAddress());
        return issueTokens(user, labelled);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken, RefreshTokenService.SessionContext context) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken, context);
        AppUser user = rotation.user();

        // A session must not outlive the account's right to use it.
        requireLoginAllowed(user);

        AccessTokenService.AccessToken access = accessTokens.issue(user);
        return AuthResponse.of(access.value(), access.expiresInSeconds(), access.expiresAt(),
                rotation.refreshToken().value(), rotation.refreshToken().expiresAt(), UserSummary.from(user));
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.revokeSession(refreshToken);
    }

    private AuthResponse issueTokens(AppUser user, RefreshTokenService.SessionContext context) {
        AccessTokenService.AccessToken access = accessTokens.issue(user);
        RefreshTokenService.IssuedToken refresh = refreshTokens.issueNewSession(user, context);

        return AuthResponse.of(access.value(), access.expiresInSeconds(), access.expiresAt(),
                refresh.value(), refresh.expiresAt(), UserSummary.from(user));
    }

    /**
     * An unverified email is allowed to sign in and manage its profile; the
     * verification requirement attaches to publishing a listing instead. Suspended
     * and closed accounts are refused outright.
     */
    private void requireLoginAllowed(AppUser user) {
        if (user.getDeletedAt() != null || user.getStatus() == UserStatus.DELETED) {
            throw ApiException.accountClosed();
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.accountSuspended();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
