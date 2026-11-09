package com.autox.admin;

import com.autox.common.CreatedEntity;
import com.autox.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Append-only record of what an administrator did to an account: who, what, on whom, why and
 * when. Plain ids and copied addresses rather than associations: V19 says why a record outlives
 * the accounts it names.
 */
@Entity
@Table(name = "admin_user_action")
public class AdminUserAction extends CreatedEntity {

    /** Mirrors ck_admin_user_action. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AdminUserActionType", enumAsRef = true)
    public enum Type {
        SUSPEND,
        REACTIVATE,
        CHANGE_ROLE,
        /** A dealer's public page taken off the site; a reason is required, as for a suspension. */
        HIDE_DEALER_PAGE,
        SHOW_DEALER_PAGE
    }

    @Column(name = "admin_id", nullable = false, updatable = false)
    private UUID adminId;

    @Column(name = "admin_email", length = 255, nullable = false, updatable = false)
    private String adminEmail;

    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;

    @Column(name = "target_email", length = 255, nullable = false, updatable = false)
    private String targetEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16, nullable = false, updatable = false)
    private Type action;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "previous_value", length = 32, nullable = false, updatable = false)
    private String previousValue;

    @Column(name = "new_value", length = 32, nullable = false, updatable = false)
    private String newValue;

    protected AdminUserAction() {
    }

    /** One record. There is no other way to make one, and no way to change one. */
    static AdminUserAction of(AppUser admin, AppUser target, Type action, String reason,
                              String previousValue, String newValue) {
        AdminUserAction entry = new AdminUserAction();
        entry.adminId = admin.getId();
        entry.adminEmail = admin.getEmail();
        entry.targetUserId = target.getId();
        entry.targetEmail = target.getEmail();
        entry.action = action;
        entry.reason = reason;
        entry.previousValue = previousValue;
        entry.newValue = newValue;
        return entry;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public Type getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getPreviousValue() {
        return previousValue;
    }

    public String getNewValue() {
        return newValue;
    }
}
