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
