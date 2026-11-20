package com.autox.assistant;

import com.autox.api.error.ApiException;
import com.autox.assistant.tools.CompareListingsTool;
import com.autox.assistant.tools.CritAirTool;
import com.autox.assistant.tools.FinancingTool;
import com.autox.assistant.tools.HelpIndex;
import com.autox.assistant.tools.ListingFacts;
import com.autox.assistant.tools.ListingFactsReader;
import com.autox.assistant.tools.SearchListingsTool;
import com.autox.assistant.tools.SimilarListingsTool;
import com.autox.assistant.tools.ToolResult;
import com.autox.assistant.tools.ValuationTool;
import com.autox.search.ListingSearchService;
import com.autox.search.dto.Valuation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Runs a checked plan's tools and builds the answer from their results (docs/assistant-plan.md,
 * 1.2 and 1.6). Every block is the server's: listing cards from the public detail, figures from the
 * tools, help text from the help pages, and for anything that has no answer a reason code the
 * clients word from the labels.
 */
@Component
public class AnswerBuilder {

    private final SearchListingsTool search;
    private final ListingFactsReader reader;
    private final CompareListingsTool compare;
    private final SimilarListingsTool similar;
    private final FinancingTool financing;
    private final CritAirTool critAir;
    private final ValuationTool valuation;
    private final HelpIndex help;
    private final ListingSearchService listings;

    public AnswerBuilder(SearchListingsTool search, ListingFactsReader reader, CompareListingsTool compare,
                         SimilarListingsTool similar, FinancingTool financing, CritAirTool critAir,
                         ValuationTool valuation, HelpIndex help, ListingSearchService listings) {
        this.search = search;
        this.reader = reader;
        this.compare = compare;
        this.similar = similar;
        this.financing = financing;
        this.critAir = critAir;
        this.valuation = valuation;
        this.help = help;
        this.listings = listings;
    }

    /**
     * @param shown the listings this answer shows, which the next turn can point at
     * @param reasons the reasons of its {@code NO_ANSWER} blocks, for the usage counts
     */
    public record Answer(List<AssistantBlock> blocks, List<UUID> shown, List<Plan.Reason> reasons) {
    }

    public Answer build(Plan plan, String message) {
        Building answer = new Building();
        if (asksForSellerDetails(message)) {
            // Before anything the plan says, and instead of it when it has no listing to show: the
            // seller is reached through the listing's "Contacter le vendeur", never through us.
            answer.noAnswer(Plan.Reason.SELLER_CONTACT, listingOf(plan));
            if (EnumSet.of(Plan.Intent.NO_ANSWER, Plan.Intent.HELP, Plan.Intent.CLARIFY).contains(plan.intent())) {
                return answer.done();
            }
        }
        switch (plan.intent()) {
            case NO_ANSWER -> {
                if (plan.noAnswer() == Plan.Reason.OUT_OF_SCOPE) {
                    outOfScope(message, answer);
                } else {
                    answer.noAnswer(plan.noAnswer(), null);
                }
            }
            case CLARIFY -> answer.blocks.add(AssistantBlock.clarify(plan.clarify()));
            default -> plan.calls().forEach(call -> run(call, message, answer));
        }
        if (!plan.notUnderstood().isEmpty()) {
            answer.notUnderstood.addAll(plan.notUnderstood());
        }
        if (!answer.notUnderstood.isEmpty()) {
            answer.blocks.add(AssistantBlock.notUnderstood(List.copyOf(answer.notUnderstood)));
        }
        return answer.done();
    }

