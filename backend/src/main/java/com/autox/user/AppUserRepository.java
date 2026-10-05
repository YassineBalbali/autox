package com.autox.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Case-insensitive, matching the {@code lower(email)} unique index. */
    @Query("select u from AppUser u where lower(u.email) = lower(:email) and u.deletedAt is null")
    Optional<AppUser> findActiveByEmail(@Param("email") String email);

    @Query("select u from AppUser u where u.id = :id and u.deletedAt is null")
    Optional<AppUser> findActiveById(@Param("id") UUID id);

    @Query("select count(u) > 0 from AppUser u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    /** Admin listing. Pageable is mandatory — no unbounded query exists on this table. */
    @Query("select u from AppUser u where u.deletedAt is null")
    Page<AppUser> findAllActive(Pageable pageable);

    @Query("select u from AppUser u where u.deletedAt is null and u.role = :role")
    Page<AppUser> findAllActiveByRole(@Param("role") UserRole role, Pageable pageable);
}
