package com.autox.listing;

import com.autox.api.error.ApiException;
import com.autox.listing.dto.ImageResponse;
import com.autox.listing.dto.ListingResponse;
import com.autox.listing.dto.ModeratedListing;
import com.autox.listing.dto.ModerationEntry;
import com.autox.storage.ObjectStorage;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moderation: what an administrator decides about somebody else's listing.
 *
 * <h2>The model</h2>
 *
 * <p>A listing goes online the moment its seller publishes it and is reviewed afterwards.
 * So "awaiting review" is not a status: it is a listing that is {@code ACTIVE} and whose
 * verdict is still {@code NOT_REVIEWED}. A moderator then does one of four things, and
 * each writes one line to the trail:
 *
 * <table>
 *   <caption>Moderation actions</caption>
 *   <tr><th>Action</th><th>From</th><th>Status after</th><th>Verdict after</th></tr>
 *   <tr><td>APPROVE</td><td>ACTIVE, not reviewed</td><td>ACTIVE</td><td>APPROVED</td></tr>
 *   <tr><td>REJECT</td><td>ACTIVE, not reviewed</td><td>REMOVED</td><td>REJECTED</td></tr>
 *   <tr><td>REMOVE</td><td>ACTIVE</td><td>REMOVED</td><td>REJECTED</td></tr>
 *   <tr><td>REINSTATE</td><td>REMOVED</td><td>ACTIVE, PAUSED or EXPIRED</td><td>APPROVED</td></tr>
 * </table>
 *
 * <p>Refusing and taking down end in the same place on purpose. They differ in when they
 * happen — at first review, or after the listing had been accepted — and the trail keeps
 * that difference; the listing itself needs only one way of being off the site by a
 * moderator's decision.
 *
 * <h2>Reversible, unlike a seller's delete</h2>
 *
 * <p>A moderator's removal sets {@code REMOVED} and nothing else. A seller's delete also
 * stamps {@code deleted_at}, after which the row is invisible to every read path,
 * including this one. That difference is the whole of what makes reinstatement possible,
 * and it is why a listing its seller deleted cannot be brought back from here.
 *
 * <h2>Who</h2>
 *
 * <p>Administrators, enforced where the endpoints are declared. Nothing here takes an
 * "is admin" flag: there is no way to call this service on a seller's behalf.
 */
@Service
public class ListingModerationService {

    private static final Logger log = LoggerFactory.getLogger(ListingModerationService.class);

    private final ListingRepository listings;
    private final ListingImageRepository images;
    private final ModerationActionRepository actions;
    private final AppUserRepository users;
    private final ListingQuotaPolicy quota;
    private final ObjectStorage storage;

    public ListingModerationService(ListingRepository listings,
                                    ListingImageRepository images,
                                    ModerationActionRepository actions,
                                    AppUserRepository users,
                                    ListingQuotaPolicy quota,
                                    ObjectStorage storage) {
        this.listings = listings;
        this.images = images;
        this.actions = actions;
        this.users = users;
        this.quota = quota;
        this.storage = storage;
    }

