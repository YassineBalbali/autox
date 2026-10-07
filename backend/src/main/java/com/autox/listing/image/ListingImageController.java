package com.autox.listing.image;

import com.autox.listing.dto.ImageResponse;
import com.autox.listing.dto.ImageUploadRequest;
import com.autox.listing.dto.ImageUploadTicket;
import com.autox.listing.dto.ReorderImagesRequest;
import com.autox.user.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A listing's photos.
 *
 * <p>No endpoint here accepts file content. Uploading is a pre-signed PUT straight to object
 * storage; the API issues the slot and verifies the result. That keeps large uploads off the
 * application's threads and memory entirely, and means a phone on a slow connection is
 * talking to storage rather than holding one of our request threads open for a minute.
 */
@RestController
@RequestMapping("/api/v1/listings/{listingId}/images")
public class ListingImageController {

    private final ListingImageService imageService;

    public ListingImageController(ListingImageService imageService) {
        this.imageService = imageService;
    }

    /** Step 1: reserve a slot and get a pre-signed upload URL. */
    @PostMapping
    public ResponseEntity<ImageUploadTicket> requestUpload(@AuthenticationPrincipal Jwt token,
                                                           @PathVariable UUID listingId,
                                                           @Valid @RequestBody ImageUploadRequest request) {
        ImageUploadTicket ticket = imageService.requestUpload(
                CurrentUser.id(token), CurrentUser.isAdmin(token), listingId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ticket);
    }

    /** Step 3, after the client has PUT the bytes to storage. Idempotent. */
    @PostMapping("/{imageId}/confirm")
    public ImageResponse confirmUpload(@AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID listingId,
                                       @PathVariable UUID imageId) {
        return imageService.confirmUpload(CurrentUser.id(token), CurrentUser.isAdmin(token), listingId, imageId);
    }

    /** Every photo, pending ones included, so a client can show upload progress. */
    @GetMapping
    public List<ImageResponse> list(@AuthenticationPrincipal Jwt token, @PathVariable UUID listingId) {
        return imageService.list(CurrentUser.id(token), CurrentUser.isAdmin(token), listingId);
    }

    /** Replaces the gallery order. The body must list every confirmed photo exactly once. */
    @PutMapping("/order")
    public List<ImageResponse> reorder(@AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID listingId,
                                       @Valid @RequestBody ReorderImagesRequest request) {
        return imageService.reorder(CurrentUser.id(token), CurrentUser.isAdmin(token),
                listingId, request.imageIds());
    }

    /** Removes the photo, its variants and the stored objects, and closes the ordering gap. */
    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID listingId,
                                       @PathVariable UUID imageId) {
        imageService.delete(CurrentUser.id(token), CurrentUser.isAdmin(token), listingId, imageId);
        return ResponseEntity.noContent().build();
    }
}
