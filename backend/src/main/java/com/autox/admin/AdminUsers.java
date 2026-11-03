package com.autox.admin;

import com.autox.listing.ListingStatus;
import com.autox.listing.ModerationActionType;
import com.autox.listing.ModerationStatus;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the administrators' user screens read and send. Addresses and phone numbers: admins only. */
public final class AdminUsers {

    private static final int MAX_REASON = 2000;

    private AdminUsers() {
    }

    /** A line of the list. */
    public record AdminUserRow(
            UUID id,
            String email,
            String displayName,
            UserRole role,
            UserStatus status,
            boolean emailVerified,
            String countryCode,
            String locale,
            Instant createdAt,
            long listingCount,
            boolean demo) {
    }

    /** The whole account, with what it has done and what was done about it. */
    public record AdminUserDetail(
            UUID id,
            String email,
            String displayName,
            String firstName,
            String lastName,
            String phone,
            Instant phoneVerifiedAt,
            UserRole role,
            UserStatus status,
            Instant emailVerifiedAt,
            String countryCode,
            String locale,
            Instant createdAt,
            Instant lastLoginAt,
            boolean demo,
            String companyName,
            String registrationNumber,
            AdminUserActivity activity,
            List<AdminUserListing> listings,
            List<AdminListingHistoryEntry> moderationHistory,
            List<AdminUserActionEntry> actions,
            long accessTokenTtlSeconds) {
    }

    public record AdminUserActivity(
            long listings,
            long listingsOnline,
            long conversations,
            long messagesSent,
            long reportsFiled,
            long reportsReceived,
            long activeSessions) {
    }

    /** {@code online}: a visitor can open it right now, so a link to it leads somewhere. */
    public record AdminUserListing(
            UUID id,
            String title,
            ListingStatus status,
            ModerationStatus moderationStatus,
            long priceAmount,
            String currencyCode,
            Instant createdAt,
            boolean online) {
    }

    public record AdminListingHistoryEntry(
            UUID id,
            UUID listingId,
            String listingTitle,
            ModerationActionType action,
            String reasonCode,
            String note,
            String adminName,
            Instant createdAt) {
    }

    public record AdminUserActionEntry(
            UUID id,
            AdminUserAction.Type action,
            String adminEmail,
            String reason,
            String previousValue,
            String newValue,
            Instant createdAt) {

        static AdminUserActionEntry from(AdminUserAction action) {
            return new AdminUserActionEntry(action.getId(), action.getAction(), action.getAdminEmail(),
                    action.getReason(), action.getPreviousValue(), action.getNewValue(), action.getCreatedAt());
        }
    }

    /**
     * The account after an action. {@code accessTokenTtlSeconds} is how long an access token
     * issued just before a suspension can still be used: it cannot be recalled.
     */
    public record AdminUserActionResult(
            AdminUserDetail user,
            int revokedSessions,
            long accessTokenTtlSeconds) {
    }

    public record SuspendUserRequest(@NotBlank @Size(max = MAX_REASON) String reason) {
    }

    public record ReactivateUserRequest(@Size(max = MAX_REASON) String note) {
    }

    public record ChangeUserRoleRequest(@NotNull UserRole role, @Size(max = MAX_REASON) String note) {
    }
}
