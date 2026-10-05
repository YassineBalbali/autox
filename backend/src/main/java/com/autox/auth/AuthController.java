package com.autox.auth;

import com.autox.auth.dto.AuthResponse;
import com.autox.auth.dto.LoginRequest;
import com.autox.auth.dto.RefreshRequest;
import com.autox.auth.dto.RegisterRequest;
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
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest http) {
        AuthResponse response = authService.register(request, sessionContext(http, null));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

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
     * Behind a load balancer the socket address is the proxy's.
     * {@code server.forward-headers-strategy=framework} makes Spring honour
     * {@code X-Forwarded-For}, so {@code getRemoteAddr()} is already the real client.
     */
    private static String clientIp(HttpServletRequest http) {
        String address = http.getRemoteAddr();
        return address == null || address.isBlank() ? null : address;
    }
}
