package com.autox.messaging;

import com.autox.api.PageResponse;
import com.autox.api.error.ApiException;
import com.autox.messaging.dto.ConversationDetail;
import com.autox.messaging.dto.ConversationSummary;
import com.autox.messaging.dto.ReportMessageRequest;
import com.autox.messaging.dto.ReportedMessage;
import com.autox.messaging.dto.UnreadCount;
import com.autox.search.ListingSearchRepository;
import com.autox.storage.ObjectStorage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Messages between a buyer and a seller about one listing.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>A buyer starts a conversation, about a listing that is online and not their own.
 *       There is one per listing and buyer; contacting the seller again continues it.</li>
 *   <li>Both sides then write in it while the listing has not been taken down or deleted
 *       and neither account is closed or suspended. Sold, paused or expired leaves it open.</li>
 *   <li>Nobody else can read it — an administrator included, except the messages around one
 *       that was reported to them.</li>
 *   <li>The other party is a display name and a role. No email address and no phone number is
 *       in any response: buyer and seller reach each other only through here.</li>
 *   <li>A sent message is never changed or deleted (the database refuses it too). The
 *       recipient can report it; a moderator can hide it, and then neither side sees its text.</li>
 * </ul>
 *
 * <p>A conversation that is not the caller's answers 404, not 403: whether a conversation
 * exists is not something a stranger should be able to learn.
 */
@Service
@Transactional(readOnly = true)
public class MessagingService {

    /** The most messages a conversation returns, the latest ones. */
    static final int MESSAGES_SHOWN = 200;

    /** Messages shown either side of a reported one. */
    static final int REPORT_CONTEXT = 5;

    /** How much of the last message an inbox line shows. */
    static final int PREVIEW_LENGTH = 140;

    private final MessagingRepository repository;
    private final MessageThrottle throttle;
    private final ListingSearchRepository listings;
    private final ObjectStorage storage;

    public MessagingService(MessagingRepository repository, MessageThrottle throttle,
                            ListingSearchRepository listings, ObjectStorage storage) {
        this.repository = repository;
        this.throttle = throttle;
        this.listings = listings;
        this.storage = storage;
    }

    // ---- writing ----------------------------------------------------------------------

    /**
     * A buyer writes to a listing's seller: starts the conversation, or continues it if they
     * already have one about this listing.
     */
    @Transactional
    public ConversationDetail contact(UUID buyerId, UUID listingId, String body) {
        MessagingRepository.ContactTarget listing = repository.contactTarget(listingId)
                .orElseThrow(() -> ApiException.notFound("Listing " + listingId));
        if (listing.sellerId().equals(buyerId)) {
            throw ApiException.forbidden("This is your own listing.");
        }

        var existing = repository.conversationId(listingId, buyerId);
        if (existing.isPresent()) {
            return reply(buyerId, existing.get(), body);
        }
        // Only an online listing can be written about for the first time. Offline it answers
        // 404, as the public listing page does.
        if (!listing.online()) {
            throw ApiException.notFound("Listing " + listingId);
        }
        requireOpenAccount(buyerId);
        String text = clean(body);
        allow(buyerId, true);

        Instant now = Instant.now();
        UUID conversationId = repository.createConversation(listingId, buyerId, listing.sellerId(), now);
        repository.addMessage(conversationId, buyerId, text, now);
        return conversation(buyerId, conversationId);
    }

    /** Either side writes in a conversation they are part of. */
    @Transactional
    public ConversationDetail reply(UUID senderId, UUID conversationId, String body) {
        MessagingRepository.Header header = participantHeader(senderId, conversationId);
        if (!header.canReply()) {
            throw ApiException.conflict("This conversation is closed: the listing was taken down, "
                    + "or one of the accounts is no longer open.");
        }
        requireOpenAccount(senderId);
        String text = clean(body);
        allow(senderId, false);

        repository.addMessage(conversationId, senderId, text, Instant.now());
        return conversation(senderId, conversationId);
    }

