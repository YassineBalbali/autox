package com.autox.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every token in a rotation family. Called both on logout and on replay
     * detection, where one bulk statement matters more than loaded entities.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revokedReason = :reason
             where t.familyId = :familyId
               and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId,
                     @Param("reason") TokenRevocationReason reason,
                     @Param("now") Instant now);

    /**
     * One row per live session: rotation revokes the predecessor, so a family contributes
     * exactly one. Bounded by how many devices one person signs in from, which is why this
     * is the one list endpoint with no page parameter.
     */
    @Query("""
            select t from RefreshToken t
             where t.user.id = :userId
               and t.revokedAt is null
               and t.expiresAt > :now
             order by t.issuedAt desc
            """)
    java.util.List<RefreshToken> findActiveByUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Revokes a family, but only if it belongs to this user. The ownership check is in the
     * statement rather than in a read beforehand, so there is no window between the two and
     * a mismatch simply updates nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revokedReason = :reason
             where t.familyId = :familyId
               and t.user.id = :userId
               and t.revokedAt is null
            """)
    int revokeFamilyForUser(@Param("familyId") UUID familyId,
                            @Param("userId") UUID userId,
                            @Param("reason") TokenRevocationReason reason,
                            @Param("now") Instant now);

    /** Used when a password changes or an admin locks an account. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revokedReason = :reason
             where t.user.id = :userId
               and t.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId,
                         @Param("reason") TokenRevocationReason reason,
                         @Param("now") Instant now);

    /** Housekeeping: tokens long past expiry have no audit value. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);

    @Query("select count(t) from RefreshToken t where t.user.id = :userId and t.revokedAt is null and t.expiresAt > :now")
    long countActiveSessions(@Param("userId") UUID userId, @Param("now") Instant now);
}
