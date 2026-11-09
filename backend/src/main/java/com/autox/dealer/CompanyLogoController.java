package com.autox.dealer;

import com.autox.listing.dto.ImageUploadRequest;
import com.autox.listing.dto.ImageUploadTicket;
import com.autox.user.CurrentUser;
import com.autox.user.UserService;
import com.autox.user.dto.UserProfileResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A dealer's logo, in three steps like a listing photo: ask for a slot, PUT the file to the URL it
 * gives, confirm. The confirmation answers with the profile, which carries the new logo's URL.
 */
@RestController
@RequestMapping("/api/v1/me/company/logo")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Profile", description = "The signed-in account. Requires authentication.")
public class CompanyLogoController {

    private final CompanyLogoService logos;
    private final UserService users;

    public CompanyLogoController(CompanyLogoService logos, UserService users) {
        this.logos = logos;
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<ImageUploadTicket> requestLogoUpload(@AuthenticationPrincipal Jwt token,
                                                               @Valid @RequestBody ImageUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(logos.requestUpload(CurrentUser.id(token), request));
    }

    @PostMapping("/{uploadId}/confirm")
    public UserProfileResponse confirmLogoUpload(@AuthenticationPrincipal Jwt token, @PathVariable UUID uploadId) {
        UUID userId = CurrentUser.id(token);
        logos.confirm(userId, uploadId);
        return users.getProfile(userId);
    }

    @DeleteMapping
    public UserProfileResponse removeLogo(@AuthenticationPrincipal Jwt token) {
        UUID userId = CurrentUser.id(token);
        logos.remove(userId);
        return users.getProfile(userId);
    }
}
