package com.autox.listing.report;

import com.autox.user.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reporting a listing to the moderators. Signed in only. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Listings")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/listings/{listingId}/report")
public class ListingReportController {

    private final ListingReportService reports;

    public ListingReportController(ListingReportService reports) {
        this.reports = reports;
    }

    /** Whether the caller has reported a listing. */
    public record ReportStatus(boolean reported) {
    }

    /**
     * Reports a listing to the moderators: a listing the public can see, not one's own, with a
     * verified address. 409 {@code LISTING_ALREADY_REPORTED} the second time; 429 past five an hour.
     */
    @PostMapping
    public ResponseEntity<Void> reportListing(@AuthenticationPrincipal Jwt token, @PathVariable UUID listingId,
                                              @Valid @RequestBody ReportListingRequest request) {
        reports.report(CurrentUser.id(token), listingId, request);
        return ResponseEntity.noContent().build();
    }

    /** Whether the caller has already reported it, so the page can say so instead of offering it again. */
    @GetMapping
    public ReportStatus listingReportStatus(@AuthenticationPrincipal Jwt token, @PathVariable UUID listingId) {
        return new ReportStatus(reports.reportedBy(CurrentUser.id(token), listingId));
    }
}
