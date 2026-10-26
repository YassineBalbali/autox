package com.autox.catalog.suggest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * One entry under the search bar as the visitor types: a make, or a model of a make.
 *
 * <p>Ids and slugs both. The slugs are what the search URL carries ({@code marque=audi}),
 * the ids what the rest of the API is keyed on. The model fields are absent on a make.
 */
@Schema(name = "CatalogueSuggestion")
public record CatalogueSuggestion(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Type type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID makeId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "audi") String makeSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Audi") String makeName,
        UUID modelId,
        @Schema(example = "a3") String modelSlug,
        @Schema(example = "A3") String modelName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Audi A3",
                description = "What to show: the make's name, or the make's and the model's.") String label,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1",
                description = "Listings online now. Never zero: an entry with none is not suggested.")
        long listingCount) {

    @Schema(name = "CatalogueSuggestionType")
    public enum Type { MAKE, MODEL }
}
