# The admin dashboard

`/admin`: what needs a moderator, then how the site is used over a chosen period. Admin-only, and
checked on every request as the other admin endpoints are: `GET /api/v1/admin/stats?period=…`.

**Every figure is one the API counted.** A panel with nothing to count says so in words. There are
no placeholder numbers.

## Periods

`TODAY`, `WEEK` (7 days), `MONTH` (30), `QUARTER` (90), in Paris time (`DashboardPeriod`).

- **What a period covers:** whole days ending today, today so far included. "7 jours" is the six
  days before today plus today until now.
- **What it is compared with:** the same span just before it, ending at the same time of day.
  Today until 10:00 is compared with yesterday until 10:00, not with all of yesterday.
- **Clock changes:** days are calendar days, so a week that holds one is an hour longer or shorter,
  and so is the week it is compared with.
- **Trends:** the change against the previous period, rounded to a whole percent.
  - "Nouveau" when there was nothing before.
  - "—" when there is nothing in either period.
  - Red when a figure that should fall rises: failures.
- **Charts:** one bar per hour today, one per day otherwise.
- **Smart-search counts:** natural-language search is only stored per day, so on "Aujourd'hui" its
  count is compared with all of yesterday.

### Whose clock: the database's

"Now", the end of every period, is the database's `now()` (`AdminStatsRepository.databaseNow`),
not the application's clock. Every row counted here was stamped by the database: a column default,
or the outbox's `sent_at = now()`.

**The bug:** with the period ending at the JVM's `Instant.now()`, two clocks met in one comparison.
When the database's ran a few milliseconds ahead, a row written just before the request fell
after the end of the period and was not counted.
- **How it showed:** a test that failed once in a full run ("expected 2, was 1") and passed alone.
  Docker's VM clock ran ahead of Windows'.
- **In production:** Neon's clock and Render's can drift the same way.

**Reproduced, then fixed:** `AdminStatsIntegrationTest.databaseClock` runs the service with its
clock 5 seconds and 1 hour behind and ahead of the database's. All four cases failed before the
change and pass after it. The application's clock now only times the cache.

## The demo switch

The API sends real activity and the demo seed apart, always. The switch only decides what the page
adds up, so turning it on or off asks the server nothing. It is on by default and the page says so
next to the date ("Démo incluse"). It is turned off with `?demo=0`, and the period is set with
`?periode=jour|7j|30j|trimestre`, so a view can be shared. Where there is no demo seed the switch is
disabled.

## Moderating from here

The five oldest listings in the queue, in the queue's own order (created first). Each can be
accepted, or refused with a reason. These are the moderation page's endpoints, so the rules and the
trail are the same. Anything that needs the listing read in full is on the moderation page. With the
demo switch off, only real listings are listed.

## Search, counted by filter

`search_filter_usage` (V24) counts, per hour, how many first pages of the results page used each
filter. It records the filter's name and never its value, so nothing anyone typed is kept. '_all'
counts every search, which is what a filter's share is taken of.

- **Not counted:** a later page of the same search.
- **Counted from:** the deploy. Before that there is nothing to show, and the panel says so.
- **Bots:** they count too, within the public rate limit.
- **Retention:** rows go after 200 days. The longest period is a quarter, compared with the one
  before it.

## The CSV

**Exporter (CSV)** writes the figures on screen, with the demo switch as it is, for a French
spreadsheet: semicolons, a byte-order mark, one line per figure, then one per day or hour of the
series. A make typed by a seller that starts like a formula is prefixed with an apostrophe.

## Caching

The figures are computed once a minute per period. Moderating from the dashboard updates its own
counts at once. Elsewhere, a change shows within the minute.
