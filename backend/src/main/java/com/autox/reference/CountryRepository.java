package com.autox.reference;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CountryRepository extends JpaRepository<Country, String> {

    /** Only an enabled country may be chosen at registration. */
    @Query("select c from Country c where c.code = upper(:code) and c.enabled = true")
    Optional<Country> findEnabledByCode(String code);

    @Query("select c from Country c where c.enabled = true order by c.name")
    List<Country> findAllEnabled();
}
