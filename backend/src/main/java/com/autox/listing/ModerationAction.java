package com.autox.listing;

import com.autox.common.CreatedEntity;
import com.autox.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Append-only audit entry: who did what to a listing, when and why. The current
 * verdict is denormalised onto {@link Listing#getModerationStatus()}; this table is
 * the history behind it, and the reason shown to the seller.
 */
@Entity
@Table(name = "moderation_action")
public class ModerationAction extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false, updatable = false)
    private Listing listing;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_id", nullable = false, updatable = false)
    private AppUser admin;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16, nullable = false, updatable = false)
    private ModerationActionType action;

    /** Required for REJECT and REMOVE. Stable code, translated for display. */
    @Column(name = "reason_code", length = 40, updatable = false)
    private String reasonCode;

    /** Internal free-text detail. */
    @Column(name = "note", updatable = false)
    private String note;

    protected ModerationAction() {
    }

    /** One line of the trail. There is no other way to make one, and no way to change one. */
    public static ModerationAction of(Listing listing, AppUser admin, ModerationActionType action,
                                      String reasonCode, String note) {
        ModerationAction entry = new ModerationAction();
        entry.listing = listing;
        entry.admin = admin;
        entry.action = action;
        entry.reasonCode = reasonCode;
        entry.note = note;
        return entry;
    }

    public Listing getListing() {
        return listing;
    }

    public void setListing(Listing listing) {
        this.listing = listing;
    }

    public AppUser getAdmin() {
        return admin;
    }

    public void setAdmin(AppUser admin) {
        this.admin = admin;
    }

    public ModerationActionType getAction() {
        return action;
    }

    public void setAction(ModerationActionType action) {
        this.action = action;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
