package com.autox.auth.verification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** Only the newest link works. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailVerificationToken t
               set t.supersededAt = :now
             where t.user.id = :userId
               and t.usedAt is null
               and t.supersededAt is null
            """)
    int supersedeOutstanding(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Spends the token. One statement, so two clicks at once cannot both find it unspent: the
     * second waits for the first and then updates nothing.
     *
     * @return 1 if this call spent it
     */
    @Modifying
    @Query("update EmailVerificationToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("delete from EmailVerificationToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
