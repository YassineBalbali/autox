package com.autox.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleModelRepository extends JpaRepository<VehicleModel, UUID> {

    @Query("select m from VehicleModel m where m.id = :id and m.active = true")
    Optional<VehicleModel> findActiveById(@Param("id") UUID id);

    @Query("select m from VehicleModel m where m.make.id = :makeId and m.active = true order by m.name")
    java.util.List<VehicleModel> findActiveByMake(@Param("makeId") UUID makeId);
}
