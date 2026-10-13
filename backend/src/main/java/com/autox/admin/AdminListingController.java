package com.autox.admin;

import com.autox.api.PageResponse;
import com.autox.listing.ListingModerationService;
import com.autox.listing.ModerationView;
import com.autox.listing.dto.ModeratedListing;
import com.autox.listing.dto.ModerationDecisionRequest;
import com.autox.listing.dto.ModerationNoteRequest;
import com.autox.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listing moderation. Administrators only.
 *
 * <p>Guarded twice on purpose, like {@link AdminUserController}: by path in
 * {@code SecurityConfig} and by {@code @PreAuthorize} here, so that moving the controller
 * or adding a mapping cannot silently unguard it.
 *
 * <p>Each decision is its own endpoint rather than a field on an update, for the reason
 * the seller's status changes are: there is then no request shape that sets a status or a
 * verdict directly, and so no way around the rules or the trail. Every decision answers
 * with the listing as it now stands, trail included.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Admin", description = "Moderation. Requires the ADMIN role.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/listings")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AdminListingController {

    /** Smaller than elsewhere: every row carries a whole listing and all its photos. */
    private static final int MAX_PAGE_SIZE = 50;

    private final ListingModerationService moderation;

    public AdminListingController(ListingModerationService moderation) {
        this.moderation = moderation;
    }

    /**
     * One of the three moderation lists, a page at a time. {@code PENDING} is the queue:
     * online and not yet reviewed, oldest first.
     */
    @GetMapping
    public PageResponse<ModeratedListing> moderationList(
            @RequestParam(defaultValue = "PENDING") ModerationView view,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return PageResponse.of(moderation.list(view, page, size), listing -> listing);
    }

    /** One listing in any state, with its moderation trail. 404 once its seller has deleted it. */
    @GetMapping("/{id}")
    public ModeratedListing moderationDetail(@PathVariable UUID id) {
        return moderation.get(id);
    }

    /** Accepts a listing awaiting review. 409 if it is not online and unreviewed. */
    @PostMapping("/{id}/approve")
    public ModeratedListing approve(@AuthenticationPrincipal Jwt token,
                                    @PathVariable UUID id,
                                    @Valid @RequestBody(required = false) ModerationNoteRequest request) {
        return moderation.approve(CurrentUser.id(token), id, request == null ? null : request.note());
    }

    /** Refuses a listing awaiting review; it leaves the site. A reason is required. */
    @PostMapping("/{id}/reject")
    public ModeratedListing reject(@AuthenticationPrincipal Jwt token,
                                   @PathVariable UUID id,
                                   @Valid @RequestBody ModerationDecisionRequest request) {
        return moderation.reject(CurrentUser.id(token), id, request.reasonCode(), request.note());
    }

    /**
     * Takes down a listing that is online. Reversible — see {@link #reinstate} — unlike a
     * seller's own delete. A reason is required.
     */
    @PostMapping("/{id}/remove")
    public ModeratedListing remove(@AuthenticationPrincipal Jwt token,
                                   @PathVariable UUID id,
                                   @Valid @RequestBody ModerationDecisionRequest request) {
        return moderation.remove(CurrentUser.id(token), id, request.reasonCode(), request.note());
    }

    /**
     * Brings back a listing a moderator refused or took down. It returns online, or
     * paused if the seller's quota is full, or expired if its window has run out.
     */
    @PostMapping("/{id}/reinstate")
    public ModeratedListing reinstate(@AuthenticationPrincipal Jwt token,
                                      @PathVariable UUID id,
                                      @Valid @RequestBody(required = false) ModerationNoteRequest request) {
        return moderation.reinstate(CurrentUser.id(token), id, request == null ? null : request.note());
    }
}
