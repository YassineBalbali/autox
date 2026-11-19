package com.autox.assistant.tools;

import com.autox.api.error.ApiException;
import com.autox.catalog.FuelType;
import com.autox.search.LaCote;
import com.autox.search.ValuationRequest;
import com.autox.search.ValuationScope;
import com.autox.search.dto.Valuation;
import com.autox.search.interpret.CatalogueMatcher;
import java.time.Year;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The {@code estimate_value} tool: La Cote for a car the user describes, exactly
 * {@code GET /valuation}'s answer (docs/price-indicator.md, "La Cote"). The make and model are
 * names, resolved against the catalogue by the smart search's own matcher.
 *
 * <p>Fewer than five comparables is still an answer: no estimate, with the count and the
 * loosenings that would reach five, which is what La Cote's page shows. A make or model the
 * catalogue does not have is {@code NOT_COMPUTABLE}.
 */
@Component
public class ValuationTool {

    public static final int MIN_POWER_HP = 20;
    public static final int MAX_POWER_HP = 1_500;
    public static final int MIN_YEAR = 1950;
    public static final int MAX_MILEAGE_KM = 1_000_000;
    static final String CURRENCY = "EUR";

    private final CatalogueMatcher catalogue;
    private final LaCote laCote;

    public ValuationTool(CatalogueMatcher catalogue, LaCote laCote) {
        this.catalogue = catalogue;
        this.laCote = laCote;
    }

    /**
     * @param make  null when the user named only the model; it must then be the only model of that name
     * @param scope null is the indicator's own rule ({@code EXACT})
     */
    public ToolResult<Valuation> value(String make, String model, FuelType fuel, int powerHp, int year,
                                       Integer month, int mileageKm, ValuationScope scope) {
        if (powerHp < MIN_POWER_HP || powerHp > MAX_POWER_HP || year < MIN_YEAR || year > Year.now().getValue() + 1
                || (month != null && (month < 1 || month > 12)) || mileageKm < 0 || mileageKm > MAX_MILEAGE_KM) {
            throw new IllegalArgumentException("A value out of bounds for La Cote");
        }
        Optional<CatalogueMatcher.Model> resolved = resolve(make, model);
        if (resolved.isEmpty()) {
            return ToolResult.refusal(ToolResult.Reason.NOT_COMPUTABLE);
        }
        try {
            return ToolResult.answer(laCote.value(new ValuationRequest(
                    resolved.get().id(), fuel, powerHp, year, month, mileageKm, scope, CURRENCY)));
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                return ToolResult.refusal(ToolResult.Reason.NOT_COMPUTABLE);
            }
            throw e;
        }
    }

    private Optional<CatalogueMatcher.Model> resolve(String make, String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        if (make == null || make.isBlank()) {
            return catalogue.modelAlone(model);
        }
        return catalogue.make(make).flatMap(found -> catalogue.model(found, model));
    }
}
