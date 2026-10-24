package com.autox.search;

import com.autox.search.dto.Valuation;
import com.autox.search.dto.ValuationEngine;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "La Cote": what a car is worth on AutoX, for anyone. No authentication, and rate-limited
 * per address like the rest of the public read API.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "La Cote", description = "Public valuation of a described car against live listings. No authentication.")
@RestController
@RequestMapping("/api/v1/valuation")
@Validated
public class LaCoteController {

    private final LaCote laCote;

    public LaCoteController(LaCote laCote) {
        this.laCote = laCote;
    }

    /**
     * An estimate of a car's value: the median asking price of comparable live listings,
     * each brought to this car's age and mileage, with the interquartile range around it.
     *
     * <p>The comparables are the price indicator's: same model, same fuel, power within 15 %,
     * registered within two years, in the same mileage band. With fewer than five there is no
     * estimate, and {@code widerScopes} lists the loosenings that would give one; ask again
     * with one as {@code scope}. 404 for an unknown model.
     */
    @GetMapping
    public Valuation value(@Valid ValuationRequest car) {
        return laCote.value(car);
    }

    /**
     * The engines of a model that are listed now, each a fuel and a power with the version name
     * sellers most often give it: the choices a visitor values a car with. 404 for an unknown
     * model.
     */
    @GetMapping("/engines")
    public List<ValuationEngine> engines(@RequestParam UUID modelId) {
        return laCote.engines(modelId);
    }
}
