package com.autox.search.interpret;

import com.autox.search.ListingSearchCriteria;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What a description of a car was understood to mean.
 *
 * @param criteria      the filters, exactly as {@code GET /api/v1/listings} takes them: catalogue
 *                      ids, prices in the currency's minor units with the currency beside them.
 *                      Every value has been checked; none comes straight from the model.
 * @param summary       what was applied, in French, for display: "SUV · Diesel · Automatique ·
 *                      ≤ 15 000 €". Written by the server from {@code criteria}, so it never
 *                      describes a filter that is not there.
 * @param notUnderstood the parts of the request that could not become a filter — something the
 *                      search cannot filter on ("bon état", a colour) or a value that failed
 *                      checking (a make not in the catalogue). Empty when everything was used.
 */
@Schema(description = "A natural-language search, as filters the listing search accepts.")
public record InterpretResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ListingSearchCriteria criteria,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "SUV / 4x4 · Diesel · Automatique · ≤ 15 000 €")
        String summary,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"en bon état\"]")
        List<String> notUnderstood) {
}
