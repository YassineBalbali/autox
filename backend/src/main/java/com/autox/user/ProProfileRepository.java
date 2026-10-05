package com.autox.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProProfileRepository extends JpaRepository<ProProfile, UUID> {

    Optional<ProProfile> findByUserId(UUID userId);
}
