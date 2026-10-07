package com.autox.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleFeatureRepository extends JpaRepository<VehicleFeature, UUID> {

    @Query("select f from VehicleFeature f where f.code in :codes and f.active = true")
    List<VehicleFeature> findActiveByCodes(@Param("codes") Collection<String> codes);
}
