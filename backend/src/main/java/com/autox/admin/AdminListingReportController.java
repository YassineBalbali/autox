package com.autox.admin;

import com.autox.api.PageResponse;
import com.autox.listing.report.ListingReportService;
import com.autox.listing.report.ReportedListing;
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

/** Reported listings, beside the reported messages: the same queue, the same two decisions. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Admin", description = "Moderation. Requires the ADMIN role.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/listing-reports")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AdminListingReportController {

    private static final int MAX_PAGE_SIZE = 50;

    private final ListingReportService reports;

    public AdminListingReportController(ListingReportService reports) {
        this.reports = reports;
    }

    /** Open reports on listings, oldest first. */
    @GetMapping
    public PageResponse<ReportedListing> openListingReports(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return reports.open(page, size);
    }

    /** Closes the reports on the listing and leaves it online. 409 if already dealt with. */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismissListingReport(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        reports.dismiss(CurrentUser.id(token), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Takes the listing down, recorded as a moderation decision with the report's reason, and closes
     * its reports. 409 if already dealt with, or if the listing is no longer online.
     */
    @PostMapping("/{id}/remove")
    public ResponseEntity<Void> removeReportedListing(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        reports.remove(CurrentUser.id(token), id);
        return ResponseEntity.noContent().build();
    }
}
