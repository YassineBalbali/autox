package com.autox.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a turn decided, for the evaluation in eval/assistant/ (docs/assistant-plan.md, 9 and 12):
 * the checked plan written back in the planner's own terms (listing codes, whole euros), the
 * reasons and block types of the answer, the attempts and the provider's token counts.
 *
 * <p>Sent as the {@link #HEADER} response header only when {@code autox.assistant.eval-trace} is on,
 * which a local machine sets for a run and production never does. One header rather than a field
 * of the response, so the API's contract and the clients are unchanged. ASCII JSON: a header holds
 * no other characters, so the rest is escaped.
 */
final class EvalTrace {

    static final String HEADER = "X-Assistant-Eval";

    private EvalTrace() {
    }

    /** A turn whose plans could not be followed: no plan, the « reformuler » answer. */
    static String invalid(int attempts, long tokensIn, long tokensOut, ObjectMapper json) {
        ObjectNode trace = json.createObjectNode();
        trace.putNull("intent");
        trace.putArray("calls");
        trace.putArray("reasons").add(Plan.Reason.REPHRASE.name());
        trace.putArray("blocks").add(AssistantBlock.Type.NO_ANSWER.name());
        return write(counts(trace, attempts, tokensIn, tokensOut), json);
    }

    static String of(Plan plan, AnswerBuilder.Answer answer, List<AssistantBlock> blocks, References.Table table,
                     int minorUnit, int attempts, long tokensIn, long tokensOut, ObjectMapper json) {
        ObjectNode trace = json.createObjectNode();
        trace.put("intent", plan.intent().name());
        ArrayNode calls = trace.putArray("calls");
        BigDecimal scale = BigDecimal.TEN.pow(minorUnit);
        for (Plan.Call call : plan.calls()) {
            ObjectNode out = calls.addObject().put("tool", call.tool().wireName());
            switch (call) {
                case Plan.Search s -> {
                    out.set("criteria", s.criteria());
                    out.put("sort", s.sort() == null ? null : s.sort().name());
                }
                case Plan.OneListing one -> out.put("ref", code(table, one.listingId()));
                case Plan.Compare c -> refs(out, table, c.listingIds());
                case Plan.TooFewToCompare few -> refs(out, table, few.listingIds());
                case Plan.Finance f -> {
                    if (f.listingId() != null) {
                        out.put("ref", code(table, f.listingId()));
                    }
                    euros(out, "amountEuros", f.amount(), scale);
                    euros(out, "downPaymentEuros", f.downPayment(), scale);
                    if (f.months() != null) {
                        out.put("months", f.months());
                    }
                    if (f.ratePercent() != null) {
                        out.put("ratePercent", f.ratePercent());
                    }
                }
                case Plan.CritAirOf c -> {
                    if (c.listingId() != null) {
                        out.put("ref", code(table, c.listingId()));
                    }
                    if (c.fuel() != null) {
                        out.put("fuel", c.fuel().name());
                    }
                    if (c.year() != null) {
                        out.put("year", c.year());
                    }
                    if (c.month() != null) {
                        out.put("month", c.month());
                    }
                }
                case Plan.Value v -> {
                    out.put("make", v.make()).put("model", v.model()).put("fuel", v.fuel() == null ? null : v.fuel().name())
                            .put("powerHp", v.powerHp()).put("year", v.year()).put("mileageKm", v.mileageKm());
                    if (v.month() != null) {
                        out.put("month", v.month());
                    }
                }
                case Plan.Help h -> out.put("query", h.query());
            }
        }
        if (plan.noAnswer() != null) {
            trace.put("noAnswer", plan.noAnswer().name());
        }
        if (plan.clarify() != null) {
            trace.put("clarify", plan.clarify().name());
        }
        ArrayNode reasons = trace.putArray("reasons");
        answer.reasons().forEach(reason -> reasons.add(reason.name()));
        ArrayNode types = trace.putArray("blocks");
        blocks.forEach(block -> types.add(block.type().name()));
        return write(counts(trace, attempts, tokensIn, tokensOut), json);
    }

    private static ObjectNode counts(ObjectNode trace, int attempts, long tokensIn, long tokensOut) {
        return trace.put("attempts", attempts).put("tokensIn", tokensIn).put("tokensOut", tokensOut);
    }

    private static void refs(ObjectNode out, References.Table table, List<UUID> ids) {
        ArrayNode refs = out.putArray("refs");
        ids.forEach(id -> refs.add(code(table, id)));
    }

    private static String code(References.Table table, UUID id) {
        return table.references().stream().filter(reference -> reference.listingId().equals(id))
                .map(References.Reference::code).findFirst().orElse("?");
    }

    private static void euros(ObjectNode out, String field, Long minor, BigDecimal scale) {
        if (minor != null) {
            out.put(field, BigDecimal.valueOf(minor).divide(scale).stripTrailingZeros());
        }
    }

    private static String write(ObjectNode trace, ObjectMapper json) {
        try {
            return json.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
