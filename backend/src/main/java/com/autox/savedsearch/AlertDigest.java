package com.autox.savedsearch;

import com.autox.mail.EmailOutbox;
import com.autox.mail.EmailTemplates;
import com.autox.mail.EmailType;
import com.autox.search.ListingLabel;
import com.autox.search.ListingSearchRepository;
import com.autox.search.SearchSort;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One account's daily e-mail: what is new in each of its active searches.
 *
 * <p>One transaction for one account, so the e-mail, the record of what it holds and the move of
 * each search's date all commit together or not at all. A crash cannot send a listing twice or lose
 * one.
 *
 * <p><b>Why the date is not enough, and {@code saved_search_sent} is needed.</b> A listing's
 * {@code published_at} is set before its transaction commits, so a listing can become visible with a
 * date a little earlier than a digest that did not see it. Each run therefore looks back
 * {@link #OVERLAP} further, and the table is what stops that sending anything twice.
 */
@Service
public class AlertDigest {

    private static final Logger log = LoggerFactory.getLogger(AlertDigest.class);

    /** The most listings one search shows in an e-mail. */
    static final int LISTINGS_PER_SEARCH = 10;

    /** Longer than a publication's transaction can take. */
    static final Duration OVERLAP = Duration.ofMinutes(5);

    private static final int TOTAL_CAP = 999;

    private final SavedSearchRepository repository;
    private final ResolvedSearch.Resolver resolver;
    private final ListingSearchRepository search;
    private final EmailOutbox outbox;
    private final UnsubscribeToken tokens;
    private final SavedSearchLinks links;

    public AlertDigest(SavedSearchRepository repository, ResolvedSearch.Resolver resolver,
                       ListingSearchRepository search, EmailOutbox outbox, UnsubscribeToken tokens,
                       SavedSearchLinks links) {
        this.repository = repository;
        this.resolver = resolver;
        this.search = search;
        this.outbox = outbox;
        this.tokens = tokens;
        this.links = links;
    }

    /**
     * Writes this account's digest to the outbox, if there is anything to say.
     *
     * @param oncePerDay false only for an administrator sending themselves one to test it
     * @return whether an e-mail was written
     */
    @Transactional
    public boolean digestFor(UUID userId, boolean oncePerDay) {
        SavedSearchRepository.Recipient recipient = repository.lockRecipient(userId).orElse(null);
        if (recipient == null || (oncePerDay && repository.digestWrittenToday(recipient.email()))) {
            return false;
        }

        List<EmailTemplates.DigestSection> sections = new ArrayList<>();
        for (SavedSearchRepository.Row row : repository.activeFor(userId)) {
            ResolvedSearch resolved = resolver.resolve(row);
            if (!resolved.readable()) {
                // Its date stays, so nothing is lost if a later version can read it.
                log.warn("Saved search {} could not be read and was left out of the digest", row.id());
                continue;
            }

            Instant since = latest(row.lastDigestAt().minus(OVERLAP), row.createdAt());
            List<ListingSearchRepository.SearchRow> listings = search.publishedSince(
                    resolved.criteria(), SearchSort.NEWEST, since, userId, row.id(), LISTINGS_PER_SEARCH);
            repository.markDigested(row.id());
            if (listings.isEmpty()) {
                continue;
            }

            int total = search.countPublishedSince(resolved.criteria(), since, userId, row.id(), TOTAL_CAP);
            repository.recordSent(row.id(), listings.stream().map(ListingSearchRepository.SearchRow::id).toList());
            sections.add(new EmailTemplates.DigestSection(
                    row.name(),
                    total,
                    listings.stream().map(this::item).toList(),
                    links.results(resolved.linkParams()),
                    links.unsubscribe(tokens.sign(row.id()))));
        }

        if (sections.isEmpty()) {
            return false;
        }
        outbox.enqueue(new EmailOutbox.Draft(recipient.email(), EmailType.SAVED_SEARCH_DIGEST,
                EmailTemplates.savedSearchDigest(sections, links.mySearches())));
        return true;
    }

    private EmailTemplates.DigestItem item(ListingSearchRepository.SearchRow row) {
        return new EmailTemplates.DigestItem(
                ListingLabel.of(row.make(), row.model(), row.version(), row.title()),
                facts(row),
                links.listing(row.id()),
                row.leasing() != null);
    }

    /** What a result card says about a listing, on one line. */
    private static String facts(ListingSearchRepository.SearchRow row) {
        List<String> facts = new ArrayList<>();
        facts.add(row.leasing() != null
                ? "à partir de " + money(row.leasing().monthlyPayment(), row.currencyCode()) + "/mois"
                : money(row.priceAmount(), row.currencyCode()));
        facts.add(String.valueOf(row.firstRegistrationYear()));
        facts.add(integer(row.mileageKm()) + " km");
        if (row.city() != null) {
            facts.add(row.city());
        }
        return String.join(" · ", facts);
    }

    private static String money(long minorUnits, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.FRANCE);
        format.setCurrency(currency);
        int digits = Math.max(currency.getDefaultFractionDigits(), 0);
        double amount = minorUnits / Math.pow(10, digits);
        format.setMaximumFractionDigits(minorUnits % (long) Math.pow(10, digits) == 0 ? 0 : digits);
        format.setMinimumFractionDigits(format.getMaximumFractionDigits());
        return format.format(amount);
    }

    private static String integer(int value) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(value);
    }

    private static Instant latest(Instant one, Instant other) {
        return one.isAfter(other) ? one : other;
    }
}
