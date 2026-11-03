package com.autox.listing.dto;

import com.autox.listing.DamageType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * What a seller declares about damage. {@code damaged} false says the vehicle is undamaged and
 * clears anything declared before; true needs all three of the others.
 *
 * @param running whether it runs: "roulant" or "non roulant"
 */
public record DamageDeclaration(
        @NotNull Boolean damaged,
        Set<@NotNull DamageType> types,
        Boolean running,
        @Size(max = 2000) String description) {

    @Schema(hidden = true)
    @AssertTrue(message = "a damaged vehicle needs at least one damage type, whether it runs, and a description")
    public boolean isComplete() {
        return !Boolean.TRUE.equals(damaged)
                || (types != null && !types.isEmpty() && running != null
                        && description != null && !description.isBlank());
    }
}
