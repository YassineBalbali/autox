package com.autox.assistant;

import com.autox.api.error.ApiException;
import com.autox.reference.Currency;
import com.autox.reference.CurrencyRepository;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One turn of Assistant AutoX (docs/assistant-plan.md, 0 and 7).
 *
 * <p>Order matters, as in the smart search. The switch first, so a server with the assistant off
 * answers 404 and counts nothing. The conversation's length next, which costs nothing. Then the
 * caller's limits, the day's budget and the minute's pace, all before the planner is called, so a
 * refused turn spends no quota. Then the planner, once, or twice if its first plan cannot be
 * followed; the validator; the tools; the answer.
 *
 * <p><b>Logged per turn:</b> the outcome, the intent, the tools, the tokens and the time, and the
 * field that made a plan invalid. <b>Never</b> the message, the plan's values or a listing's label.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    static final String CURRENCY = "EUR";

    private final AssistantProperties properties;
    private final AssistantPlanner planner;
    private final PlanValidator validator;
    private final References references;
    private final AnswerBuilder answers;
    private final AssistantLimits limits;
    private final AssistantUsage usage;
    private final CurrencyRepository currencies;

    public AssistantService(AssistantProperties properties, AssistantPlanner planner, PlanValidator validator,
                            References references, AnswerBuilder answers, AssistantLimits limits,
                            AssistantUsage usage, CurrencyRepository currencies) {
        this.properties = properties;
        this.planner = planner;
        this.validator = validator;
        this.references = references;
        this.answers = answers;
        this.limits = limits;
        this.usage = usage;
        this.currencies = currencies;
    }

    /** On only when switched on and a planner is configured. */
    public boolean enabled() {
        return properties.enabled() && planner.available();
    }

    public AssistantCapabilities capabilities() {
        requireEnabled();
        boolean busy = usage.tokensToday() >= properties.dailyTokenBudget();
        return new AssistantCapabilities(!busy, busy ? AssistantCapabilities.Unavailable.BUSY : null,
                TurnRequest.MAX_MESSAGE, TurnRequest.MAX_TURNS);
    }

    public TurnResponse turn(TurnRequest request, String address, UUID accountId) {
        requireEnabled();
        TurnRequest.State state = request.state() == null ? TurnRequest.State.empty() : request.state();
        if (state.turns() >= TurnRequest.MAX_TURNS) {
            return new TurnResponse(List.of(AssistantBlock.noAnswer(Plan.Reason.CONVERSATION_OVER, null)), state);
        }

        OptionalLong wait = limits.recordCaller(address, accountId);
        if (wait.isPresent()) {
            throw ApiException.rateLimited(wait.getAsLong());
        }
        if (usage.tokensToday() >= properties.dailyTokenBudget()) {
            log.info("Assistant turn refused: today's token budget of {} is spent", properties.dailyTokenBudget());
            throw ApiException.assistantUnavailable();
        }
        OptionalLong pace = limits.takeTurn();
        if (pace.isPresent()) {
            throw ApiException.rateLimited(pace.getAsLong());
        }

        long started = System.nanoTime();
        ContactMask.Masked message = ContactMask.mask(request.message().strip().replaceAll("\\s+", " "));
        UUID pageListing = request.context() == null ? null : request.context().listingId();
        References.Table table = references.build(state.shown(), pageListing);
        AssistantPlanner.Input input = new AssistantPlanner.Input(message.text(), state.earlier(), table.references(),
                table.pageListing(), Year.now().getValue());
        int minorUnit = currencies.findById(CURRENCY).map(Currency::getMinorUnit).orElse((short) 2);

        long tokensIn = 0;
        long tokensOut = 0;
        PlanValidator.Result result = null;
        int attempts = 0;
        try {
            while (attempts < 2 && !(result instanceof PlanValidator.Valid)) {
                attempts++;
                AssistantPlanner.Answer planned = planner.plan(input);
                tokensIn += planned.tokensIn();
                tokensOut += planned.tokensOut();
                result = validator.validate(planned.plan(), message.text(), table, minorUnit);
                if (result instanceof PlanValidator.Invalid invalid) {
                    log.info("Assistant plan invalid (attempt {}): field={}", attempts, invalid.field());
                }
            }
        } catch (PlannerUnavailableException e) {
            usage.record(AssistantUsage.Outcome.FAILED, null, List.of(), attempts - 1, tokensIn + e.tokensSpent(), tokensOut);
            log.info("Assistant turn failed: planner unavailable after {} ms", elapsed(started));
            throw ApiException.assistantUnavailable();
        }

        List<AssistantBlock> blocks = new ArrayList<>();
        if (message.removed()) {
            blocks.add(AssistantBlock.of(AssistantBlock.Type.CONTACT_REMOVED));
        }
        if (!(result instanceof PlanValidator.Valid valid)) {
            blocks.add(AssistantBlock.noAnswer(Plan.Reason.REPHRASE, null));
            usage.record(AssistantUsage.Outcome.INVALID, null, List.of(Plan.Reason.REPHRASE), attempts - 1, tokensIn, tokensOut);
            log.info("Assistant turn: two invalid plans, tokens={}/{}, {} ms", tokensIn, tokensOut, elapsed(started));
            return new TurnResponse(blocks, next(state, message.text(), List.of()));
        }

        Plan plan = valid.plan();
        AnswerBuilder.Answer answer = answers.build(plan, message.text());
        blocks.addAll(answer.blocks());
        usage.record(AssistantUsage.Outcome.ANSWERED, plan.intent(), answer.reasons(), attempts - 1, tokensIn, tokensOut);
        log.info("Assistant turn: intent={} tools={} reasons={} attempts={} tokens={}/{} {} ms",
                plan.intent(), plan.calls().stream().map(call -> call.tool().wireName()).toList(), answer.reasons(),
                attempts, tokensIn, tokensOut, elapsed(started));
        return new TurnResponse(blocks, next(state, message.text(), answer.shown()));
    }

    /** The state to send back: this message added, the listings shown added, at most what the limits allow. */
    private static TurnRequest.State next(TurnRequest.State state, String message, List<UUID> shown) {
        List<String> earlier = new ArrayList<>(state.earlier());
        earlier.add(message.length() > TurnRequest.MAX_MESSAGE ? message.substring(0, TurnRequest.MAX_MESSAGE) : message);
        while (earlier.size() > TurnRequest.MAX_EARLIER) {
            earlier.removeFirst();
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(state.shown());
        for (UUID id : shown) {
            ids.remove(id);
            ids.add(id);
        }
        List<UUID> recent = new ArrayList<>(ids);
        while (recent.size() > References.MAX) {
            recent.removeFirst();
        }
        return new TurnRequest.State(List.copyOf(earlier), List.copyOf(recent), state.turns() + 1);
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw ApiException.notFound("Assistant");
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