    // --- reads ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ModeratedListing> list(ModerationView view, int page, int size) {
        Page<Listing> found = switch (view) {
            // Oldest first: a queue is worked from the front, and the listing that has
            // been online unreviewed the longest is the one that most needs a look.
            case PENDING -> listings.findAwaitingReview(
                    PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt")));
            case LIVE -> listings.findByStatus(ListingStatus.ACTIVE,
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt")));
            case REMOVED -> listings.findByStatus(ListingStatus.REMOVED,
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
        };

        // Photos and trails for the whole page in two queries, not two per row.
        List<UUID> ids = found.getContent().stream().map(Listing::getId).toList();
        Map<UUID, List<ListingImage>> photos = new HashMap<>();
        Map<UUID, List<ModerationAction>> trails = new HashMap<>();
        if (!ids.isEmpty()) {
            images.findReadyByListings(ids).forEach(image ->
                    photos.computeIfAbsent(image.getListing().getId(), key -> new ArrayList<>()).add(image));
            actions.findTrails(ids).forEach(action ->
                    trails.computeIfAbsent(action.getListing().getId(), key -> new ArrayList<>()).add(action));
        }

        return found.map(listing -> view(listing,
                photos.getOrDefault(listing.getId(), List.of()),
                trails.getOrDefault(listing.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public ModeratedListing get(UUID listingId) {
        return view(load(listingId));
    }

    // --- decisions --------------------------------------------------------

    /** Accepts a listing that is online and has not been reviewed. It stays online. */
    @Transactional
    public ModeratedListing approve(UUID moderatorId, UUID listingId, String note) {
        Listing listing = load(listingId);
        requireAwaitingReview(listing, "approved");

        listing.setModerationStatus(ModerationStatus.APPROVED);
        return record(listing, moderatorId, ModerationActionType.APPROVE, null, note);
    }

    /** Refuses a listing at first review. It leaves the site at once. */
    @Transactional
    public ModeratedListing reject(UUID moderatorId, UUID listingId, ModerationReason reason, String note) {
        Listing listing = load(listingId);
        requireAwaitingReview(listing, "rejected");
        requireExplained(reason, note);

        takeOffline(listing);
        return record(listing, moderatorId, ModerationActionType.REJECT, reason, note);
    }

    /**
     * Takes down a listing that is online — typically one that had been approved, and
     * about which something has since been learned.
     */
    @Transactional
    public ModeratedListing remove(UUID moderatorId, UUID listingId, ModerationReason reason, String note) {
        Listing listing = load(listingId);
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw ApiException.conflict("Only a listing that is online can be taken down; this one is "
                    + listing.getStatus() + ".");
        }
        requireExplained(reason, note);

        takeOffline(listing);
        return record(listing, moderatorId, ModerationActionType.REMOVE, reason, note);
    }

    /**
     * Brings back a listing a moderator had refused or taken down.
     *
     * <p>It returns to where it would be had it never left, which is not always online:
     * <ul>
     *   <li><b>EXPIRED</b> if its window ran out while it was down. Reinstating is not
     *       renewing; the seller renews it, as they would any expired listing.</li>
     *   <li><b>PAUSED</b> if the seller has meanwhile filled their quota. A moderator
     *       undoing a mistake must not be stopped by the cap, and must not break it
     *       either; paused, the listing is the seller's again to put online when there
     *       is room.</li>
     *   <li><b>ACTIVE</b> otherwise.</li>
     * </ul>
     *
     * <p>The verdict becomes APPROVED: somebody has now looked at it and decided it may be
     * on the site, which is what approval means.
     */
    @Transactional
    public ModeratedListing reinstate(UUID moderatorId, UUID listingId, String note) {
        Listing listing = load(listingId);
        if (listing.getStatus() != ListingStatus.REMOVED) {
            throw ApiException.conflict("Only a removed listing can be reinstated; this one is "
                    + listing.getStatus() + ".");
        }

        ListingStatus target;
        Instant expiresAt = listing.getExpiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            target = ListingStatus.EXPIRED;
        } else if (!quota.hasRoomForOneMore(listing.getSeller())) {
            target = ListingStatus.PAUSED;
        } else {
            target = ListingStatus.ACTIVE;
        }

        move(listing, target);
        listing.setModerationStatus(ModerationStatus.APPROVED);
        return record(listing, moderatorId, ModerationActionType.REINSTATE, null, note);
    }

    // --- rules ------------------------------------------------------------

    /**
     * What a moderator may do with a listing in its present state. Sent with every
     * listing, and the same conditions the methods above enforce.
     */
    static Set<ModerationActionType> allowedActions(Listing listing) {
        return switch (listing.getStatus()) {
            case ACTIVE -> listing.getModerationStatus() == ModerationStatus.NOT_REVIEWED
                    ? EnumSet.of(ModerationActionType.APPROVE, ModerationActionType.REJECT,
                            ModerationActionType.REMOVE)
                    : EnumSet.of(ModerationActionType.REMOVE);
            case REMOVED -> EnumSet.of(ModerationActionType.REINSTATE);
            default -> EnumSet.noneOf(ModerationActionType.class);
        };
    }

    /**
     * Loads a listing for moderation: any seller's, but never one its seller deleted.
     * Those are gone for good — see the class comment.
     */
    private Listing load(UUID listingId) {
        return listings.findActiveById(listingId).orElseThrow(() -> ApiException.notFound("Listing"));
    }

    private void requireAwaitingReview(Listing listing, String verb) {
        if (listing.getStatus() != ListingStatus.ACTIVE
                || listing.getModerationStatus() != ModerationStatus.NOT_REVIEWED) {
            throw ApiException.conflict("Only a listing that is online and awaiting review can be " + verb
                    + "; this one is " + listing.getStatus() + " and " + listing.getModerationStatus() + ".");
        }
    }

    private void requireExplained(ModerationReason reason, String note) {
        if (reason.requiresNote() && (note == null || note.isBlank())) {
            throw ApiException.validationFailed("A note is required when the reason is " + reason + ".");
        }
    }

    private void takeOffline(Listing listing) {
        move(listing, ListingStatus.REMOVED);
        listing.setModerationStatus(ModerationStatus.REJECTED);
    }

    /** Every status change here goes through the same table a seller's does. */
    private void move(Listing listing, ListingStatus target) {
        if (!listing.getStatus().canTransitionTo(target)) {
            throw ApiException.conflict("Cannot move a listing from " + listing.getStatus() + " to " + target + ".");
        }
        listing.setStatus(target);
    }

    /** Writes the trail entry and answers with the listing as it now stands. */
    private ModeratedListing record(Listing listing, UUID moderatorId, ModerationActionType type,
                                    ModerationReason reason, String note) {
        AppUser moderator = users.findActiveById(moderatorId).orElseThrow(() -> ApiException.notFound("User"));
        String kept = note == null || note.isBlank() ? null : note.trim();

        actions.save(ModerationAction.of(listing, moderator, type, reason == null ? null : reason.name(), kept));
        // The version check happens here rather than at commit, so that two moderators
        // deciding at once produce one decision and one refusal — not two trail entries.
        listings.saveAndFlush(listing);

        log.info("Listing {} {} by moderator {}{} -> {} / {}", listing.getId(), type, moderatorId,
                reason == null ? "" : " (" + reason + ")", listing.getStatus(), listing.getModerationStatus());
        return view(listing);
    }

    private ModeratedListing view(Listing listing) {
        List<UUID> id = List.of(listing.getId());
        return view(listing, images.findReadyByListings(id), actions.findTrails(id));
    }

    private ModeratedListing view(Listing listing, List<ListingImage> photos, List<ModerationAction> trail) {
        List<ImageResponse> gallery = photos.stream()
                .map(image -> ImageResponse.from(image, storage::readUrl))
                .toList();
        return new ModeratedListing(
                ListingResponse.from(listing, gallery),
                ModeratedListing.ModeratedSeller.from(listing.getSeller()),
                allowedActions(listing),
                trail.stream().map(ModerationEntry::from).toList());
    }
}
