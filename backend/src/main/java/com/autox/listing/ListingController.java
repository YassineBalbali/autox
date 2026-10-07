package com.autox.listing;

import com.autox.api.PageResponse;
import com.autox.listing.dto.CreateListingRequest;
import com.autox.listing.dto.ListingResponse;
import com.autox.listing.dto.ListingSummary;
import com.autox.listing.dto.UpdateListingRequest;
import com.autox.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller-side listing management. Every endpoint requires authentication; the public,
 * unauthenticated read and search endpoints are a separate resource and a later step.
 *
 * <p>Status changes are their own endpoints rather than a field on the update payload,
 * because that is what makes the transition rules unbypassable: there is no request shape
 * that can set a status directly.
 */
@RestController
@RequestMapping("/api/v1/listings")
@Validated
public class ListingController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ListingService listingService;

    public ListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    /** Creates a DRAFT. Publication is a separate, explicit call. */
    @PostMapping
    public ResponseEntity<ListingResponse> create(@AuthenticationPrincipal Jwt token,
                                                  @Valid @RequestBody CreateListingRequest request) {
        ListingResponse created = listingService.create(CurrentUser.id(token), request);
        return ResponseEntity.created(URI.create("/api/v1/listings/" + created.id())).body(created);
    }

    /** The seller's own listings, in every state. Paginated; there is no unbounded form. */
    @GetMapping("/mine")
    public PageResponse<ListingSummary> listOwn(
            @AuthenticationPrincipal Jwt token,
            @RequestParam(required = false) ListingStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PageResponse.of(
                listingService.listOwn(CurrentUser.id(token), status, pageRequest),
                summary -> summary);
    }

    @GetMapping("/{id}")
    public ListingResponse get(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return listingService.get(CurrentUser.id(token), CurrentUser.isAdmin(token), id);
    }

    @PatchMapping("/{id}")
    public ListingResponse update(@AuthenticationPrincipal Jwt token,
                                  @PathVariable UUID id,
                                  @Valid @RequestBody UpdateListingRequest request) {
        return listingService.update(CurrentUser.id(token), CurrentUser.isAdmin(token), id, request);
    }

    /** DRAFT, PAUSED or EXPIRED to ACTIVE. Checks the active-listing cap. */
    @PostMapping("/{id}/publish")
    public ListingResponse publish(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return listingService.publish(CurrentUser.id(token), CurrentUser.isAdmin(token), id);
    }

    /** ACTIVE to PAUSED. Keeps the listing and its photos, frees the quota slot. */
    @PostMapping("/{id}/unpublish")
    public ListingResponse unpublish(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return listingService.unpublish(CurrentUser.id(token), CurrentUser.isAdmin(token), id);
    }

    /** Extends the expiry window, and revives an expired listing. */
    @PostMapping("/{id}/renew")
    public ListingResponse renew(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return listingService.renew(CurrentUser.id(token), CurrentUser.isAdmin(token), id);
    }

    /** Soft delete: the row stays for the audit trail but disappears from every read. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        listingService.delete(CurrentUser.id(token), CurrentUser.isAdmin(token), id);
        return ResponseEntity.noContent().build();
    }
}
