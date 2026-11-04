package com.autox.auth.verification;

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
 * A single-use link that proves control of an address. Only the SHA-256 of the token is stored,
 * for the reasons given on {@code PasswordResetToken}.
 */
@Entity
@Table(name = "email_verification_token")
public class EmailVerificationToken extends CreatedEntity {

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

    protected EmailVerificationToken() {
    }

    static EmailVerificationToken issue(AppUser user, String tokenHash, Instant expiresAt) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.user = user;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        return token;
    }

    boolean isUsable(Instant now) {
        return usedAt == null && supersededAt == null && expiresAt.isAfter(now);
    }

    AppUser getUser() {
        return user;
    }
}
