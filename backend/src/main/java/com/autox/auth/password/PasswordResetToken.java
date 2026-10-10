package com.autox.auth.password;

import com.autox.common.CreatedEntity;
import com.autox.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single-use password reset grant.
 *
 * <p>Only the SHA-256 of the token is stored, exactly as for refresh tokens: this table
 * must not be a list of working reset links. SHA-256 rather than BCrypt because the token
 * is full-entropy random — slow hashing defends against guessable inputs, and a reset would
 * otherwise pay 250 ms to look up a row.
 *
 * <p>Two ways a token stops working besides expiring: {@link #getUsedAt()} when it reset a
 * password, and {@link #getSupersededAt()} when a newer request replaced it. They are
 * separate columns because the audit trail should distinguish a reset that happened from a
 * request the user abandoned — the second, repeated, is what a targeted attempt looks like.
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Column(name = "token_hash", length = 64, nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    /** Where the request came from. Audit only — never matched against. */
    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    protected PasswordResetToken() {
    }

    public static PasswordResetToken issue(AppUser user, String tokenHash, Instant expiresAt, String ipAddress) {
        PasswordResetToken token = new PasswordResetToken();
        token.user = user;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        token.ipAddress = ipAddress;
        return token;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && supersededAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant when) {
        if (usedAt == null) {
            usedAt = when;
        }
    }

    public AppUser getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public void setSupersededAt(Instant supersededAt) {
        this.supersededAt = supersededAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
