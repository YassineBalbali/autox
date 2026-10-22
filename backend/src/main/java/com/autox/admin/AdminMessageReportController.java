package com.autox.admin;

import com.autox.api.PageResponse;
import com.autox.messaging.MessagingService;
import com.autox.messaging.dto.ReportedMessage;
import com.autox.user.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reported messages. The queue shows each with the messages around it; there is no endpoint
 * for reading a conversation nobody reported.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Admin", description = "Moderation. Requires the ADMIN role.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/message-reports")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AdminMessageReportController {

    private static final int MAX_PAGE_SIZE = 50;

    private final MessagingService messaging;

    public AdminMessageReportController(MessagingService messaging) {
        this.messaging = messaging;
    }

    /** Open reports, oldest first. */
    @GetMapping
    public PageResponse<ReportedMessage> openReports(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return messaging.openReports(page, size);
    }

    /** Hides the message from both sides (its text is kept) and closes its reports. 409 if already dealt with. */
    @PostMapping("/{id}/hide")
    public ResponseEntity<Void> hide(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        messaging.hide(CurrentUser.id(token), id);
        return ResponseEntity.noContent().build();
    }

    /** Closes the report and leaves the message as it is. 409 if already dealt with. */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        messaging.dismiss(CurrentUser.id(token), id);
        return ResponseEntity.noContent().build();
    }
}
