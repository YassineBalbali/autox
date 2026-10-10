package com.autox.user;

import com.autox.user.dto.UpdateProfileRequest;
import com.autox.user.dto.UserProfileResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    public UserController(UserService userService) {
        this.userService = userService;
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
     * Partial update: only the fields present in the body are changed. Email, role and
     * password are not editable here — changing those is a separate flow with its own
     * verification, and neither exists yet.
     */
    @PatchMapping
    public UserProfileResponse updateOwnProfile(@AuthenticationPrincipal Jwt token,
                                                @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(CurrentUser.id(token), request);
    }
}
