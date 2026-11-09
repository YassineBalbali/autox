package com.autox.admin;

import com.autox.admin.AdminUsers.AdminUserActionEntry;
import com.autox.admin.AdminUsers.AdminUserActionResult;
import com.autox.admin.AdminUsers.AdminUserDetail;
import com.autox.admin.AdminUsers.AdminUserRow;
import com.autox.api.error.ApiException;
import com.autox.auth.RefreshTokenService;
import com.autox.config.AuthProperties;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.ProProfile;
import com.autox.user.ProProfileRepository;
import com.autox.user.TokenRevocationReason;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What administrators can see and do about accounts.
 *
 * <p>Who may call this is decided in {@code CurrentAdminAuthenticationConverter}, on every request.
 *
 * <h2>There is always an administrator left</h2>
 *
 * <p>Every action takes a lock on all the administrators' rows and the target's. Without it, two
 * administrators demoting each other at the same moment would each see the other still in
 * place, and both would succeed.
 */
@Service
public class AdminUserService {

    /** How many listings and history lines a detail page carries. Newest first. */
    static final int HISTORY_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final AppUserRepository users;
    private final ProProfileRepository proProfiles;
    private final AdminUserRepository reads;
    private final AdminUserActionRepository actions;
    private final RefreshTokenService refreshTokens;
    private final long accessTokenTtlSeconds;

    AdminUserService(AppUserRepository users, ProProfileRepository proProfiles, AdminUserRepository reads,
                     AdminUserActionRepository actions, RefreshTokenService refreshTokens,
                     AuthProperties authProperties) {
        this.users = users;
        this.proProfiles = proProfiles;
        this.reads = reads;
        this.actions = actions;
        this.refreshTokens = refreshTokens;
        this.accessTokenTtlSeconds = authProperties.accessTokenTtl().toSeconds();
    }

    @Transactional(readOnly = true)
    public Page<AdminUserRow> list(AdminUserRepository.Filter filter, PageRequest page) {
        return reads.list(filter, page);
    }

    @Transactional(readOnly = true)
    public AdminUserDetail detail(UUID userId) {
        return detailOf(requireAccount(userId));
    }

    /**
     * Suspends an account: it can no longer sign in or refresh, its sessions end now, and its
     * listings leave the public site through {@code ListingSearchRepository.visible}. Nothing
     * else about the account or its listings changes, so reactivation has nothing to rebuild.
     */
    @Transactional
    public AdminUserActionResult suspend(UUID actorId, UUID userId, String reason) {
        List<AppUser> locked = users.lockAdministratorsAnd(userId);
        AppUser target = targetIn(locked, userId);
        AppUser actor = requirePermitted(actorId, target, locked, true);
        if (target.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.conflict("This account is already suspended.");
        }

        UserStatus previous = target.getStatus();
        target.setStatus(UserStatus.SUSPENDED);
        int revoked = refreshTokens.revokeAllSessions(target.getId(), TokenRevocationReason.ADMIN_REVOKED);
        record(actor, target, AdminUserAction.Type.SUSPEND, reason.trim(), previous.name(), UserStatus.SUSPENDED.name());
        log.warn("Admin {} suspended user {}; {} session(s) revoked", actor.getId(), target.getId(), revoked);
        return result(target, revoked);
    }

