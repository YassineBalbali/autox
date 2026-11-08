package com.autox.privacy;

import com.autox.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The account owner's rights over their data: a copy of it, and deleting the account. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Profile", description = "The signed-in account. Requires authentication.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
public class PrivacyController {

    private final AccountDeletionService deletion;
    private final DataExportService export;

    public PrivacyController(AccountDeletionService deletion, DataExportService export) {
        this.deletion = deletion;
        this.export = export;
    }

    /**
     * @param password the account's password, again: a valid token may be a stolen one, and this
     *                 cannot be undone
     */
    public record DeleteAccountRequest(@NotBlank @Size(max = 128) String password) {
    }

    /**
     * Deletes the account, for good. Its listings and their photos, saved searches, sessions and
     * personal data go; in conversations its messages stay under "Compte supprimé", without their
     * text. A confirmation e-mail is sent to the address. 400 {@code CURRENT_PASSWORD_INCORRECT};
     * 409 {@code DEMO_ACCOUNT_CANNOT_BE_DELETED}, or {@code LAST_ADMIN} for the last administrator
     * able to sign in.
     */
    @PostMapping("/deletion")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal Jwt token,
                                              @Valid @RequestBody DeleteAccountRequest request) {
        deletion.delete(CurrentUser.id(token), request.password());
        return ResponseEntity.noContent().build();
    }

    /**
     * Everything AutoX holds about the account, as a JSON file: the account and company, listings
     * with photos and price history, conversations, saved searches, sessions. Three an hour; past
     * that, 429 {@code RATE_LIMITED}.
     */
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> exportData(@AuthenticationPrincipal Jwt token) {
        export.allow(CurrentUser.id(token));
        String name = "autox-mes-donnees-" + LocalDate.now(ZoneId.of("Europe/Paris")) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(export.export(CurrentUser.id(token)));
    }
}
