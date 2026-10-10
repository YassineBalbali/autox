package com.autox.user;

import com.autox.auth.RefreshTokenService;
import com.autox.user.dto.SessionResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in account's own sessions.
 *
 * <p>This exists because a refresh token lives for ninety days on a device that may be
 * lost, sold or stolen, and until now there was nothing a user could do about one but
 * change their password. The data was already being recorded for exactly this purpose —
 * {@code refresh_token} has held the device label, user agent and address since the auth
 * work; nothing read it back.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Sessions",
        description = "List and end the signed-in account's own sessions. Requires authentication.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me/sessions")
public class SessionController {

    private final RefreshTokenService refreshTokens;

    public SessionController(RefreshTokenService refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /**
     * Every live session, newest first.
     *
     * <p>Not paginated, and the one list endpoint in the API that is not. The bound is how
     * many devices one person signs in from, which is single digits: a page parameter here
     * would be ceremony around a list that cannot grow.
     *
     * <p>The response cannot say which entry is the caller's own. An access token carries
     * no reference to the refresh family it was issued with, so a client that wants to mark
     * one as "this device" should match on the {@code deviceLabel} it chose at sign-in.
     */
    @GetMapping
    public List<SessionResponse> listOwnSessions(@AuthenticationPrincipal Jwt token) {
        return refreshTokens.activeSessions(CurrentUser.id(token)).stream()
                .map(SessionResponse::from)
                .toList();
    }

    /**
     * Ends one session. The device holding it can no longer refresh, and its access token
     * stops working when it expires — within fifteen minutes, which is the cost of a
     * stateless API and the reason that lifetime is short.
     *
     * <p>A session belonging to someone else, or one already ended, answers 404: there is
     * nothing to be learned here by guessing identifiers.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revokeOwnSession(@AuthenticationPrincipal Jwt token,
                                                 @PathVariable UUID sessionId) {
        refreshTokens.revokeOwnSession(CurrentUser.id(token), sessionId);
        return ResponseEntity.noContent().build();
    }
}
