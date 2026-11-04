package com.autox.messaging;

import com.autox.demo.DemoAccounts;
import com.autox.mail.EmailOutbox;
import com.autox.mail.EmailTemplates;
import com.autox.mail.EmailType;
import com.autox.mail.MailProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the other party that a message has arrived.
 *
 * <p>Written in the sender's transaction, so a message that rolls back tells nobody. At most one
 * email per person per conversation in {@link #THROTTLE}, and the outbox is what remembers: this
 * has to hold across a restart and across instances. Per person rather than per conversation
 * alone, or a seller's quick reply would never reach the buyer whose message they had just been
 * told about.
 *
 * <p>A demo account's address is invented, so it gets nothing: a bounce costs the sender's
 * reputation with the provider.
 */
@Service
public class NewMessageNotifier {

    static final Duration THROTTLE = Duration.ofMinutes(15);

    private final MessagingRepository repository;
    private final EmailOutbox outbox;
    private final DemoAccounts demoAccounts;
    private final MailProperties mail;

    public NewMessageNotifier(MessagingRepository repository, EmailOutbox outbox, DemoAccounts demoAccounts,
                       MailProperties mail) {
        this.repository = repository;
        this.outbox = outbox;
        this.demoAccounts = demoAccounts;
        this.mail = mail;
    }

    /** Call after the message is written: that write is what serialises two senders here. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void messageSent(UUID conversationId, UUID senderId) {
        MessagingRepository.Header header = repository.header(conversationId).orElseThrow();
        boolean fromBuyer = header.buyerId().equals(senderId);
        MessagingRepository.PartyRow sender = fromBuyer ? header.buyer() : header.seller();
        MessagingRepository.PartyRow recipient = fromBuyer ? header.seller() : header.buyer();
        if (recipient.id().equals(senderId) || demoAccounts.isDemoAccount(recipient.id())) {
            return;
        }

        String address = repository.notifiableEmail(recipient.id()).orElse(null);
        if (address == null || outbox.notifiedSince(conversationId, address, THROTTLE)) {
            return;
        }
        outbox.enqueue(new EmailOutbox.Draft(address, EmailType.NEW_MESSAGE,
                EmailTemplates.newMessage(sender.displayName(), header.title(),
                        mail.siteUrl() + "/messages/" + conversationId),
                conversationId));
    }
}
