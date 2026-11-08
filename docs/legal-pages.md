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

"Signaler une annonce", "Nous contacter" and "Presse" were removed from the footer: a listing
cannot be reported from the site (only a message can), and there is no contact form and no press.

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
| Listings online 60 days; deleted ones kept | `autox.listing.expiry-days`; `ListingService.delete` (soft delete) |
| Photo metadata removed | `ListingImageService.rewriteStoredOriginal` |
| Text of the smart search sent to Groq, not stored | `GroqSearchInterpreter`; `InterpretUsage` counts only |
| Brevo records clicks | Brevo's default link tracking; `BrevoEmailSender` does not turn it off |
| The five `localStorage` keys | exported from the modules that write them; `info-pages.spec.ts` checks the cookies page lists exactly those |

The hosts' legal details were taken from their own legal pages in November 2026. The gaps the
privacy page admits (no account deletion, no data export, deleted listings and messages kept)
are W15 in `docs/weaknesses.md`.
