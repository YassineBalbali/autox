package com.autox.reference;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeoPlaceRepository extends JpaRepository<GeoPlace, UUID> {
}
