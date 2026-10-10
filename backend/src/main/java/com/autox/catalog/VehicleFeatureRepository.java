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

    /**
     * The whole equipment vocabulary for the reference endpoint, labels included.
     *
     * <p>Fetch-joined deliberately: without it this is one query for the 34 features plus one
     * per feature for its labels, on an endpoint whose entire job is to be cheap and cacheable.
     * {@code distinct} is needed because the join multiplies each feature by its locale count.
     */
    @Query("""
            select distinct f from VehicleFeature f
              left join fetch f.translations
             where f.active = true
             order by f.category, f.displayOrder, f.code
            """)
    List<VehicleFeature> findAllActiveWithTranslations();
}
