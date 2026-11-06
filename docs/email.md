# Transactional email

All French, plain text plus simple HTML, sent through Brevo's transactional API in production and
written to the log locally: password reset, address verification, new-message notifications, and
saved searches' daily e-mail (`docs/saved-searches.md`).

## The outbox

Anything that sends mail writes a row to `outbox_email` (V22) in the same transaction as the change
it reports. `EmailOutbox.enqueue` is `MANDATORY`, so a call with no transaction fails rather than
writing mail with nothing behind it. No request calls Brevo.

`OutboxPoller` ticks every 30 seconds, claims up to 20 due rows with
`SELECT … FOR UPDATE SKIP LOCKED`, sends them, and marks them SENT.

**On failure:** the error goes in `last_error` and the row waits 1, 5, 15 or 60 minutes. The fifth
failed attempt makes it FAILED. `BACKOFF` also holds your 6-hour delay, which nothing reaches while
`MAX_ATTEMPTS` is 5: five attempts have four waits between them. Raising the cap to 6 is what would
use it.

### Three things that are not obvious

**A claim is a lease, not a held lock.** The locks last for one statement, which moves the rows'
`next_attempt_at` forward ten minutes and commits. Holding them through the send would keep a
database connection open across up to twenty calls to Brevo on a pool of five. Worse, the failure
update has to run in `REQUIRES_NEW` so that it survives the exception, and with the outer
transaction still holding the row's lock that update would wait for it for ever. An instance that
dies mid-send leaves its rows to come due when the lease ends, with an attempt already counted, so
delivery is at least once. The row's id goes to Brevo as `IdempotencyKey`, the same on every
attempt, to make a repeat less likely to arrive twice.

**A tick does not always touch the database.** The free Neon plan covers about 400 of a month's 730
hours, so it has to sleep ("Keeping Neon inside its allowance" in `docs/deployment.md`), and the
uptime monitor keeps the API awake all month. A query every 30 seconds would keep Neon awake too and
spend the allowance in about two weeks. So a tick asks the database only when:

- a transaction in this process has committed mail, which `enqueue` signals after the commit;
- a retry has come due, a time the poller reads from the table after each poll;
- it is the first tick after a start, which picks up what was left waiting;
- `autox.mail.idle-check` (six hours) has passed, for what another instance left behind.

From the outside it behaves as a 30-second poll. Mail from a live request waits at most one tick.

**A body can be a credential.** A reset or verification link's token is stored only as a hash, but
the email has to hold the real one until it is sent. So `body_text` and `body_html` are cleared when
a row becomes SENT or FAILED, and a link sits in the table only for as long as it may still go out.
Rows themselves are deleted after 90 days: the dashboard reads 30, and an address is personal data.

## What is logged

The poller logs the outbox id, the type, the attempt and the error: never a body, a token or an
address. A failure's text comes from Brevo's status and error message. An exception of any other
kind is stored by its type only, since its message could say anything.

**The one exception is `LoggingEmailSender`,** which writes the whole text body, link included,
because that is what makes the flows walkable locally. It is the default, and it warns at every
startup. It must not run in production, where anyone who can read the log could take over any
account.

## Verifying an address

Sign-up writes a single-use token, stored hashed, valid for 24 hours, and its email. Opening the
link loads `/verifier-email`, which posts the token to `POST /api/v1/auth/email/verify`. The GET
spends nothing, so a mail scanner fetching the link does not use it up. It sets `email_verified_at`
and makes the account ACTIVE. A new link replaces the last one. "Renvoyer le lien" is
`POST /api/v1/me/email/verification`, three per account per hour.

Until then, `EmailVerificationService.requireVerified` answers 403 `EMAIL_NOT_VERIFIED` on publishing
or renewing a listing, starting a conversation and replying. It reads the account, not the access
token, so a click takes effect at once. A draft can still be saved, so nothing is lost. Favourites
are kept in the browser and the API never sees them. Admin pages are not blocked.

**Who is held back.** `app_user.email_verification_required`, set by sign-up only. Status could not
tell them apart: every existing account reads `PENDING_VERIFICATION` too. V22 leaves the column
false for every account that exists, and the demo seed never sets it. Those accounts keep working,
and existing ones see the banner on the account page and can verify when they like.

A suspended account's address can be verified, but its status stays SUSPENDED. Reactivation then
restores ACTIVE rather than the `PENDING_VERIFICATION` it was suspended from.

## New-message notifications

To the recipient, never the sender. From AutoX, with no Reply-To, so a reply cannot reach the other
user. The email says who wrote (display name), about which listing, and links to the conversation,
which is where it says to reply. The listing is named as on the result cards, by `ListingLabel`:
make, model and version, or the seller's title when there is no make.
It holds neither the message's text nor an address. A display name or a title that contains an
address is replaced by "Un utilisateur" or "une annonce". Both are typed by other users, so they are
escaped in the HTML and lose their line breaks in the subject.

**At most one per conversation per person per 15 minutes, checked against the outbox.** Per person
because a limit on the conversation alone would swallow a seller's quick reply: the buyer's message
would have used it up. Two messages at the same moment cannot both pass, since writing the message
updates the conversation's row, which makes the second transaction wait for the first. A later
message in the window sends nothing, then or afterwards; the unread count on the site still shows it.

A demo seller gets nothing, because their address is invented and a bounce costs the sender's
reputation. Neither does a suspended or closed account.

## Delivery in practice

One real verification email has been sent through Brevo, on 4 November 2026, from the local stack
to a Gmail address, with `AUTOX_MAIL_FROM` set to a verified Gmail sender.

- It arrived in the inbox, not in spam. That is one message to one Gmail mailbox, not a measure of
  deliverability elsewhere.
- **Brevo rewrote the sender** to `<sender>@<id>.brevosend.com`. So the address recipients see is
  not the one in `AUTOX_MAIL_FROM`.

### Click tracking

Brevo rewrites every link in a transactional e-mail to go through its own tracking address, the
links holding a verification or reset token included. **It cannot be turned off per message.** Brevo's
send API has no field for it, and its custom headers only take non-standard ones. The setting is per
account:

- **Brevo → Transactional → Settings → Tracking**: on the plans below Enterprise, this offers only
  **Anonymous e-mail tracking**. That stops clicks being tied to a recipient, but it does not stop the
  rewriting.
- **Turning tracking off altogether:** according to Brevo's product team on its community forum
  (May 2024), this is available on request and on Enterprise plans only. Ask Brevo support, from the
  account.

Read in November 2026, from Brevo's API reference and its community forum, not from its help pages;
check it in the account. What this means here: a token passes through Brevo's redirect, and a mail
scanner or a link preview that follows the redirect does not spend it, because the pages spend a
token on a POST, not on opening the link.

## Changing this

The wording is in `EmailTemplates`. Every email ends with "Cet e-mail est envoyé automatiquement,
merci de ne pas y répondre." The link paths are in `EmailVerificationService` and
`NewMessageNotifier`, and they have to match the Angular routes: a mismatch produces a link that
loads the application and renders nothing.
