import type { Env } from "./env";
import { USER_AGENT } from "./title-facts";

/**
 * Trending (#477, ADR 0014): Wikipedia's most viewed articles, which the device matches to its Titles'
 * article names (the Title facts' `enwiki` and `frwiki`). The pageviews API (CC0) publishes each wiki's
 * top 1,000 articles of a day once that day is over; the Cron Trigger reads each wanted wiki's list once,
 * keeps [DAYS] of them, and the feed serves the kept days with their views. The device sums the week.
 */

/** Days kept of each wiki: the week the device sums. */
export const DAYS = 7;
/** English is read for every device; its App language's wiki too. */
const ALWAYS = "en";
/** A wiki no device asked for in 30 days is no longer read. */
const WANTED_FOR = 30 * 24 * 3600_000;
/** How long when a language was asked for is left as it is, so most answers write nothing. */
const ASKED_EVERY = 3600_000;
/**
 * A day asked for in the last hour isn't asked again: one the API hasn't published yet (it comes out
 * some hours after midnight UTC), or one the API couldn't give right now.
 */
const RETRY_AFTER = 3600_000;

/** One wiki's kept days in the feed, newest first. */
export interface TrendingWiki {
  /** The wiki's language code, `en` or `fr`. */
  wiki: string;
  days: TrendingDay[];
}

export interface TrendingDay {
  /** `YYYY-MM-DD`, in UTC, as the API dates its lists. */
  day: string;
  /** The day's most viewed articles, most viewed first, named as Wikipedia shows them (`Jailer 3`). */
  articles: { name: string; views: number }[];
}

/**
 * The feed for a device in App [language] (ISO 639-1): English's kept days, then the language's when it
 * isn't English. Only D1 is read, never Wikipedia; the language is noted as wanted, so the Cron Trigger
 * reads its wiki within the next minutes, and English with it.
 */
export async function trending(env: Env, language: string): Promise<{ wikis: TrendingWiki[] }> {
  const now = Date.now();
  const wikis = [...new Set([ALWAYS, language])];
  const [, { results }] = await env.DB.batch<{ wiki: string; day: string; articles: string }>([
    env.DB.prepare("INSERT INTO trending_wanted (wiki, asked_at) VALUES (?1, ?2) ON CONFLICT (wiki) DO UPDATE SET asked_at = excluded.asked_at WHERE asked_at < ?3")
      .bind(language, now, now - ASKED_EVERY),
    env.DB.prepare(`SELECT wiki, day, articles FROM trending_day WHERE wiki IN (${wikis.map((_, i) => `?${i + 1}`).join(", ")}) AND articles IS NOT NULL AND day >= ?${wikis.length + 1} ORDER BY day DESC`)
      .bind(...wikis, window(now).at(-1)!),
  ]);
  return {
    wikis: wikis.map((wiki) => ({
      wiki,
      days: results.filter((r) => r.wiki === wiki).map((r) => ({ day: r.day, articles: JSON.parse(r.articles) })),
    })),
  };
}

/**
 * One run of the Cron Trigger's Trending part: drops the days older than the week kept and the wikis
 * no longer wanted, then reads each wanted wiki's missing days in the week, newest first, one request
 * each, out of [budget]. A day read is never asked again; one not published yet, or that the API
 * couldn't give, is asked again after [RETRY_AFTER]. A wiki is wanted while a device asked in its
 * language in the last [WANTED_FOR], English while any did. Says how many requests it sent.
 */
export async function fillTrending(env: Env, budget: number): Promise<number> {
  const now = Date.now();
  const days = window(now);
  const [wanted, kept] = await env.DB.batch([
    env.DB.prepare("SELECT wiki FROM trending_wanted WHERE asked_at > ?1").bind(now - WANTED_FOR),
    env.DB.prepare("SELECT wiki, day, articles IS NOT NULL AS read, tried_at FROM trending_day"),
  ]);
  const asked = (wanted.results as { wiki: string }[]).map((w) => w.wiki);
  const wikis = asked.length === 0 ? [] : [...new Set([ALWAYS, ...asked])];
  const rows = kept.results as { wiki: string; day: string; read: number; tried_at: number }[];
  const stale = rows.filter((r) => !days.includes(r.day) || !wikis.includes(r.wiki));
  if (stale.length > 0) await env.DB.batch(stale.map((r) => env.DB.prepare("DELETE FROM trending_day WHERE wiki = ?1 AND day = ?2").bind(r.wiki, r.day)));
  const byKey = new Map(rows.map((r) => [`${r.wiki}/${r.day}`, r]));

  let sent = 0;
  for (const day of days) {
    for (const wiki of wikis) {
      const row = byKey.get(`${wiki}/${day}`);
      if (row !== undefined && (row.read || row.tried_at > now - RETRY_AFTER)) continue;
      if (sent >= budget) return sent;
      sent++;
      const articles = await readDay(env, wiki, day);
      await env.DB.prepare(
        "INSERT INTO trending_day (wiki, day, articles, tried_at) VALUES (?1, ?2, ?3, ?4) ON CONFLICT (wiki, day) DO UPDATE SET articles = COALESCE(excluded.articles, articles), tried_at = excluded.tried_at",
      ).bind(wiki, day, articles === null ? null : JSON.stringify(articles), now).run();
    }
  }
  return sent;
}

/** The days kept at [now], `YYYY-MM-DD` in UTC, newest first: the [DAYS] before today, whose lists are over. */
function window(now: number): string[] {
  const today = new Date(now);
  today.setUTCHours(0, 0, 0, 0);
  return Array.from({ length: DAYS }, (_, i) => new Date(today.getTime() - (i + 1) * 86_400_000).toISOString().slice(0, 10));
}

/** [wiki]'s most viewed articles of [day], or null when the API hasn't published them or can't give them right now. */
async function readDay(env: Env, wiki: string, day: string): Promise<TrendingDay["articles"] | null> {
  const [year, month, date] = day.split("-");
  try {
    const response = await fetch(`${env.WIKIMEDIA_PAGEVIEWS}/top/${wiki}.wikipedia/all-access/${year}/${month}/${date}`, {
      headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
    });
    if (!response.ok) {
      // 404: the day isn't published yet.
      if (response.status !== 404) console.warn(`Wikipedia's ${wiki} top articles of ${day} couldn't be fetched: HTTP ${response.status}`);
      return null;
    }
    const body = (await response.json()) as { items?: { articles?: { article?: unknown; views?: unknown }[] }[] };
    return (body.items?.[0]?.articles ?? [])
      .filter((a) => typeof a.article === "string" && typeof a.views === "number")
      .map((a) => ({ name: (a.article as string).replaceAll("_", " "), views: a.views as number }));
  } catch (e) {
    console.warn(`Wikipedia's ${wiki} top articles of ${day} couldn't be read: ${e}`);
    return null;
  }
}
