import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";
import { FACTS_CACHED_A_DAY, MAX_FACTS_BATCH, USER_AGENT } from "../src/title-facts";
import { FakeWeb } from "./fake-web";
import { FakeWikidata } from "./fake-wikidata";

/** Seam 2: the Title facts endpoint (#476), run in the Workers runtime with the Wikidata Query Service faked. */
let web: FakeWeb;
let wikidata: FakeWikidata;
let token: string;
beforeEach(async () => {
  await env.DB.batch(["device", "title_facts", "facts_cached"].map((table) => env.DB.prepare(`DELETE FROM ${table}`)));
  web = new FakeWeb();
  wikidata = new FakeWikidata(web);
  wikidata.movie(238, { language: "en", imdbId: "tt0068646", enwiki: "The Godfather", frwiki: "Le Parrain" });
  wikidata.show(1396, { language: "en", imdbId: "tt0903747", enwiki: "Breaking Bad", frwiki: "Breaking Bad (série télévisée)" });
  // Squid Game: Korean, with no French article.
  wikidata.show(93405, { language: "ko", imdbId: "tt10919420", enwiki: "Squid Game" });
  token = await registerDevice();
});
afterEach(() => web.restore());

const call = (path: string, body?: unknown, auth: string | null = token) =>
  worker.fetch(
    new Request(`https://cloud.example${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(auth ? { Authorization: `Bearer ${auth}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    env,
  );

async function registerDevice(): Promise<string> {
  const response = await call("/v1/devices", undefined, null);
  return ((await response.json()) as { token: string }).token;
}

interface Facts {
  kind: string;
  tmdbId: number;
  originalLanguage: string | null;
  imdbId: string | null;
  enwiki: string | null;
  frwiki: string | null;
}

interface Key {
  kind: string;
  tmdbId: number;
}

async function facts(titles: Key[]) {
  const response = await call("/v1/title-facts", { titles });
  expect(response.status).toBe(200);
  return (await response.json()) as { titles: Facts[]; notFound: Key[]; unavailable: Key[] };
}

const godfather = { kind: "movie", tmdbId: 238 };
const breakingBad = { kind: "show", tmdbId: 1396 };
const squidGame = { kind: "show", tmdbId: 93405 };
/** A Title number Wikidata has no item for. */
const filler = { kind: "movie", tmdbId: 777_001 };

describe("Title facts", () => {
  it("answers each Title's original language, IMDb ID and Wikipedia article names, and names the Titles Wikidata has no item for", async () => {
    const answer = await facts([godfather, filler, squidGame, breakingBad]);

    expect(answer).toEqual({
      titles: [
        { kind: "movie", tmdbId: 238, originalLanguage: "en", imdbId: "tt0068646", enwiki: "The Godfather", frwiki: "Le Parrain" },
        { kind: "show", tmdbId: 93405, originalLanguage: "ko", imdbId: "tt10919420", enwiki: "Squid Game", frwiki: null },
        { kind: "show", tmdbId: 1396, originalLanguage: "en", imdbId: "tt0903747", enwiki: "Breaking Bad", frwiki: "Breaking Bad (série télévisée)" },
      ],
      notFound: [filler],
      unavailable: [],
    });
  });

  it("asks Wikidata once for a batch of 300, by the TMDB movie and show ID properties, naming itself", async () => {
    const titles = Array.from({ length: MAX_FACTS_BATCH }, (_, i) => (i % 2 === 0 ? { kind: "movie", tmdbId: 1000 + i } : { kind: "show", tmdbId: 1000 + i }));
    titles[0] = godfather;
    titles[1] = breakingBad;

    const answer = await facts(titles);

    expect(wikidata.requests).toHaveLength(1);
    expect(wikidata.asked[0]).toHaveLength(MAX_FACTS_BATCH);
    expect(wikidata.asked[0].slice(0, 2)).toEqual(["movie/238", "show/1396"]);
    const { query, userAgent, accept } = wikidata.requests[0];
    expect(userAgent).toBe(USER_AGENT);
    expect(userAgent).toContain("https://github.com/victortrinh/better-iptv");
    expect(accept).toBe("application/sparql-results+json");
    // Movies by P4947, shows by P4983; the original language through P364 and P218, the IMDb ID by P345, the article names from the sitelinks.
    expect(query).toContain('("movie" wdt:P4947 "238")');
    expect(query).toContain('("show" wdt:P4983 "1396")');
    for (const asked of ["wdt:P345", "wdt:P364", "wdt:P218", "https://en.wikipedia.org/", "https://fr.wikipedia.org/"]) expect(query).toContain(asked);
    expect(answer.titles.map((t) => t.tmdbId)).toEqual([238, 1396]);
    expect(answer.notFound).toHaveLength(MAX_FACTS_BATCH - 2);
    expect(answer.unavailable).toEqual([]);
  });

  it("refuses a 301st Title, and a malformed request", async () => {
    const titles = Array.from({ length: MAX_FACTS_BATCH + 1 }, (_, i) => ({ kind: "movie", tmdbId: 1 + i }));
    expect((await call("/v1/title-facts", { titles })).status).toBe(400);
    expect((await call("/v1/title-facts", { titles: [{ kind: "film", tmdbId: 1 }] })).status).toBe(400);
    expect((await call("/v1/title-facts", { titles: [{ kind: "movie", tmdbId: "238" }] })).status).toBe(400);
    expect((await call("/v1/title-facts", { titles: [{ kind: "movie", tmdbId: 0 }] })).status).toBe(400);
    expect((await call("/v1/title-facts", {})).status).toBe(400);
    expect((await call("/v1/title-facts", [godfather], null)).status).toBe(401);
    expect(wikidata.requests).toHaveLength(0);
  });

  it("answers a second request for the same Titles from its cache, from any device, without a Wikidata request", async () => {
    await facts([godfather, squidGame]);
    expect(wikidata.requests).toHaveLength(1);

    token = await registerDevice();
    const again = await facts([squidGame, godfather]);

    expect(wikidata.requests).toHaveLength(1);
    expect(again.titles.map((t) => [t.tmdbId, t.originalLanguage, t.imdbId, t.enwiki, t.frwiki])).toEqual([
      [93405, "ko", "tt10919420", "Squid Game", null],
      [238, "en", "tt0068646", "The Godfather", "Le Parrain"],
    ]);
  });

  it("caches a Title Wikidata has no item for as not found, and never asks about it again", async () => {
    expect((await facts([filler])).notFound).toEqual([filler]);
    // Even once Wikidata gains the item: the cache is for good.
    wikidata.movie(filler.tmdbId, { language: "fa" });

    const again = await facts([filler, godfather]);

    expect(again.notFound).toEqual([filler]);
    expect(again.titles.map((t) => t.tmdbId)).toEqual([238]);
    // Only The Godfather, never asked before, reached Wikidata.
    expect(wikidata.asked).toEqual([["movie/777001"], ["movie/238"]]);
    const kept = await env.DB.prepare("SELECT found, fetched_at FROM title_facts WHERE kind = 'movie' AND tmdb_id = ?1").bind(filler.tmdbId).first<{ found: number; fetched_at: number }>();
    expect(kept?.found).toBe(0);
    expect(kept?.fetched_at).toBeGreaterThan(0);
  });

  it("caches at most FACTS_CACHED_A_DAY Titles a day, answering the rest without keeping them", async () => {
    // Today's budget is all but one Title spent.
    await env.DB.prepare("INSERT INTO facts_cached (day, titles) VALUES (?1, ?2)").bind(new Date().toISOString().slice(0, 10), FACTS_CACHED_A_DAY - 1).run();

    const answer = await facts([godfather, breakingBad, filler]);

    // Every Title is answered as before.
    expect(answer.titles.map((t) => t.tmdbId)).toEqual([238, 1396]);
    expect(answer.notFound).toEqual([filler]);
    expect(answer.unavailable).toEqual([]);
    // Only the first was kept, and the day's count says so.
    const cached = await env.DB.prepare("SELECT kind, tmdb_id FROM title_facts ORDER BY tmdb_id").all<{ kind: string; tmdb_id: number }>();
    expect(cached.results).toEqual([{ kind: "movie", tmdb_id: 238 }]);
    expect((await env.DB.prepare("SELECT titles FROM facts_cached").first<{ titles: number }>())?.titles).toBe(FACTS_CACHED_A_DAY);
    // The rest are asked of Wikidata again, and still not kept today.
    await facts([breakingBad, filler]);
    expect(wikidata.asked).toEqual([["movie/238", "show/1396", "movie/777001"], ["show/1396", "movie/777001"]]);
    expect((await env.DB.prepare("SELECT count(*) AS n FROM title_facts").first<{ n: number }>())?.n).toBe(1);
    // Tomorrow they are.
    await env.DB.prepare("UPDATE facts_cached SET day = '2000-01-01'").run();
    await facts([breakingBad, filler]);
    expect((await env.DB.prepare("SELECT count(*) AS n FROM title_facts").first<{ n: number }>())?.n).toBe(3);
    expect((await env.DB.prepare("SELECT titles FROM facts_cached WHERE day = ?1").bind(new Date().toISOString().slice(0, 10)).first<{ titles: number }>())?.titles).toBe(2);
  });

  it("asks only about the Titles it hasn't cached, in one query", async () => {
    await facts([godfather]);

    const answer = await facts([godfather, breakingBad, filler]);

    expect(wikidata.asked).toEqual([["movie/238"], ["show/1396", "movie/777001"]]);
    expect(answer.titles.map((t) => t.tmdbId)).toEqual([238, 1396]);
    expect(answer.notFound).toEqual([filler]);
  });

  it("answers the cached Titles and lists the rest as unavailable while Wikidata fails or is slow, caching nothing of them", async () => {
    await facts([godfather]);

    for (const failure of [429, 500, 504]) {
      wikidata.failWith = failure;
      const answer = await facts([godfather, squidGame, filler]);
      expect(answer.titles.map((t) => t.tmdbId)).toEqual([238]);
      expect(answer.notFound).toEqual([]);
      expect(answer.unavailable).toEqual([squidGame, filler]);
    }
    wikidata.failWith = null;
    wikidata.breakAnswers = true;
    expect((await facts([squidGame])).unavailable).toEqual([squidGame]);
    wikidata.breakAnswers = false;

    // Nothing was cached for them: the next request asks Wikidata about them again, and gets its answers.
    const back = await facts([squidGame, filler]);
    expect(back.titles.map((t) => t.tmdbId)).toEqual([93405]);
    expect(back.notFound).toEqual([filler]);
    expect(back.unavailable).toEqual([]);
    expect(wikidata.asked.at(-1)).toEqual(["show/93405", "movie/777001"]);
  });

  it("keeps a Movie and a Show with the same number apart", async () => {
    wikidata.movie(1396, { language: "de", imdbId: "tt0000001" });

    const answer = await facts([{ kind: "movie", tmdbId: 1396 }, breakingBad]);

    expect(answer.titles.map((t) => [t.kind, t.originalLanguage])).toEqual([
      ["movie", "de"],
      ["show", "en"],
    ]);
  });

  it("takes an item with no language, IMDb ID or article as found, with each null", async () => {
    wikidata.movie(555);

    const answer = await facts([{ kind: "movie", tmdbId: 555 }]);

    expect(answer.titles).toEqual([{ kind: "movie", tmdbId: 555, originalLanguage: null, imdbId: null, enwiki: null, frwiki: null }]);
    expect(answer.notFound).toEqual([]);
  });

  it("asks about a Title once however many times the request lists it", async () => {
    const answer = await facts([godfather, godfather, { kind: "movie", tmdbId: 238 }]);

    expect(wikidata.asked).toEqual([["movie/238"]]);
    expect(answer.titles).toHaveLength(1);
  });
});
