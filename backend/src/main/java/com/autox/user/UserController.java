package com.autox.user;

import com.autox.auth.RefreshTokenService;
import com.autox.auth.dto.AuthResponse;
import com.autox.auth.dto.ChangePasswordRequest;
import com.autox.auth.password.PasswordChangeService;
import com.autox.user.dto.UpdateProfileRequest;
import com.autox.user.dto.UserProfileResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own profile. There is deliberately no "get user by id" here:
 * a public seller profile is a different resource with a different, narrower shape,
 * and it belongs with listings.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Profile", description = "The signed-in account. Requires authentication.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
public class UserController {

    private final UserService userService;
    private final PasswordChangeService passwordChange;

    public UserController(UserService userService, PasswordChangeService passwordChange) {
        this.userService = userService;
        this.passwordChange = passwordChange;
    }

    /**
     * The signed-in account: contact details, role, country, locale and — for a
     * PROFESSIONNEL — the company profile. Never another user's; the id comes from the
     * token, not from the request.
     */
    @GetMapping
    public UserProfileResponse getOwnProfile(@AuthenticationPrincipal Jwt token) {
        return userService.getProfile(CurrentUser.id(token));
    }

    /**
     * Partial update: only the fields present in the body are changed. Email and role are
     * not editable here — changing an address needs to prove control of the new one, and
     * changing a role is a commercial decision, not a profile edit. The password has its
     * own endpoint below.
     */
    @PatchMapping
    public UserProfileResponse updateOwnProfile(@AuthenticationPrincipal Jwt token,
                                                @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(CurrentUser.id(token), request);
    }

    /**
     * Changes the password. Requires the current one, even though the request is already
     * authenticated: a valid token may be a stolen one, and taking over the account
     * permanently is the most valuable thing that can be done with it.
     *
     * <p><b>Returns a new token pair, and invalidates every existing session including the
     * caller's.</b> The client must store the tokens from this response; the refresh token
     * it arrived with no longer works. An access token carries no reference to the session
     * it was issued with, so "all sessions but this one" is not something the server can
     * express — replacing the lot is the version of that guarantee it can actually keep.
     */
    @PostMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal Jwt token,
                                       @Valid @RequestBody ChangePasswordRequest request,
                                       HttpServletRequest http) {
        RefreshTokenService.SessionContext context = new RefreshTokenService.SessionContext(
                request.deviceLabel(), http.getHeader("User-Agent"), http.getRemoteAddr());
        return passwordChange.changePassword(CurrentUser.id(token),
                request.currentPassword(), request.newPassword(), context);
    }
}
