package com.autox.listing.report;

import com.autox.api.PageResponse;
import com.autox.api.error.ApiException;
import com.autox.auth.verification.EmailVerificationService;
import com.autox.listing.ListingModerationService;
import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports on listings: filed by signed-in visitors, dealt with by moderators beside the message
 * reports.
 *
 * <ul>
 *   <li>One report per person and listing; a second answers 409 {@code LISTING_ALREADY_REPORTED}.</li>
 *   <li>Only a listing the public can see, and not one's own.</li>
 *   <li>{@link #PER_WINDOW} reports an hour per account, past which 429 {@code RATE_LIMITED}.</li>
 *   <li>A moderator dismisses the reports on a listing, or takes the listing down, which records a
 *       moderation decision with the reason the report gave. Either closes every open report on
 *       that listing.</li>
 * </ul>
 */
@Service
public class ListingReportService {

    static final int PER_WINDOW = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    private final ListingReportRepository reports;
    private final ThrottleCounters counters;
    private final EmailVerificationService emailVerification;
    private final ListingModerationService moderation;

    public ListingReportService(ListingReportRepository reports, ThrottleCounters counters,
                                EmailVerificationService emailVerification, ListingModerationService moderation) {
        this.reports = reports;
        this.counters = counters;
        this.emailVerification = emailVerification;
        this.moderation = moderation;
    }

    @Transactional
    public void report(UUID reporterId, UUID listingId, ReportListingRequest request) {
        UUID sellerId = reports.visibleListingSeller(listingId)
                .orElseThrow(() -> ApiException.notFound("Listing " + listingId));
        if (sellerId.equals(reporterId)) {
            throw ApiException.forbidden("You cannot report your own listing.");
        }
        emailVerification.requireVerified(reporterId);
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (request.reason() == ListingReportReason.OTHER && note == null) {
            throw ApiException.validationFailed("A note is required when the reason is OTHER.");
        }
        if (reports.reportedBy(listingId, reporterId)) {
            throw ApiException.listingAlreadyReported();
        }
        allow(reporterId);
        if (!reports.insert(listingId, reporterId, request.reason(), note, Instant.now())) {
            // A second request from the same person, a moment behind the first.
            throw ApiException.listingAlreadyReported();
        }
    }

    /** Whether the caller has reported this listing, for the listing page's button. */
    @Transactional(readOnly = true)
    public boolean reportedBy(UUID userId, UUID listingId) {
        return reports.reportedBy(listingId, userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportedListing> open(int page, int size) {
        List<ReportedListing> items = reports.open(size, (long) page * size).stream().map(ReportedListing::from).toList();
        return PageResponse.of(new PageImpl<>(items, PageRequest.of(page, size), reports.countOpen()), item -> item);
    }

    /** Closes the reports on the listing and leaves it as it is. */
    @Transactional
    public void dismiss(UUID moderatorId, UUID reportId) {
        ListingReportRepository.Target target = openTarget(reportId);
        reports.resolveAll(target.listingId(), "DISMISSED", moderatorId, Instant.now());
    }

    /**
     * Takes the listing down, as a moderation decision with the report's reason, and closes the
     * reports on it. 409 when the listing is not online any more: dismiss the report then.
     */
    @Transactional
    public void remove(UUID moderatorId, UUID reportId) {
        ListingReportRepository.Target target = openTarget(reportId);
        moderation.remove(moderatorId, target.listingId(), target.reason().moderationReason(),
                "Retirée à la suite d’un signalement.");
        reports.resolveAll(target.listingId(), "REMOVED", moderatorId, Instant.now());
    }

    private ListingReportRepository.Target openTarget(UUID reportId) {
        ListingReportRepository.Target target = reports.target(reportId)
                .orElseThrow(() -> ApiException.notFound("Report " + reportId));
        if (!target.open()) {
            throw ApiException.conflict("This report has already been dealt with.");
        }
        return target;
    }

    /**
     * In the report's transaction: a refusal rolls its own count back, which changes nothing, since
     * the count it found was already past the limit and still is.
     */
    private void allow(UUID reporterId) {
        Instant now = Instant.now();
        ThrottleCounters.WindowCount count = counters.recordInFixedWindow(
                ThrottleScope.LISTING_REPORT, reporterId.toString(), now, WINDOW);
        if (count.eventCount() > PER_WINDOW) {
            throw ApiException.rateLimited(Math.max(1, Duration.between(now, count.windowEndsAt()).toSeconds() + 1));
        }
    }
}
