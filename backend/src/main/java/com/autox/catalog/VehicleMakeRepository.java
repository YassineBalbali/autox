package com.autox.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleMakeRepository extends JpaRepository<VehicleMake, UUID> {

    /** A deactivated make may not be chosen for a new listing, though existing ones keep it. */
    @Query("select m from VehicleMake m where m.id = :id and m.active = true")
    Optional<VehicleMake> findActiveById(@Param("id") UUID id);

    /** Popular makes first, so a search dropdown can show the common ones without a second call. */
    @Query("select m from VehicleMake m where m.active = true order by m.popular desc, m.name")
    java.util.List<VehicleMake> findAllActive();
}
