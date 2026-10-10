package com.autox.auth.password;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Invalidates every outstanding request for a user. Called when a new one is issued, so
     * that only the newest link works — otherwise a user who clicks "forgot password" three
     * times leaves three live keys to their account in three emails.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PasswordResetToken t
               set t.supersededAt = :now
             where t.user.id = :userId
               and t.usedAt is null
               and t.supersededAt is null
            """)
    int supersedeOutstanding(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Housekeeping: a long-expired request has no audit value left. */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