    /** The listing the plan is about, if it names exactly one: the one whose contact button to show. */
    private static UUID listingOf(Plan plan) {
        List<UUID> ids = plan.calls().stream().map(call -> switch (call) {
            case Plan.OneListing one -> one.listingId();
            case Plan.Finance f -> f.listingId();
            case Plan.CritAirOf c -> c.listingId();
            default -> null;
        }).filter(java.util.Objects::nonNull).distinct().toList();
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    /**
     * A request for a seller's phone, e-mail, address or identity (docs/assistant-plan.md, 3.3).
     * The masked contact details of the user's own message are left out first: they are the user's.
     */
    static boolean asksForSellerDetails(String message) {
        String folded = PlanValidator.fold(message.replace(ContactMask.REPLACEMENT, " "));
        return SELLER_DETAILS.matcher(folded).find();
    }

    private static final String DETAIL = "(telephones?|tel|numeros?|portable|e-?mails?|mails?|courriels?|adresses?|"
            + "coordonnees|noms?|prenoms?|identite|qui est|whatsapp)";
    private static final String SELLER = "(vendeu(r|se|rs|ses)|proprietaires?|annonceurs?)";
    /** A detail and the seller within a few words of each other, in either order, folded. */
    private static final java.util.regex.Pattern SELLER_DETAILS = java.util.regex.Pattern.compile(
            "\\b" + DETAIL + "\\b(\\W+\\w+){0,4}?\\W+" + SELLER + "\\b|\\b" + SELLER + "\\b(\\W+\\w+){0,4}?\\W+"
                    + DETAIL + "\\b");

    private void run(Plan.Call call, String message, Building answer) {
        switch (call) {
            case Plan.Search s -> {
                SearchListingsTool.Outcome outcome = search.search(s.criteria(), message, s.sort());
                List<ListingCard> cards = answer.cards(outcome.results().stream().map(ListingFacts::id).toList());
                if (cards.isEmpty()) {
                    answer.noAnswer(Plan.Reason.NO_RESULTS, null);
                } else {
                    answer.blocks.add(AssistantBlock.listings(cards));
                }
                answer.blocks.add(AssistantBlock.searchLink(new AssistantBlock.SearchLink(outcome.criteria(),
                        outcome.sort(), outcome.count().count(), outcome.count().capped())));
                answer.notUnderstood.addAll(outcome.notUnderstood());
            }
            case Plan.OneListing one when one.tool() == Plan.Tool.GET_LISTING -> {
                switch (reader.read(one.listingId())) {
                    case ToolResult.Answer<ListingFacts> found -> answer.card(found.value().id())
                            .ifPresentOrElse(card -> answer.blocks.add(AssistantBlock.facts(card, found.value())),
                                    () -> answer.noAnswer(Plan.Reason.LISTING_UNAVAILABLE, one.listingId()));
                    case ToolResult.Refusal<ListingFacts> refusal -> answer.refusal(refusal);
                }
            }
            case Plan.OneListing one -> {
                switch (similar.similar(one.listingId())) {
                    case ToolResult.Answer<List<ListingFacts>> found -> {
                        List<ListingCard> cards = answer.cards(found.value().stream().map(ListingFacts::id).toList());
                        if (cards.isEmpty()) {
                            answer.noAnswer(Plan.Reason.NO_RESULTS, one.listingId());
                        } else {
                            answer.blocks.add(AssistantBlock.listings(cards));
                        }
                    }
                    case ToolResult.Refusal<List<ListingFacts>> refusal -> answer.refusal(refusal);
                }
            }
            case Plan.TooFewToCompare few -> {
                // The one there is, if any, so the user sees what was found; the reason names it.
                List<ListingCard> cards = answer.cards(few.listingIds());
                answer.noAnswer(Plan.Reason.NOT_ENOUGH_LISTINGS, cards.isEmpty() ? null : cards.getFirst().id());
                if (!cards.isEmpty()) {
                    answer.blocks.add(AssistantBlock.listings(cards));
                }
            }
            case Plan.Compare c -> {
                switch (compare.compare(c.listingIds())) {
                    case ToolResult.Answer<CompareListingsTool.Comparison> found -> answer.blocks.add(
                            AssistantBlock.comparison(answer.cards(c.listingIds()), found.value().differences()));
                    case ToolResult.Refusal<CompareListingsTool.Comparison> refusal -> answer.refusal(refusal);
                }
            }
            case Plan.Finance f -> {
                ToolResult<FinancingTool.Simulation> result = f.listingId() != null
                        ? financing.forListing(f.listingId(), f.downPayment(), f.months(), f.ratePercent())
                        : financing.forAmount(f.amount(), f.downPayment(), f.months(), f.ratePercent());
                switch (result) {
                    case ToolResult.Answer<FinancingTool.Simulation> found ->
                            answer.blocks.add(AssistantBlock.financing(found.value()));
                    case ToolResult.Refusal<FinancingTool.Simulation> refusal -> answer.refusal(refusal);
                }
            }
            case Plan.CritAirOf c -> {
                ToolResult<CritAirTool.Estimate> result = c.listingId() != null
                        ? critAir.forListing(c.listingId())
                        : critAir.forCar(c.fuel(), c.year(), c.month());
                switch (result) {
                    case ToolResult.Answer<CritAirTool.Estimate> found ->
                            answer.blocks.add(AssistantBlock.critAir(found.value(), c.listingId()));
                    case ToolResult.Refusal<CritAirTool.Estimate> refusal -> answer.refusal(refusal);
                }
            }
            case Plan.Value v -> {
                switch (valuation.value(v.make(), v.model(), v.fuel(), v.powerHp(), v.year(), v.month(),
                        v.mileageKm(), v.scope())) {
                    case ToolResult.Answer<Valuation> found -> {
                        // Below five comparables there is no figure: said so, with the count and the
                        // loosenings that would reach five, as /la-cote shows them.
                        if (found.value().estimate() == null) {
                            answer.noAnswer(Plan.Reason.NOT_COMPUTABLE, null);
                        }
                        answer.blocks.add(AssistantBlock.valuation(found.value()));
                    }
                    case ToolResult.Refusal<Valuation> refusal -> answer.refusal(refusal);
                }
            }
            case Plan.Help h -> {
                switch (help.search(h.query())) {
                    case ToolResult.Answer<List<HelpIndex.Passage>> found ->
                            answer.blocks.add(AssistantBlock.help(found.value()));
                    case ToolResult.Refusal<List<HelpIndex.Passage>> refusal -> answer.refusal(refusal);
                }
            }
        }
    }

    /**
     * Never out of scope for a question about AutoX itself (A2 fixes, docs/assistant-plan.md, 12):
     * the help pages are searched with the user's words, and a question in the site's own vocabulary
     * that they do not answer is `NOT_IN_HELP`, which points to the help centre.
     */
    private void outOfScope(String message, Building answer) {
        String query = message.length() > HelpIndex.MAX_QUERY ? message.substring(0, HelpIndex.MAX_QUERY) : message;
        if (help.search(query) instanceof ToolResult.Answer<List<HelpIndex.Passage>> found) {
            answer.blocks.add(AssistantBlock.help(found.value()));
        } else if (ABOUT_THE_SITE.matcher(PlanValidator.fold(message)).find()) {
            answer.noAnswer(Plan.Reason.NOT_IN_HELP, null);
        } else {
            answer.noAnswer(Plan.Reason.OUT_OF_SCOPE, null);
        }
    }

    /** Words that put a question on AutoX itself, folded: its name, an account, a listing, a message… */
    private static final java.util.regex.Pattern ABOUT_THE_SITE = java.util.regex.Pattern.compile(
            "\\b(autox|site|compte|annonces?|messager\\w*|messages?|alertes?|favoris?|signal\\w*|mot de passe|"
                    + "connexion|connecter|inscri\\w*|identifiant|vendeurs?|publier|publication|donnees|moderat\\w*|"
                    + "professionnel|pro|la cote|recherche)\\b");

    /** The answer as it is built: blocks in order, the listings shown, the reasons given. */
    private final class Building {

        final List<AssistantBlock> blocks = new ArrayList<>();
        final Set<UUID> shown = new LinkedHashSet<>();
        final Set<String> notUnderstood = new LinkedHashSet<>();
        final List<Plan.Reason> reasons = new ArrayList<>();

        Answer done() {
            return new Answer(List.copyOf(blocks), List.copyOf(shown), List.copyOf(reasons));
        }

        void noAnswer(Plan.Reason reason, UUID listingId) {
            blocks.add(AssistantBlock.noAnswer(reason, listingId));
            reasons.add(reason);
        }

        void refusal(ToolResult.Refusal<?> refusal) {
            noAnswer(switch (refusal.reason()) {
                case LISTING_UNAVAILABLE -> Plan.Reason.LISTING_UNAVAILABLE;
                case NOT_COMPUTABLE -> Plan.Reason.NOT_COMPUTABLE;
                case NOT_FINANCEABLE -> Plan.Reason.NOT_FINANCEABLE;
                case NOTHING_TO_FINANCE -> Plan.Reason.NOTHING_TO_FINANCE;
                case NOT_IN_HELP -> Plan.Reason.NOT_IN_HELP;
            }, refusal.listingId());
        }

        /** Cards for these listings, in order; one gone since is left out. */
        List<ListingCard> cards(List<UUID> ids) {
            List<ListingCard> cards = ids.stream().map(this::card).flatMap(Optional::stream).toList();
            cards.forEach(card -> shown.add(card.id()));
            return cards;
        }

        Optional<ListingCard> card(UUID id) {
            try {
                ListingCard card = ListingCard.from(listings.detail(id));
                shown.add(card.id());
                return Optional.of(card);
            } catch (ApiException e) {
                if (e.getStatus() == HttpStatus.NOT_FOUND) {
                    return Optional.empty();
                }
                throw e;
            }
        }
    }
}
