# Messages between buyers and sellers

A buyer writes to a seller from the listing page ("Contacter le vendeur"). The seller
answers in their inbox at `/messages`, where the buyer reads the answer. Everything goes
through AutoX: the seller's email address and phone number are never shown, and the buyer's
are never passed on. There are no email notifications yet. The header's "Messages" link
shows how many conversations are waiting.

The code is in `backend/src/main/java/com/autox/messaging/`, with the rules on
`MessagingService`, and in `frontend/src/app/features/messages/`.

## The rules

| | |
|---|---|
| Who starts a conversation | A signed-in buyer, about a listing that is online and not their own |
| How many | One per listing and buyer. Writing again from the listing page continues it |
| Who writes in it | Both sides, while the listing has not been taken down or deleted and neither account is closed or suspended. Sold, paused or expired leaves it open: that is when buyer and seller have most to say to each other |
| Who reads it | Its two participants. Anyone else gets 404, not 403, so whether a conversation exists is not something a stranger can find out |
| What the other side is | A display name and whether it is a trade seller. No email address and no phone number appears in any response |
| A message | Plain text, 1 to 2,000 characters, shown exactly as typed and never as markup |
| Changing or deleting one | Impossible, for anyone. No endpoint does it, and a database trigger refuses any change to a sent message's text, sender or time |
| Reporting one | Its recipient can, once, with a reason (scam, abuse, spam, other) and a note, which is required for "other". The reporter's side shows "Signalé"; the sender is not told |

A user who types their own phone number or address into a message can do so. It is their
choice to share it, as on the large French classifieds sites. The composer on the listing
page warns about scams instead, and so does the reply box: a buyer is warned against
paying for a car before seeing it, and a seller against handing it over before the payment
has cleared, including bank cheques, which are the classic scam against sellers.

## Unread

A conversation is unread for a person when it has a message from the other side, sent
after they last marked it read, that a moderator has not hidden. Reading a conversation
(`GET /conversations/{id}`) changes nothing. Marking it read is a separate call, `POST
/conversations/{id}/read`, which the inbox makes when a conversation is opened, so a GET
never has a side effect. Sending a message marks the sender as having read up to it.

The header's number counts **conversations**, not messages, since a conversation is what a
person has to act on. It is asked for once a minute, only while the tab is visible, and as
soon as the tab becomes visible again. There is no push channel. A minute is an acceptable
delay for someone waiting about a car, and a hidden tab polling all day would be load with
nobody watching. The inbox reloads when that number changes, and an open conversation
reloads when the list shows a message newer than the ones on screen.

## Rate limits

Per account, on the same `throttle_counter` table as the other throttles. They are keyed by
user id, not address, because sending needs an account and an account is what a spammer has
to spend.

| Scope | Default | Counts |
|---|---|---|
| `MESSAGE_SEND` | 20 per 10 minutes | Every message |
| `CONVERSATION_START` | 10 per day | Every new conversation, which also counts as a message |

Over either limit: 429 `RATE_LIMITED` with `Retry-After`, which the site words as "Vous avez
envoyé beaucoup de messages en peu de temps", followed by how long to wait. A message is
counted only once it has passed every other check, so an empty message, or one about a
listing that went offline, costs nothing.

**The windows are fixed, not "quiet for the window".** The other throttles restart a count
after a gap of a whole window with no events. That suits traffic that should stop, but it
would block a seller who answers a buyer steadily every few minutes: they are never quiet
for ten minutes, so by the end of the day they would be over the limit at a perfectly normal
rate. `ThrottleCounters.recordInFixedWindow` restarts the count once the window that began
with the first event has run, however busy it was. The trade-off is that a burst straddling
two windows can reach twice the limit; for a cap on spam that is acceptable.

The numbers are in `application.yml`, under `autox.messaging.throttle`.
`AUTOX_MESSAGING_THROTTLE_ENABLED` turns both off. Unlike the per-address throttles, they
stay on in the test suite, because each test registers its own accounts and so cannot
collide with another test's count.

## Moderation

A report joins the moderators' queue at `/admin/moderation/messages`, the fourth tab of the
moderation screen. Each report shows the reason, the note, and up to five messages either
side of the reported one, which is the context needed to judge it. **A moderator sees a
conversation only through a report on it.** No endpoint lets an administrator read
conversations nobody has reported.

There are two decisions, and either one closes every open report on that message:

- **Masquer le message.** Both sides now see "Message masqué par la modération" in its
  place. The text is kept in the database for the record. Hidden is not deleted.
- **Classer sans suite.** The message stays as it was.

A message already dealt with answers 409 if a second moderator acts on it, and the queue
says so on the card.

## What is stored

| Table | |
|---|---|
| `conversation` | One per (listing, buyer), unique. The seller is copied from the listing, which never changes hands, so a seller's inbox is one index range. `buyer_read_at` and `seller_read_at` hold how far each side has read |
| `message` | Immutable apart from `hidden_at` and `hidden_by`. `ck_message_body` enforces 1 to 2,000 characters |
| `message_report` | One per (message, reporter), with the resolution, `HIDDEN` or `DISMISSED`, and who resolved it and when |

Conversations cascade from the listing. A listing row is only ever hard-deleted wholesale,
by the demo seed's removal; a seller deleting a listing soft-deletes it, and its
conversations stay readable but closed.

## The API

| | |
|---|---|
| `POST /listings/{id}/conversation` | Write to the seller: starts the conversation, or continues it |
| `GET /conversations` | The caller's conversations, as buyer and as seller, most recent first, paginated |
| `GET /conversations/unread` | `{ conversations }`: how many have something unread |
| `GET /conversations/{id}` | The latest 200 messages, oldest first. `earlierOmitted` says when there are more |
| `POST /conversations/{id}/messages` | Reply. 409 once the conversation is closed: see `canReply` |
| `POST /conversations/{id}/read` | Mark everything in it read |
| `POST /messages/{id}/report` | Report a received message |
| `GET /admin/message-reports` | Open reports, oldest first, with context. ADMIN |
| `POST /admin/message-reports/{id}/hide`·`dismiss` | Decide. ADMIN |

## Not built yet

- **Notifications.** Nothing is emailed: a seller learns of a message only by visiting
  the site. This is the biggest gap left, and it depends on the real mail provider
  (`deferred.md`).
- **Blocking a user**, and a way to leave or archive a conversation.
- **Attachments**, and showing the other side's typing or read status.
- **Older messages.** A conversation shows its latest 200; there is no way to load the
  ones before.
- **The demo sellers cannot answer.** Their passwords are random and never shown, so a
  visitor who writes to a demo listing gets no reply. The banner already says the vehicles
  are not for sale. Answering as a demo seller would need a demo login, which would be a
  credential in the repository.
