# What Zugig costs to run

Kept from the Paying grilling of 2026-11-06. Nothing here is paid yet: today the app runs on free
tiers for one household. Each line says what it is for, when it starts, and what it costs. Update it
when a quote comes in or a line is settled.

## Fixed, monthly

| Line | For | Starts | Cost | Status |
| --- | --- | --- | --- | --- |
| TMDB commercial API licence | Posters, metadata, search, trending. A paid app is commercial use under TMDB's terms, whichever tier shows the data. | Before the first sale | 149 USD/month for under 1M USD revenue (TMDB's own site, read 2026-11-06) | Not carried: TMDB left in M6 (#481: the cloud holds no TMDB key or data). The line stays here as the reason why. |
| Cloudflare Workers Paid | The cloud past the free tier's 100k requests a day and D1's free caps | When daily requests near 100k | 5 USD/month plus usage (negligible at hundreds of households) | Not needed yet |
| OpenSubtitles Pro package | Subtitle downloads past the free account's 20 a day, and commercial use | Before the first sale, if subtitles stay a paid feature | Unknown; OpenSubtitles steers commercial apps to Pro | Quote to ask |
| Subdl API | Second subtitle source | Before the first sale | Free key today; commercial terms unknown | To check |

## Per sale

| Line | Cost |
| --- | --- |
| Paddle (merchant of record: checkout, GST/HST and foreign tax filed for you; chosen 2026-11-06) | about 5% + 0.50 USD of each sale |

## One-off

| Line | Cost |
| --- | --- |
| Lawyer: IPTV exposure, TMDB and Netflix data, Paddle paperwork | A few hundred CAD |
| Android release key, GitHub, CI | Nothing |

## Free today, and why it may stop being free

- **ESPN's scoreboard** (undocumented endpoint): no fee, no agreement, can be shut off any day.
- **Wikidata** (Title facts) and **Wikipedia's pageviews** (Trending): CC0 data, no key, no fee; the Query Service and the API ask only for a User-Agent naming the app.
- **Cloudflare free tier**: 100k Worker requests a day, D1 5M row reads a day.
- **OpenSubtitles free account**: 20 downloads a day for everyone together.

## Without TMDB

The Provider's own catalog already gives the app a TMDB ID for 94% of Listings, a poster URL per
Listing (`stream_icon`, `cover`), and per-Title details on request (`get_vod_info`, `get_series_info`:
plot, cast, genre, release date, trailer key, episode stills). An app built on those pays TMDB nothing
and loses: textless backdrops and title logos (the M4 look), consistent artwork (one poster per
Listing, 34 different ones for Ballerina), trending and genre Charts, name matching for the 6% of
Listings without an ID, search by person, upcoming Episodes, and the original language that drives
Prefer original audio. Settled 2026-11-07 (M6, ADR 0014): the Provider's own backdrops and details
carry the look, Wikidata (CC0, free) gives the original language and a Wikipedia-pageviews trending
row, and the rest is dropped. No new cost line.

## Decided 2026-11-06

- Price: 29.99 CAD once, 3 devices. No subscription.
- Paid features: sports schedules, subtitles, shared Header reads. Their recurring costs: the OpenSubtitles Pro package and, past the free tier, Workers Paid.
- TMDB goes before the first sale (M6), which removes the one line a one-time price couldn't carry.

## Arithmetic

Decided 2026-11-06: no subscription. The price is paid once, so a recurring cost has to be covered by
new sales or cut.

With TMDB, at 30 CAD one-time (about 21 USD net of Paddle), a fixed 154 USD a month would have needed
about 8 sales a month, every month, forever; a yearly pass at 15 CAD, about 150 households paying each
year. Without TMDB the fixed line is Workers Paid and the OpenSubtitles Pro package, under 100 USD a
year until the Pro quote says otherwise: a handful of sales a year.

## Tax, in one paragraph

Income from sales is taxable income in Canada whichever processor collects it; PayPal and Stripe
report to the CRA. GST/HST: a seller under the small-supplier threshold (30,000 CAD of worldwide
taxable sales over four consecutive calendar quarters) need not register or collect it; above it
they must. Digital sales to the EU, the UK and many US states owe their VAT or sales tax from the
first sale, which is what a merchant of record takes on.
