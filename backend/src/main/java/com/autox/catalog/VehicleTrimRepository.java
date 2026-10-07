package com.autox.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleTrimRepository extends JpaRepository<VehicleTrim, UUID> {

    @Query("select t from VehicleTrim t where t.id = :id and t.active = true")
    Optional<VehicleTrim> findActiveById(@Param("id") UUID id);
}
