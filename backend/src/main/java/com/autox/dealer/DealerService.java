package com.autox.dealer;

import com.autox.api.error.ApiException;
import com.autox.demo.DemoAccounts;
import com.autox.messaging.MessagingService;
import com.autox.messaging.dto.ConversationDetail;
import com.autox.storage.ObjectStorage;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The directory of professionals and their public pages.
 *
 * <p>Who the public sees is {@link DealerRepository}'s rule. A page the public cannot see —
 * hidden by an administrator, or an account suspended or deleted — answers 404, as a listing
 * that is offline does. Two people still see a hidden page: the dealer, so they know why it is
 * gone, and administrators. A suspended account cannot be signed in to, so its dealer never
 * gets that far.
 */
@Service
@Transactional(readOnly = true)
public class DealerService {

    static final int MAX_PAGE_SIZE = 48;

    private final DealerRepository dealers;
    private final ObjectStorage storage;
    private final DemoAccounts demoAccounts;
    private final MessagingService messaging;

    DealerService(DealerRepository dealers, ObjectStorage storage, DemoAccounts demoAccounts, MessagingService messaging) {
        this.dealers = dealers;
        this.storage = storage;
        this.demoAccounts = demoAccounts;
        this.messaging = messaging;
    }

    public DealerDirectory directory(DealerRepository.Filter filter, DealerSort sort, int page, int size) {
        long total = dealers.count(filter);
        var items = dealers.directory(filter, sort, size, (long) page * size).stream()
                .map(row -> new DealerCard(row.slug(), row.name(), logoUrl(row), row.city(), row.postalCode(),
                        row.departement(), row.saleListings(), row.leasingListings(), row.memberSince()))
                .toList();
        int pages = (int) ((total + size - 1) / size);
        return new DealerDirectory(items, page, size, total, pages, page + 1 < pages, dealers.departements());
    }

    /**
     * @param viewerId the caller, or null when signed out
     */
    public DealerPage page(String slug, UUID viewerId, boolean viewerAdmin) {
        DealerRepository.DealerRow row = dealers.bySlug(slug).orElseThrow(() -> notFound(slug));
        boolean own = row.userId().equals(viewerId);
        boolean visible = row.publicAccount() && row.hiddenAt() == null;
        if (!visible && !own && !viewerAdmin) {
            throw notFound(slug);
        }
        boolean insider = own || viewerAdmin;
        return new DealerPage(
                row.slug(),
                row.userId(),
                row.name(),
                row.companyName() == null || row.companyName().equalsIgnoreCase(row.name()) ? null : row.companyName(),
                logoUrl(row),
                row.city(),
                row.postalCode(),
                row.departement(),
                row.description(),
                row.openingHours(),
                row.websiteUrl(),
                // The dealer's choice, and only the company's contact number: never the account's own.
                row.showPhone() ? row.contactPhone() : null,
                row.memberSince(),
                row.saleListings(),
                row.leasingListings(),
                demoAccounts.isDemoAccount(row.userId()),
                own,
                insider ? row.hiddenAt() : null,
                insider ? row.hiddenReason() : null);
    }

    /** A visitor writes to a dealer from their page, which has to be one the public can see. */
    @Transactional
    public ConversationDetail contact(String slug, UUID buyerId, String body) {
        DealerRepository.DealerRow row = dealers.bySlug(slug)
                .filter(dealer -> dealer.publicAccount() && dealer.hiddenAt() == null)
                .orElseThrow(() -> notFound(slug));
        return messaging.contactDealer(buyerId, row.userId(), body);
    }

    private String logoUrl(DealerRepository.DealerRow row) {
        return row.logoKey() == null ? null : storage.readUrl(row.logoKey());
    }

    private static ApiException notFound(String slug) {
        return ApiException.notFound("Dealer " + slug);
    }
}