    /**
     * Lifts a suspension, back to the status it replaced. Not simply to ACTIVE: an account whose
     * address was never verified was not active before, and a suspension must not verify it.
     */
    @Transactional
    public AdminUserActionResult reactivate(UUID actorId, UUID userId, String note) {
        List<AppUser> locked = users.lockAdministratorsAnd(userId);
        AppUser target = targetIn(locked, userId);
        AppUser actor = requirePermitted(actorId, target, locked, false);
        if (target.getStatus() != UserStatus.SUSPENDED) {
            throw ApiException.conflict("This account is not suspended.");
        }

        UserStatus restored = actions.findLatestSuspension(target.getId())
                .map(suspension -> UserStatus.valueOf(suspension.getPreviousValue()))
                // Suspended by hand in the database: no record to go by.
                .orElse(target.isEmailVerified() ? UserStatus.ACTIVE : UserStatus.PENDING_VERIFICATION);
        // The address was verified during the suspension.
        if (restored == UserStatus.PENDING_VERIFICATION && target.isEmailVerified()) {
            restored = UserStatus.ACTIVE;
        }
        target.setStatus(restored);
        record(actor, target, AdminUserAction.Type.REACTIVATE, blankToNull(note),
                UserStatus.SUSPENDED.name(), restored.name());
        log.warn("Admin {} reactivated user {}", actor.getId(), target.getId());
        return result(target, 0);
    }

    /**
     * Hides a dealer's public page: the directory leaves them out and the page answers 404 to the
     * public. The account, its listings and its conversations are not touched; the dealer still
     * sees their page, with the reason.
     */
    @Transactional
    public AdminUserActionResult hideDealerPage(UUID actorId, UUID userId, String reason) {
        List<AppUser> locked = users.lockAdministratorsAnd(userId);
        AppUser target = targetIn(locked, userId);
        AppUser actor = requirePermitted(actorId, target, locked, false);
        ProProfile profile = dealerPageOf(target);
        if (profile.getHiddenAt() != null) {
            throw ApiException.conflict("This dealer's page is already hidden.");
        }
        profile.hide(reason.trim(), java.time.Instant.now());
        record(actor, target, AdminUserAction.Type.HIDE_DEALER_PAGE, reason.trim(), "VISIBLE", "HIDDEN");
        log.warn("Admin {} hid the dealer page of user {}", actor.getId(), target.getId());
        proProfiles.flush();
        return result(target, 0);
    }

    @Transactional
    public AdminUserActionResult showDealerPage(UUID actorId, UUID userId, String note) {
        List<AppUser> locked = users.lockAdministratorsAnd(userId);
        AppUser target = targetIn(locked, userId);
        AppUser actor = requirePermitted(actorId, target, locked, false);
        ProProfile profile = dealerPageOf(target);
        if (profile.getHiddenAt() == null) {
            throw ApiException.conflict("This dealer's page is not hidden.");
        }
        profile.show();
        record(actor, target, AdminUserAction.Type.SHOW_DEALER_PAGE, blankToNull(note), "HIDDEN", "VISIBLE");
        log.warn("Admin {} put back the dealer page of user {}", actor.getId(), target.getId());
        proProfiles.flush();
        return result(target, 0);
    }

    /** A professional's company profile, which is their page. Anyone else has none to hide. */
    private ProProfile dealerPageOf(AppUser target) {
        if (target.getRole() != UserRole.PROFESSIONNEL) {
            throw ApiException.conflict("Only a professional account has a public page.");
        }
        return proProfiles.findByUserId(target.getId()).orElseThrow(() -> ApiException.notFound("Company profile"));
    }

    /**
     * Changes an account's role. Takes effect at the account's next token: at the latest when
     * its current access token runs out and is refreshed.
     */
    @Transactional
    public AdminUserActionResult changeRole(UUID actorId, UUID userId, UserRole role, String note) {
        List<AppUser> locked = users.lockAdministratorsAnd(userId);
        AppUser target = targetIn(locked, userId);
        AppUser actor = requirePermitted(actorId, target, locked, role != UserRole.ADMIN);
        if (target.getRole() == role) {
            throw ApiException.conflict("This account already has this role.");
        }
        if (role == UserRole.ADMIN && reads.isDemoAccount(target.getId())) {
            // Nobody can sign in to it, so it would count as an administrator nobody can be.
            throw ApiException.demoAccountCannotBeAdmin();
        }

        UserRole previous = target.getRole();
        target.setRole(role);
        // A dealer's listing page and company screen expect a company. Their display name stands
        // in until they correct it, and a profile from an earlier time as a dealer is kept.
        if (role == UserRole.PROFESSIONNEL && proProfiles.findByUserId(target.getId()).isEmpty()) {
            proProfiles.save(ProProfile.forUser(target, target.getDisplayName(), null));
        }
        record(actor, target, AdminUserAction.Type.CHANGE_ROLE, blankToNull(note), previous.name(), role.name());
        log.warn("Admin {} changed user {} from {} to {}", actor.getId(), target.getId(), previous, role);
        return result(target, 0);
    }

