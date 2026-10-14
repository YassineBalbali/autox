package com.autox.auth;

import com.autox.auth.dto.AuthResponse;
import com.autox.auth.dto.ForgotPasswordRequest;
import com.autox.auth.dto.LoginRequest;
import com.autox.auth.dto.RefreshRequest;
import com.autox.auth.dto.RegisterRequest;
import com.autox.auth.dto.ResetPasswordRequest;
import com.autox.auth.password.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. Everything here is callable without credentials, and
 * everything returns the same token shape, so a mobile client has one code path.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Authentication", description = "Register, sign in, rotate tokens, sign out. No credentials needed to call these.")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordReset;

    public AuthController(AuthService authService, PasswordResetService passwordReset) {
        this.authService = authService;
        this.passwordReset = passwordReset;
    }

    /**
     * Creates an account and signs it in, returning the same token pair as login — so a
     * client never has to follow registration with a second call.
     *
     * <p>{@code role} chooses PARTICULIER or PROFESSIONNEL; a professional must also send
     * {@code companyName} and {@code registrationNumber}. ADMIN cannot be self-assigned.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest http) {
        AuthResponse response = authService.register(request, sessionContext(http, null));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Exchanges credentials for a short-lived access token and a long-lived refresh token.
     *
     * <p>A wrong password and an unknown address both answer 401 {@code INVALID_CREDENTIALS},
     * deliberately indistinguishable. Repeated failures answer 429 {@code TOO_MANY_ATTEMPTS}
     * with {@code Retry-After}; the delay is progressive and capped, so failing on purpose
     * cannot lock the real owner out.
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, sessionContext(http, request.deviceLabel()));
    }

    /**
     * Exchanges a refresh token for a new pair. The old refresh token is invalid
     * afterwards — clients must store the one returned here.
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(request.refreshToken(), sessionContext(http, null));
    }

    /**
     * Asks for a password reset link, which is emailed to the address if it belongs to an
     * account that can use one.
     *
     * <p>Always 202, and always the same body, whether or not the address is registered:
     * anything else would turn this into a way to find out who has an account here. The
     * caller therefore cannot tell from the response that anything was sent. Repeated
     * requests answer 429 {@code RATE_LIMITED} — the address does not have to be one the
     * caller owns, so the endpoint is capped both per account and per caller.
     */
    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest http) {
        passwordReset.requestReset(request.email(), clientIp(http));
        return ResponseEntity.accepted().build();
    }

    /**
     * Sets a new password using the token from a reset link.
     *
     * <p>The link works once and only for a short window. Every way it can fail — unknown,
     * expired, already spent, replaced by a newer request — answers 400
     * {@code INVALID_RESET_TOKEN}, because a legitimate user does the same thing in all
     * four cases: ask for a new link.
     *
     * <p>A successful reset ends every session on every device. The user signs in again.
     */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Ends this device's session. Idempotent: an unknown token still returns 204. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private static RefreshTokenService.SessionContext sessionContext(HttpServletRequest http, String deviceLabel) {
        return new RefreshTokenService.SessionContext(deviceLabel, http.getHeader("User-Agent"), clientIp(http));
    }

    /**
     * Behind a load balancer the socket address is the proxy's. With
     * {@code server.forward-headers-strategy=native}, Tomcat's RemoteIpValve has already
     * replaced it with the client named by a trusted proxy header, so {@code getRemoteAddr()}
     * is the real client.
     */
    private static String clientIp(HttpServletRequest http) {
        String address = http.getRemoteAddr();
        return address == null || address.isBlank() ? null : address;
    }
}
