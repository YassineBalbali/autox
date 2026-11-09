# The footer's pages

The pages linked from the footer live in `frontend/src/app/features/info/`, one component each,
all inside `InfoPage` (title, introduction, and for the four legal ones the revision date, the
note "Ce texte n'est pas un avis juridique." and links to the others).

| Path | Page |
|---|---|
| `/mentions-legales` | Mentions légales |
| `/conditions-generales` | Conditions générales d'utilisation |
| `/confidentialite` | Politique de confidentialité |
| `/cookies` | Cookies |
| `/a-propos` | Qui sommes-nous |
| `/comment-ca-marche` | Comment ça marche |
| `/pro` | Espace professionnel |
| `/aide` | Centre d'aide |
| `/conseils-achat`, `/conseils-vente` | Conseils pour acheter, pour vendre |
| `/signalement` | Signaler une annonce: where the button is, what happens next |
| `/compte-supprime` | Where deleting an account leads (not in the footer) |

"Nous contacter" and "Presse" were removed from the footer: there is no contact form and no
press.

## To fill in

- **The contact address**: `CONTACT_EMAIL` in `features/info/info-page.ts`. While it is `null`,
  every page shows a highlighted `[adresse de contact à compléter]`. The spec asserts that it is
  `null`; change both together.
- **Render's postal address** in the mentions légales, marked `[adresse postale à confirmer]`.
  Render's own legal pages name "Render Services, Inc." but give no address, and the directories
  that do disagree (655 5th Street or 525 Brannan Street, San Francisco).
- **`LAST_REVISED`** in the same file, whenever a legal text changes.

## Where the facts come from

The privacy and cookies pages describe the code, and have to change with it:

| On the page | In the code |
|---|---|
| Sessions: 90 days, deleted 30 days after expiry | `autox.auth.refresh-token-ttl`; `AuthMaintenanceSweeper.AUDIT_RETENTION` |
| Verification and reset links: 24 hours, 30 minutes, deleted 30 days after | `application.yml`; `AuthMaintenanceSweeper` |
| E-mails sent: 90 days | `AuthMaintenanceSweeper.OUTBOX_RETENTION` |
| Anti-abuse counters: 24 hours after the last attempt | `login-throttle.reset-after`; `ThrottleCounters.deleteOlderThan` |
| Search filter counts: 200 days | `AuthMaintenanceSweeper` |
| Listings online 60 days; a deleted one erased with its photos 30 days later | `autox.listing.expiry-days`; `DataRetentionSweeper.DELETED_LISTINGS` |
| Conversations erased a year after their last message, not under an open report | `DataRetentionSweeper.CONVERSATIONS` |
| What deleting an account erases and keeps | `AccountDeletionService` |
| Photo metadata removed | `ListingImageService.rewriteStoredOriginal` |
| Text of the smart search sent to Groq, not stored | `GroqSearchInterpreter`; `InterpretUsage` counts only |
| Brevo records clicks | Brevo's default link tracking; `BrevoEmailSender` does not turn it off |
| The five `localStorage` keys | exported from the modules that write them; `info-pages.spec.ts` checks the cookies page lists exactly those |

The hosts' legal details were taken from their own legal pages in November 2026. What is still
missing is W15 in `docs/weaknesses.md`.

## Deletion, export, retention, reports

**Deleting an account** (Mon compte → Mes données, `POST /api/v1/me/deletion` with the password).
`AccountDeletionService` deletes the listings with their photos in storage, the saved searches,
sessions, pending links, company profile, anti-abuse counters, and the e-mails sent to the
address, then queues one last e-mail to it (`ACCOUNT_DELETED`, through the outbox).

- **The account's row stays, as a tombstone**: status DELETED, display name "Compte supprimé",
  an address at `deleted.autox.invalid`, no names, no phone, a random password. Conversations,
  moderation decisions and the administration log refer to users by id; a tombstone keeps those
  readable with nobody behind them, where deleting the row would have meant rewriting every
  foreign key to it.
- **Messages: the text is erased, the message stays.** The other party keeps the conversation
  and its order, and their own side of it. The deleted person's words are theirs, and messages
  are where people write a phone number or an address, so they go. A message under an open
  report keeps its text until a moderator decides; the daily sweep erases it then. The message
  trigger allows this one change.
- **Records kept without personal data**: a moderation decision outlives its listing without its
  note; the administration log loses the addresses and a suspension's reason (its trigger allows
  only clearing those).
- **Signed out at once**: the refresh tokens are deleted, and the access token still in hand is
  refused from the next request (401 `INVALID_TOKEN`): `CurrentAdminAuthenticationConverter` reads
  the account's row on every authenticated request, and a deleted or missing account ends there.
- **Who cannot**: a demo account (the seed removes those) and the last administrator able to
  sign in (409 `LAST_ADMIN`, the same row locks as the admin actions).

**The export** (`GET /api/v1/me/export`, three an hour): account and company, listings with their
photos (links valid six hours) and price history, conversations (the other party by display name;
a message they wrote and a moderator hid, without its text), saved searches, sessions.

**Retention**, `DataRetentionSweeper`, once a day at 4:20 Paris time
(`AUTOX_SCHEDULE_DATA_RETENTION` to change it; optional): listings deleted more than 30 days ago,
with their photos; conversations whose last message is more than a year old, unless a message in
them is under an open report; the text of deleted accounts' messages once no open report holds it;
administration log entries (`admin_user_action`) more than a year old. Since V27 the log's trigger
allows a delete only for an entry over 365 days old, so the bound holds whatever asks. Moderation
decisions on listings are not swept: they keep no personal data.
A conversation now outlives its listing (`listing_id` set to null), and so does a moderation
decision.

**Listing reports**: `POST /api/v1/listings/{id}/report`, signed in with a verified address, not
one's own listing, a listing the public can see; once per person and listing (409
`LISTING_ALREADY_REPORTED`), five an hour. Moderators see them above the message reports on
Signalements, and dismiss them or take the listing down (a `REMOVE` decision with the report's
reason). Either closes every open report on that listing. The admin badge and the dashboard count
both kinds.