    /** Marks everything in the conversation read for the caller. Separate from reading it, so a GET changes nothing. */
    @Transactional
    public void markRead(UUID userId, UUID conversationId) {
        participantHeader(userId, conversationId);
        repository.markRead(conversationId, userId, Instant.now());
    }

    /**
     * The recipient reports a message. One report per message and person: reporting it again
     * changes nothing and is not an error.
     */
    @Transactional
    public void report(UUID reporterId, UUID messageId, ReportMessageRequest request) {
        MessagingRepository.MessageOwner owner = repository.messageOwner(messageId)
                .orElseThrow(() -> ApiException.notFound("Message " + messageId));
        participantHeader(reporterId, owner.conversationId());
        if (owner.senderId().equals(reporterId)) {
            throw ApiException.forbidden("You cannot report your own message.");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (request.reason() == MessageReportReason.OTHER && note == null) {
            throw ApiException.validationFailed("A note is required when the reason is OTHER.");
        }
        repository.report(messageId, reporterId, request.reason(), note, Instant.now());
    }

    // ---- reading ----------------------------------------------------------------------

    public PageResponse<ConversationSummary> inbox(UUID userId, int page, int size) {
        List<MessagingRepository.InboxRow> rows = repository.inbox(userId, size, (long) page * size);
        Map<UUID, String> covers = covers(rows.stream().map(row -> row.header().listingId()).toList());
        List<ConversationSummary> items = rows.stream().map(row -> summary(userId, row, covers)).toList();
        return PageResponse.of(new PageImpl<>(items, PageRequest.of(page, size), repository.countConversations(userId)),
                item -> item);
    }

    public UnreadCount unread(UUID userId) {
        return new UnreadCount(repository.unreadConversations(userId));
    }

    public ConversationDetail conversation(UUID userId, UUID conversationId) {
        MessagingRepository.Header header = participantHeader(userId, conversationId);
        // One more than shown, to know whether any were left out.
        List<MessagingRepository.MessageRow> rows = repository.messages(conversationId, userId, MESSAGES_SHOWN + 1);
        boolean omitted = rows.size() > MESSAGES_SHOWN;
        List<MessagingRepository.MessageRow> shown = omitted ? rows.subList(1, rows.size()) : rows;

        return new ConversationDetail(
                header.id(),
                roleOf(userId, header),
                listingRef(header, covers(List.of(header.listingId()))),
                party(counterpartOf(userId, header)),
                header.canReply(),
                shown.stream().map(row -> new ConversationDetail.MessageView(
                        row.id(),
                        row.senderId().equals(userId),
                        row.hidden() ? null : row.body(),
                        row.hidden(),
                        row.reportedByReader(),
                        row.sentAt())).toList(),
                omitted);
    }

    // ---- moderation -------------------------------------------------------------------

    public PageResponse<ReportedMessage> openReports(int page, int size) {
        List<MessagingRepository.ReportRow> rows = repository.openReports(size, (long) page * size);
        List<ReportedMessage> items = rows.stream().map(row -> {
            MessagingRepository.Header header = repository.header(row.conversationId()).orElseThrow();
            return new ReportedMessage(
                    row.id(), row.reason(), row.note(), row.reportedAt(),
                    party(row.reporter()), party(row.sender()),
                    listingRef(header, covers(List.of(header.listingId()))),
                    row.messageId(),
                    repository.context(row.conversationId(), row.messageId(), REPORT_CONTEXT).stream()
                            .map(context -> new ReportedMessage.ContextMessage(
                                    context.id(), context.senderId(), context.body(), context.hidden(),
                                    context.id().equals(row.messageId()), context.sentAt()))
                            .toList());
        }).toList();
        return PageResponse.of(new PageImpl<>(items, PageRequest.of(page, size), repository.countOpenReports()),
                item -> item);
    }

    /** Hides the reported message from both sides and closes its reports. */
    @Transactional
    public void hide(UUID moderatorId, UUID reportId) {
        UUID messageId = openReport(reportId);
        Instant now = Instant.now();
        repository.hide(messageId, moderatorId, now);
        repository.resolveReports(messageId, MessageReportResolution.HIDDEN, moderatorId, now);
    }

    /** Closes the reports on a message without hiding it. */
    @Transactional
    public void dismiss(UUID moderatorId, UUID reportId) {
        UUID messageId = openReport(reportId);
        repository.resolveReports(messageId, MessageReportResolution.DISMISSED, moderatorId, Instant.now());
    }

    // ---- helpers ----------------------------------------------------------------------

    private UUID openReport(UUID reportId) {
        MessagingRepository.ReportTarget target = repository.reportTarget(reportId)
                .orElseThrow(() -> ApiException.notFound("Report " + reportId));
        if (!target.open()) {
            throw ApiException.conflict("This report has already been dealt with.");
        }
        return target.messageId();
    }

    private MessagingRepository.Header participantHeader(UUID userId, UUID conversationId) {
        return repository.header(conversationId)
                .filter(header -> header.hasParticipant(userId))
                .orElseThrow(() -> ApiException.notFound("Conversation " + conversationId));
    }

    /**
     * An access token outlives a suspension by up to its lifetime, so the account is checked
     * here rather than trusted from the token.
     */
    private void requireOpenAccount(UUID userId) {
        if (!repository.accountOpen(userId)) {
            throw ApiException.accountSuspended();
        }
    }

    private void allow(UUID senderId, boolean startsConversation) {
        MessageThrottle.Decision decision = throttle.record(senderId, startsConversation);
        if (!decision.allowed()) {
            throw ApiException.rateLimited(decision.retryAfterSeconds());
        }
    }

    /** Trailing and leading blank lines go; the text in between is kept exactly as typed. */
    private static String clean(String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw ApiException.validationFailed("A message cannot be empty.");
        }
        return text;
    }