    /**
     * The rules, on the rows as locked. The caller's rights were checked as the request came in,
     * but a colleague can take them away between then and getting the lock, which is exactly how two
     * administrators demoting each other arrive here: so they are read again under it. In this
     * order so that the last-administrator rule holds whoever is asking.
     *
     * @param removesAdministrator whether the action can take the target out of the
     *                             administrators able to sign in
     * @return the caller's account
     */
    private AppUser requirePermitted(UUID actorId, AppUser target, List<AppUser> locked, boolean removesAdministrator) {
        if (target.getId().equals(actorId)) {
            throw ApiException.adminSelfAction();
        }
        if (removesAdministrator && canAdminister(target)
                && locked.stream().noneMatch(user -> !user.getId().equals(target.getId()) && canAdminister(user))) {
            throw ApiException.lastAdmin();
        }
        return requireCurrentAdmin(actorId);
    }

    private AppUser requireCurrentAdmin(UUID actorId) {
        return users.findActiveById(actorId)
                .filter(AdminUserService::canAdminister)
                .orElseThrow(() -> ApiException.forbidden("Administrator rights have been withdrawn from this account."));
    }

    private static boolean canAdminister(AppUser user) {
        return user.getRole() == UserRole.ADMIN
                && user.getDeletedAt() == null
                && user.getStatus() != UserStatus.SUSPENDED
                && user.getStatus() != UserStatus.DELETED;
    }

    private static AppUser targetIn(List<AppUser> locked, UUID userId) {
        return locked.stream()
                .filter(user -> user.getId().equals(userId))
                .filter(user -> user.getDeletedAt() == null && user.getStatus() != UserStatus.DELETED)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private AppUser requireAccount(UUID userId) {
        return users.findActiveById(userId)
                .filter(user -> user.getStatus() != UserStatus.DELETED)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private void record(AppUser actor, AppUser target, AdminUserAction.Type type, String reason,
                        String previousValue, String newValue) {
        actions.save(AdminUserAction.of(actor, target, type, reason, previousValue, newValue));
    }

    private AdminUserActionResult result(AppUser target, int revokedSessions) {
        // The detail is read with plain SQL, which must see what this transaction has just done.
        users.flush();
        return new AdminUserActionResult(detailOf(target), revokedSessions, accessTokenTtlSeconds);
    }

    private AdminUserDetail detailOf(AppUser user) {
        ProProfile profile = proProfiles.findByUserId(user.getId()).orElse(null);
        return new AdminUserDetail(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhoneE164(),
                user.getPhoneVerifiedAt(),
                user.getRole(),
                user.getStatus(),
                user.getEmailVerifiedAt(),
                user.getCountry().getCode(),
                user.getLocale(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                reads.isDemoAccount(user.getId()),
                profile == null ? null : profile.getCompanyName(),
                profile == null ? null : profile.getRegistrationNumber(),
                reads.activity(user.getId()),
                reads.listings(user.getId(), HISTORY_LIMIT),
                reads.moderationHistory(user.getId(), HISTORY_LIMIT),
                actions.findLatestForTarget(user.getId(), Limit.of(HISTORY_LIMIT)).stream()
                        .map(AdminUserActionEntry::from)
                        .toList(),
                accessTokenTtlSeconds,
                profile == null || user.getRole() != UserRole.PROFESSIONNEL ? null
                        : new AdminUsers.DealerPageState(reads.dealerSlug(user.getId()), profile.getHiddenAt(),
                                profile.getHiddenReason()));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
