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
@RestController
@RequestMapping("/api/v1/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserProfileResponse getOwnProfile(@AuthenticationPrincipal Jwt token) {
        return userService.getProfile(CurrentUser.id(token));
    }

    @PatchMapping
    public UserProfileResponse updateOwnProfile(@AuthenticationPrincipal Jwt token,
                                                @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(CurrentUser.id(token), request);
    }
}