    private ConversationSummary summary(UUID userId, MessagingRepository.InboxRow row, Map<UUID, String> covers) {
        MessagingRepository.Header header = row.header();
        ConversationSummary.LastMessage last = row.lastSentAt() == null ? null : new ConversationSummary.LastMessage(
                row.lastHidden() ? null : preview(row.lastBody()),
                userId.equals(row.lastSenderId()),
                row.lastHidden(),
                row.lastSentAt());
        return new ConversationSummary(
                header.id(),
                roleOf(userId, header),
                listingRef(header, covers),
                party(counterpartOf(userId, header)),
                last,
                row.unread(),
                header.canReply(),
                header.lastMessageAt());
    }

    private static String preview(String body) {
        String flat = body.replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_LENGTH ? flat : flat.substring(0, PREVIEW_LENGTH - 1).strip() + "…";
    }

    private static ConversationRole roleOf(UUID userId, MessagingRepository.Header header) {
        return header.buyerId().equals(userId) ? ConversationRole.BUYER : ConversationRole.SELLER;
    }

    private static MessagingRepository.PartyRow counterpartOf(UUID userId, MessagingRepository.Header header) {
        return header.buyerId().equals(userId) ? header.seller() : header.buyer();
    }

    private static ConversationSummary.Party party(MessagingRepository.PartyRow row) {
        return new ConversationSummary.Party(row.id(), row.displayName(), row.role());
    }

    private static ConversationSummary.ListingRef listingRef(MessagingRepository.Header header, Map<UUID, String> covers) {
        return new ConversationSummary.ListingRef(header.listingId(), header.title(), header.priceAmount(),
                header.currencyCode(), header.online(), covers.get(header.listingId()));
    }

    /** The smallest version of each listing's first photo, in one query. */
    private Map<UUID, String> covers(List<UUID> listingIds) {
        Map<UUID, String> urls = new java.util.HashMap<>();
        listings.findCoverImages(listingIds).forEach((listingId, variants) -> {
            ListingSearchRepository.CoverVariant thumbnail = variants.getOrDefault("THUMBNAIL", variants.get("CARD"));
            if (thumbnail != null) {
                urls.put(listingId, storage.readUrl(thumbnail.storageKey()));
            }
        });
        return urls;
    }
}
