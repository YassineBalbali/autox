# Saved searches and e-mail alerts

"Créer une alerte" on the results page saves the search on the screen. Each morning, an account with
something new gets one e-mail covering all its searches. "Mes recherches" lists them.

## Who can

Signed-in accounts whose address is verified, since an alert is e-mail. That includes older accounts,
which can otherwise publish and write without verifying: 403 `EMAIL_NOT_VERIFIED`. Ten searches per
account, paused ones included: 409 `SAVED_SEARCH_LIMIT_REACHED`, under a lock on the account so that
saves arriving together cannot pass it.

## Matching

`ListingSearchRepository.publishedSince` runs the same `SELECT`, the same visibility predicate and
the same `appendFilters` as the results page, and adds only "new": published after a date, not the
searcher's own, not already sent for this search. There is no second copy of the filters, so a new
filter reaches alerts without any change here. `SavedSearchIntegrationTest` compares what is sent
with what `GET /api/v1/listings` answers, for sales, leasing and damaged vehicles.

## What is stored

| Column | What |
|---|---|
| `criteria`, `criteria_version` | `ListingSearchCriteria` as JSON: what the results page sends the API |
| `link_params` | The results page's query string, for the links back to it |
| `make_label`, `model_label` | The catalogue's names when it was saved, to say which has gone |

The page posts both forms, from the one query it parsed. They are not checked against each other: a
client that sent two different searches would only mislead itself, since the links go to its owner.

**When the filters change** (`SavedSearchCriteriaCodec`). Adding one needs nothing. A field that goes
is ignored. A value that goes, a fuel type say, is dropped and the rest of the filter stands. Renaming
a field or changing what it means is what the version is for: raise it and add a step to `upgrade`.
A search that still cannot be read sends nothing, says so on the page, and costs nobody else theirs.

**When a make or model goes.** The search runs without it, and so does its link, and "Mes recherches"
says which one has gone. A model without its make is dropped too, as on the results page. Only a
catalogue id can go: a make typed as free text is a text filter and simply matches what it matches.

## The daily e-mail

`AlertDigestJob` runs at 7:00, Paris time. One transaction per account writes the e-mail to the
outbox, records what it holds, and moves each search's date, so a crash cannot send a listing twice
or lose one.

- **What is new:** published since the search's last digest. It starts when the search is saved, and
  again when it is resumed, so nothing published during a pause is sent.
- **Never twice:** a listing's `published_at` is set before its transaction commits, so it can appear
  with a date just before a digest that did not see it. Each run looks back five minutes further, and
  `saved_search_sent` stops that repeating anything.
- **One e-mail a day:** checked against the outbox since midnight in Paris. A later run the same day
  sends nothing and loses nothing: its listings go the next morning.
- **At most ten per search,** with the number new and a link to all of them. Those past the ten are
  on that link and are not sent the next day.
- **Nothing new, no e-mail.**
- **Who gets nothing:** paused searches, suspended or closed accounts, an address no longer verified.

A missed morning, because the process was down at 7:00, is covered by the next run.

### Neon

One run a day and nothing in between: there is no poll for this. It wakes the database once, as a
visitor would, and the outbox's poller sends the mail within about a minute, then lets it sleep again.

### Legal mention

An alert for leasing offers states monthly payments, so it carries the credit mention, as the results
page does: `docs/leasing.md`.

## Unsubscribing

Each search in an e-mail has its own link, to `/alertes/desabonnement?token=…`. No sign-in. The token
is the search's id, signed with HMAC-SHA256, and does not expire.

- It **pauses** the search rather than deleting it, so a click by mistake loses nothing, and the
  search stays in "Mes recherches" to resume.
- The page **posts** the token when it opens, and a GET changes nothing, so a mail scanner that fetches
  the link without running the page does not unsubscribe anybody.
- The key is derived from `AUTOX_JWT_SECRET` under its own label, so there is no new variable.
  **Rotating that secret ends the links in e-mails already sent.** Anyone clicking one is told it is
  not valid and sent to "Mes recherches".

There is no `List-Unsubscribe` header. Brevo's API says it does not take standard e-mail headers, and
I have not tested whether it adds one for this kind of mail. Mailbox providers ask bulk senders for
one, which matters if volume grows.

## Testing in production without waiting a day

Sign in as an administrator with a verified address:

1. On the results page, search for something and "Créer une alerte".
2. From **another account**, publish a matching listing: your own are left out.
3. Administration → Tableau de bord → **M'envoyer mon résumé maintenant**. It says whether there was
   anything to send, and the e-mail goes within about a minute.

It writes only the caller's digest, nobody else's, and can be repeated: it skips the once-a-day check.
It is a real digest, so what it sends is recorded as sent and will not come again, and it counts as
that day's e-mail for the 7:00 job if pressed before 7:00.

Check the unsubscribe link from that e-mail, then the job itself the next morning: the log says
`Alert digest: N e-mail(s) written for M account(s) with active searches`.
