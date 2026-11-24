package com.autox.assistant;

import com.autox.api.error.ApiException;
import com.autox.reference.Currency;
import com.autox.reference.CurrencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * One turn of Assistant AutoX (docs/assistant-plan.md, 0 and 7).
 *
 * <p>Order matters, as in the smart search. The switch first, so a server with the assistant off
 * answers 404 and counts nothing. The conversation's length next, which costs nothing. Then the
 * caller's limits, the day's budget and the minute's pace in tokens ({@link AssistantPacing}), all
 * before the planner is called, so a refused turn spends no quota. The provider's own 429 makes the
 * server wait as long as the provider asks. Then the planner, once, or twice if its first plan cannot be
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
    private final AssistantPacing pacing;
    private final AssistantUsage usage;
    private final CurrencyRepository currencies;
    private final ObjectMapper json;

    public AssistantService(AssistantProperties properties, ObjectProvider<AssistantPlanner> planners, PlanValidator validator,
                            References references, AnswerBuilder answers, AssistantLimits limits,
                            AssistantPacing pacing, AssistantUsage usage, CurrencyRepository currencies,
                            ObjectMapper json) {
        this.pacing = pacing;
        this.properties = properties;
        // None configured (a provider other than none or groq, as in tests that do not need one): off.
        this.planner = planners.getIfUnique(DisabledAssistantPlanner::new);
        this.validator = validator;
        this.references = references;
        this.answers = answers;
        this.limits = limits;
        this.usage = usage;
        this.currencies = currencies;
        this.json = json;
        if (properties.evalTrace()) {
            log.warn("Assistant eval trace on: every turn's plan is sent back in {}. For a local evaluation only.",
                    EvalTrace.HEADER);
        }
    }

    /**
     * The seconds until a turn can be planned again, when the daily quota is spent: the provider's,
     * when it refused for it, else our budget over the last 24 hours.
     */
    private OptionalLong quotaSeconds() {
        OptionalLong provider = pacing.providerQuotaSeconds();
        return provider.isPresent() ? provider : usage.budgetFreesInSeconds(properties.dailyTokenBudget());
    }

    /** On only when switched on and a planner is configured. */
    public boolean enabled() {
        return properties.enabled() && planner.available();
    }

    public AssistantCapabilities capabilities() {
        requireEnabled();
        boolean busy = quotaSeconds().isPresent();
        return new AssistantCapabilities(!busy, busy ? AssistantCapabilities.Unavailable.BUSY : null,
                TurnRequest.MAX_MESSAGE, TurnRequest.MAX_TURNS);
    }

    public TurnResponse turn(TurnRequest request, String address, UUID accountId) {
        return turn(request, address, accountId, null);
    }

    /**
     * @param trace given {@link EvalTrace}'s header value when {@code autox.assistant.eval-trace} is on;
     *              null, or the switch off, and nothing is traced
     */
    public TurnResponse turn(TurnRequest request, String address, UUID accountId, Consumer<String> trace) {
        requireEnabled();
        Consumer<String> traced = properties.evalTrace() && trace != null ? trace : ignored -> { };
        TurnRequest.State state = request.state() == null ? TurnRequest.State.empty() : request.state();
        if (state.turns() >= TurnRequest.MAX_TURNS) {
            return new TurnResponse(List.of(AssistantBlock.noAnswer(Plan.Reason.CONVERSATION_OVER, null)), state);
        }

        OptionalLong wait = limits.recordCaller(address, accountId);
        if (wait.isPresent()) {
            throw ApiException.rateLimited(wait.getAsLong());
        }
        OptionalLong quota = quotaSeconds();
        if (quota.isPresent()) {
            log.info("Assistant turn refused: the daily quota is spent (budget {} over 24 hours, or the provider's), "
                    + "frees in {}s", properties.dailyTokenBudget(), quota.getAsLong());
            throw ApiException.assistantQuota(quota.getAsLong());
        }
        AssistantPacing.Reservation first = pacing.reserve();
        if (!first.granted()) {
            log.info("Assistant turn refused: busy for {}s", first.waitSeconds());
            throw ApiException.assistantBusy(first.waitSeconds());
        }

        long started = System.nanoTime();
        ContactMask.Masked message = ContactMask.mask(request.message().strip().replaceAll("\\s+", " "));
        // What is written to a model rather than to the site is not shown to ours.
        PlannerText.Split planned = PlannerText.split(message.text());
        ConversationMemory.Memory memory = ConversationMemory.of(state);
        UUID pageListing = request.context() == null ? null : request.context().listingId();
        References.Table table = references.build(state.shown(), pageListing);
        AssistantPlanner.Input input = new AssistantPlanner.Input(planned.text(), state.earlier(), table.references(),
                table.pageListing(), Year.now().getValue(), memory.search(), memory.car());
        int minorUnit = currencies.findById(CURRENCY).map(Currency::getMinorUnit).orElse((short) 2);

        long tokensIn = 0;
        long tokensOut = 0;
        PlanValidator.Result result = null;
        int attempts = 0;
        List<EvalTrace.Rejected> rejected = new ArrayList<>();
        AssistantPacing.Reservation reservation = first;
        try {
            // At most two planner calls: the second for a plan that cannot be followed, or a timeout.
            while (attempts < 2 && !(result instanceof PlanValidator.Valid)) {
                if (attempts > 0) {
                    // Paced like the first, but never dropped for it: it waits for the minute's room,
                    // a bounded time, and past that the turn is "busy", not "rephrase".
                    reservation = pacing.reserveWithin(properties.retryWait());
                    if (!reservation.granted()) {
                        usage.record(AssistantUsage.Outcome.FAILED, null, List.of(), attempts, tokensIn, tokensOut);
                        log.info("Assistant turn refused: the second planner call would wait {}s, over {}",
                                reservation.waitSeconds(), properties.retryWait());
                        throw ApiException.assistantBusy(reservation.waitSeconds());
                    }
                }
                attempts++;
                AssistantPlanner.Answer answered;
                try {
                    answered = planner.plan(input);
                } catch (PlannerUnavailableException e) {
                    if (e.kind() != PlannerUnavailableException.Kind.TIMEOUT) {
                        throw e;
                    }
                    pacing.settle(reservation.call(), e.tokensSpent());
                    tokensIn += e.tokensSpent();
                    if (attempts >= 2) {
                        usage.record(AssistantUsage.Outcome.FAILED, null, List.of(), attempts - 1, tokensIn, tokensOut);
                        log.info("Assistant turn failed: the planner timed out twice, {} ms", elapsed(started));
                        throw ApiException.assistantSlow();
                    }
                    log.info("Assistant planner timed out (attempt {}): asking once more", attempts);
                    rejected.add(new EvalTrace.Rejected("timeout", null));
                    continue;
                }
                pacing.settle(reservation.call(), answered.tokensIn() + answered.tokensOut());
                tokensIn += answered.tokensIn();
                tokensOut += answered.tokensOut();
                result = validator.validate(answered.plan(), planned.text(), table, minorUnit, memory);
                if (result instanceof PlanValidator.Invalid invalid) {
                    log.info("Assistant plan invalid (attempt {}): field={}", attempts, invalid.field());
                    rejected.add(new EvalTrace.Rejected(invalid.field(), answered.plan()));
                }
            }
        } catch (PlannerBusyException e) {
            if (e.daily()) {
                // The provider's daily quota: every turn until it frees is told so, without a call.
                pacing.providerQuotaFor(e.retryAfterSeconds());
                usage.record(AssistantUsage.Outcome.FAILED, null, List.of(), Math.max(0, attempts - 1), tokensIn, tokensOut);
                log.info("Assistant turn refused by the provider's daily quota: retry in {}s", e.retryAfterSeconds());
                throw ApiException.assistantQuota(e.retryAfterSeconds());
            }
            // The provider's own limit: wait as long as it says, and so does every caller after.
            pacing.providerBusyFor(e.retryAfterSeconds());
            usage.record(AssistantUsage.Outcome.FAILED, null, List.of(), Math.max(0, attempts - 1), tokensIn, tokensOut);
            log.info("Assistant turn refused by the provider's rate limit: retry in {}s", e.retryAfterSeconds());
            throw ApiException.assistantBusy(e.retryAfterSeconds());
        } catch (PlannerUnavailableException e) {
            pacing.settle(reservation.call(), e.tokensSpent());
            usage.record(AssistantUsage.Outcome.FAILED, null, List.of(), Math.max(0, attempts - 1),
                    tokensIn + e.tokensSpent(), tokensOut);
            log.info("Assistant turn failed: planner unavailable ({}) after {} ms, attempt {}", e.kind(), elapsed(started), attempts);
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
            if (properties.evalTrace()) {
                traced.accept(EvalTrace.invalid(rejected, attempts, tokensIn, tokensOut, json));
            }
            return new TurnResponse(blocks, next(state, message.text(), List.of(), memory));
        }

        Plan plan = withSetAside(valid.plan(), planned.setAside());
        AnswerBuilder.Answer answer = answers.build(plan, message.text(), state.earlier());
        blocks.addAll(answer.blocks());
        usage.record(AssistantUsage.Outcome.ANSWERED, plan.intent(), answer.reasons(), attempts - 1, tokensIn, tokensOut);
        log.info("Assistant turn: intent={} tools={} reasons={} attempts={} tokens={}/{} {} ms",
                plan.intent(), plan.calls().stream().map(call -> call.tool().wireName()).toList(), answer.reasons(),
                attempts, tokensIn, tokensOut, elapsed(started));
        if (properties.evalTrace()) {
            traced.accept(EvalTrace.of(plan, answer, blocks, table, minorUnit, rejected, attempts, tokensIn, tokensOut, json));
        }
        return new TurnResponse(blocks, next(state, message.text(), answer.shown(), ConversationMemory.after(plan, memory)));
    }

    /** What was set aside before planning is quoted back as not understood, first. */
    private static Plan withSetAside(Plan plan, List<String> setAside) {
        if (setAside.isEmpty()) {
            return plan;
        }
        List<String> quoted = new ArrayList<>(setAside);
        quoted.addAll(plan.notUnderstood());
        return new Plan(plan.intent(), plan.calls(), plan.noAnswer(), plan.clarify(),
                quoted.stream().distinct().limit(PlanValidator.MAX_NOT_UNDERSTOOD).toList());
    }

    /**
     * The state to send back: this message added, the listings shown added, at most what the limits
     * allow, and what the conversation remembers.
     */
    private static TurnRequest.State next(TurnRequest.State state, String message, List<UUID> shown,
                                          ConversationMemory.Memory memory) {
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
        return new TurnRequest.State(List.copyOf(earlier), List.copyOf(recent), state.turns() + 1, memory.search(),
                memory.car());
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
