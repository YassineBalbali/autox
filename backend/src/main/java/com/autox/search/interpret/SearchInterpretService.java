package com.autox.search.interpret;

import com.autox.api.error.ApiException;
import com.autox.reference.Currency;
import com.autox.reference.CurrencyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Year;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;

/**
 * Natural-language search: a description in, checked filters out. The search itself is not run
 * here — the client applies the filters and the ordinary search runs, so this endpoint can fail
 * without the search failing with it.
 *
 * <p>Order matters. The feature check comes first, so a server with it off answers 404 without
 * counting anything; the rate limit before the provider call, so a refused request spends no
 * quota; validation after, on everything the provider returned.
 */
@Service
public class SearchInterpretService {

    /** The price filter's currency: one market is open, in euros. */
    static final String CURRENCY = "EUR";

    private final SearchInterpreter interpreter;
    private final InterpretationValidator validator;
    private final InterpretRateLimit rateLimit;
    private final CurrencyRepository currencies;
    private final InterpretUsage usage;

    SearchInterpretService(SearchInterpreter interpreter, InterpretationValidator validator,
                           InterpretRateLimit rateLimit, CurrencyRepository currencies, InterpretUsage usage) {
        this.interpreter = interpreter;
        this.validator = validator;
        this.rateLimit = rateLimit;
        this.currencies = currencies;
        this.usage = usage;
    }

    public SearchCapabilities capabilities() {
        return new SearchCapabilities(interpreter.available());
    }

    public InterpretResponse interpret(String query, String clientAddress) {
        if (!interpreter.available()) {
            throw ApiException.notFound("Natural-language search");
        }
        String text = query.strip().replaceAll("\\s+", " ");

        OptionalLong retryAfter = rateLimit.record(clientAddress);
        if (retryAfter.isPresent()) {
            throw ApiException.rateLimited(retryAfter.getAsLong());
        }

        JsonNode answer;
        try {
            answer = interpreter.interpret(text);
        } catch (InterpreterUnavailableException e) {
            usage.record(InterpretUsage.Outcome.FAILED);
            throw ApiException.interpreterUnavailable();
        }
        int minorUnit = currencies.findById(CURRENCY).map(Currency::getMinorUnit).orElse((short) 2);
        InterpretResponse response = validator.validate(answer, text, Year.now().getValue(), minorUnit);
        // Counted once the answer is known; never the text, only how it went (see InterpretUsage).
        usage.record(response.notUnderstood().isEmpty()
                ? InterpretUsage.Outcome.UNDERSTOOD : InterpretUsage.Outcome.PARTLY_UNDERSTOOD);
        return response;
    }
}
