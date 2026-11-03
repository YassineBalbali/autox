package com.autox.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AdminUserActionRepository extends JpaRepository<AdminUserAction, UUID> {

    /** Newest first; the ids are time-ordered, which breaks a tie within one instant. */
    @Query("""
            select a from AdminUserAction a
             where a.targetUserId = :target
             order by a.createdAt desc, a.id desc
            """)
    List<AdminUserAction> findLatestForTarget(@Param("target") UUID target, Limit limit);

    /** The suspension in force: what the status was before it, for reactivation to restore. */
    default Optional<AdminUserAction> findLatestSuspension(UUID target) {
        return findLatestForTarget(target, Limit.of(AdminUserService.HISTORY_LIMIT)).stream()
                .filter(action -> action.getAction() == AdminUserAction.Type.SUSPEND)
                .findFirst();
    }
}
