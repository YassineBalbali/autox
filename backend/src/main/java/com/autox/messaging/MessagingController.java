package com.autox.messaging;

import com.autox.api.PageResponse;
import com.autox.messaging.dto.ConversationDetail;
import com.autox.messaging.dto.ConversationSummary;
import com.autox.messaging.dto.ReportMessageRequest;
import com.autox.messaging.dto.SendMessageRequest;
import com.autox.messaging.dto.UnreadCount;
import com.autox.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Messages between buyers and sellers. Every endpoint requires authentication, and every
 * conversation is visible only to its two participants: one that is not the caller's
 * answers 404. The rules are on {@link MessagingService}.
 *
 * <p>Sending is rate-limited per account, a new conversation more tightly than a reply;
 * over the limit the answer is 429 {@code RATE_LIMITED} with {@code Retry-After}.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Messages", description = "Conversations between a buyer and a seller about a listing. Requires authentication.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
public class MessagingController {

    private static final int MAX_PAGE_SIZE = 50;

    private final MessagingService messaging;

    public MessagingController(MessagingService messaging) {
        this.messaging = messaging;
    }

    /**
     * Writes to a listing's seller. Starts the caller's conversation about it, or continues it
     * if they already have one. 404 if the listing is not online and the caller has no
     * conversation about it yet; 403 for the caller's own listing.
     */
    @PostMapping("/api/v1/listings/{listingId}/conversation")
    public ConversationDetail contactSeller(@AuthenticationPrincipal Jwt token,
                                            @PathVariable UUID listingId,
                                            @Valid @RequestBody SendMessageRequest request) {
        return messaging.contact(CurrentUser.id(token), listingId, request.body());
    }

    /** The caller's conversations, as buyer and as seller, most recent first. */
    @GetMapping("/api/v1/conversations")
    public PageResponse<ConversationSummary> inbox(
            @AuthenticationPrincipal Jwt token,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return messaging.inbox(CurrentUser.id(token), page, size);
    }

    /** How many conversations have a message the caller has not opened. For the header badge. */
    @GetMapping("/api/v1/conversations/unread")
    public UnreadCount unread(@AuthenticationPrincipal Jwt token) {
        return messaging.unread(CurrentUser.id(token));
    }

    /**
     * One conversation with its latest messages. Reading it does not mark it read; that is
     * {@code POST /conversations/{id}/read}, so a GET changes nothing.
     */
    @GetMapping("/api/v1/conversations/{id}")
    public ConversationDetail conversation(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return messaging.conversation(CurrentUser.id(token), id);
    }

    /** Writes in a conversation. 409 once it is closed: see {@code canReply}. */
    @PostMapping("/api/v1/conversations/{id}/messages")
    public ConversationDetail reply(@AuthenticationPrincipal Jwt token,
                                    @PathVariable UUID id,
                                    @Valid @RequestBody SendMessageRequest request) {
        return messaging.reply(CurrentUser.id(token), id, request.body());
    }

    /** Marks every message in the conversation read for the caller. */
    @PostMapping("/api/v1/conversations/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        messaging.markRead(CurrentUser.id(token), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reports a message the caller received to the moderators. Once per message: reporting it
     * again is accepted and changes nothing. A message cannot be edited or deleted, by anyone.
     */
    @PostMapping("/api/v1/messages/{id}/report")
    public ResponseEntity<Void> report(@AuthenticationPrincipal Jwt token,
                                       @PathVariable UUID id,
                                       @Valid @RequestBody ReportMessageRequest request) {
        messaging.report(CurrentUser.id(token), id, request);
        return ResponseEntity.noContent().build();
    }
}
