import { createScheduledController, env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { LEAGUES } from "../src/schedule";
import { argos, bombers, broncos, bruins, event, FakeEspn, habs, leafs, sens, team, texans } from "./fake-espn";
import { FakeNetflix } from "./fake-netflix";
import { breakingBad, FakeTmdb, godfather } from "./fake-tmdb";

/** Seam 2: the cloud's HTTP API, run in the Workers runtime with TMDB faked. */
let tmdb: FakeTmdb;
beforeEach(async () => {
  // Each test starts with no devices and an empty cache.
  await env.DB.batch([env.DB.prepare("DELETE FROM device"), env.DB.prepare("DELETE FROM title_metadata"), env.DB.prepare("DELETE FROM file_read"), env.DB.prepare("DELETE FROM upcoming_episodes"), env.DB.prepare("DELETE FROM unknown_prefix"), env.DB.prepare("DELETE FROM chart"), env.DB.prepare("DELETE FROM chart_wanted"), env.DB.prepare("DELETE FROM chart_try"), env.DB.prepare("DELETE FROM game"), env.DB.prepare("DELETE FROM team"), env.DB.prepare("DELETE FROM game_wanted"), env.DB.prepare("DELETE FROM game_try")]);
  tmdb = new FakeTmdb();
  tmdb.movie(238, godfather);
  tmdb.show(1396, breakingBad);
});
afterEach(() => {
  tmdb.restore();
  vi.useRealTimers();
});

const call = (method: string, path: string, body?: unknown, token?: string) =>
  worker.fetch(
    new Request(`https://cloud.example${path}`, {
      method,
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    env,
  );

async function registerDevice(): Promise<string> {
  const response = await call("POST", "/v1/devices");
  expect(response.status).toBe(201);
  return ((await response.json()) as { token: string }).token;
}

async function metadata(token: string, language: string, titles: { kind: string; tmdbId: number }[]) {
  const response = await call("POST", "/v1/metadata", { language, titles }, token);
  expect(response.status).toBe(200);
  return (await response.json()) as { titles: any[]; notFound: unknown[]; unavailable: unknown[] };
}

const theGodfather = { kind: "movie", tmdbId: 238 };

describe("device tokens", () => {
  it("rejects a request without a token, or with one no device was given", async () => {
    expect((await call("POST", "/v1/metadata", { language: "en", titles: [theGodfather] })).status).toBe(401);
    const madeUp = "A".repeat(43);
    expect((await call("POST", "/v1/metadata", { language: "en", titles: [theGodfather] }, madeUp)).status).toBe(401);
    expect(tmdb.requests).toHaveLength(0);
  });

  it("accepts every registered device's own token", async () => {
    const living = await registerDevice();
    const bedroom = await registerDevice();
    expect(living).not.toBe(bedroom);
    expect((await metadata(living, "en", [theGodfather])).titles).toHaveLength(1);
    expect((await metadata(bedroom, "en", [theGodfather])).titles).toHaveLength(1);
  });

  it("keeps only a hash of each token", async () => {
    const token = await registerDevice();
    const stored = await env.DB.prepare("SELECT token_hash FROM device").all<{ token_hash: string }>();
    expect(stored.results.map((row) => row.token_hash)).not.toContain(token);
  });
});

describe("Title metadata", () => {
  it("answers with what the app shows about a Movie, in the requested language", async () => {
    const { titles } = await metadata(await registerDevice(), "fr", [theGodfather]);

    expect(titles).toEqual([
      {
        kind: "movie",
        tmdbId: 238,
        originalLanguage: "en",
        originalTitle: "The Godfather",
        titles: [
          // TMDB leaves the title empty where it isn't translated.
          { language: "en", region: "US", title: "The Godfather" },
          { language: "fr", region: "FR", title: "Le Parrain" },
          { language: "fr", region: "CA", title: "Le Parrain" },
          { language: "de", region: "DE", title: "Der Pate" },
        ],
        alternativeTitles: ["Mario Puzo's The Godfather"],
        // Only French and English, the fallback: every language's description would be most of the response.
        descriptions: [
          { language: "en", region: "US", text: "Spanning the years 1945 to 1955…" },
          { language: "fr", region: "FR", text: "En 1945, à New York…" },
        ],
        genres: ["Drame", "Crime"],
        popularity: 128.4,
        releaseDate: "1972-03-14",
        runtimeMinutes: 175,
        posterPath: "/parrain.jpg",
        backdropPath: "/godfather-backdrop.jpg",
        // The best voted French logo; English and language-free ones come after it.
        logoPath: "/parrain-logo.svg",
        // The best voted poster with no language, so with no text on it.
        textlessPosterPath: "/godfather-plain.jpg",
        // The best voted backdrop with no language ("xx" counts as none), so with no text on it.
        textlessBackdropPath: "/godfather-wide.jpg",
        cast: [
          { name: "Marlon Brando", character: "Don Vito Corleone", profilePath: "/brando.jpg" },
          { name: "Al Pacino", character: "Michael Corleone", profilePath: "/pacino.jpg" },
        ],
        imdbId: "tt0068646",
        trailers: ["official-trailer", "fan-trailer"],
      },
    ]);
    expect(tmdb.requests.map((url) => url.pathname)).toEqual(["/3/movie/238"]);
    expect(tmdb.requests[0].searchParams.get("language")).toBe("fr");
  });

  it("asks TMDB for images in the requested language, English and none", async () => {
    await metadata(await registerDevice(), "fr", [theGodfather]);

    expect(tmdb.requests[0].searchParams.get("include_image_language")).toBe("fr,en,null");
  });

  it("falls back to the English logo, and to none", async () => {
    tmdb.movie(11, (language) => ({ ...godfather(language), id: 11, images: { logos: [{ file_path: "/mark.png", iso_639_1: null }] } }));
    const { titles } = await metadata(await registerDevice(), "fr", [{ kind: "show", tmdbId: 1396 }, { kind: "movie", tmdbId: 11 }]);

    expect(titles.map((t) => t.logoPath)).toEqual(["/bb-logo.png", null]);
    expect(titles.map((t) => t.textlessPosterPath)).toEqual([null, null]);
    expect(titles.map((t) => t.textlessBackdropPath)).toEqual([null, null]);
  });

  it("reads Shows from TMDB's TV endpoint, whose IDs are another number space", async () => {
    const { titles } = await metadata(await registerDevice(), "en", [{ kind: "show", tmdbId: 1396 }]);

    expect(titles).toMatchObject([
      {
        kind: "show",
        tmdbId: 1396,
        originalTitle: "Breaking Bad",
        titles: [{ language: "en", region: "US", title: "Breaking Bad" }],
        alternativeTitles: ["Hacerse malo"],
        releaseDate: "2008-01-20",
        runtimeMinutes: 45,
        backdropPath: null,
        imdbId: "tt0903747",
        trailers: [],
      },
    ]);
    expect(tmdb.requests.map((url) => url.pathname)).toEqual(["/3/tv/1396"]);
  });

  it("answers a batch in the order asked, and names the Titles TMDB doesn't have", async () => {
    const batch = await metadata(await registerDevice(), "en", [
      { kind: "show", tmdbId: 1396 },
      { kind: "movie", tmdbId: 999999 },
      theGodfather,
      // A show with The Godfather's number is another Title, and TMDB has none.
      { kind: "show", tmdbId: 238 },
    ]);

    expect(batch.titles.map((t) => `${t.kind}/${t.tmdbId}`)).toEqual(["show/1396", "movie/238"]);
    expect(batch.notFound).toEqual([{ kind: "movie", tmdbId: 999999 }, { kind: "show", tmdbId: 238 }]);
    expect(batch.unavailable).toEqual([]);
  });

  it("sends TMDB the cloud's key, which never appears in an answer", async () => {
    const response = await call("POST", "/v1/metadata", { language: "en", titles: [theGodfather] }, await registerDevice());

    expect(tmdb.tokens).toEqual(["Bearer test-tmdb-token"]);
    expect(await response.text()).not.toContain("test-tmdb-token");
  });

  it("also takes TMDB's short API key, which goes in the query instead", async () => {
    const shortKey = "0123456789abcdef0123456789abcdef";
    const token = await registerDevice();
    const response = await worker.fetch(
      new Request("https://cloud.example/v1/metadata", {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        body: JSON.stringify({ language: "en", titles: [theGodfather] }),
      }),
      { ...env, TMDB_TOKEN: shortKey },
    );

    expect(((await response.json()) as { titles: unknown[] }).titles).toHaveLength(1);
    expect(tmdb.tokens).toEqual([`api_key=${shortKey}`]);
  });

  it("rejects a malformed request", async () => {
    const token = await registerDevice();
    const tooMany = Array.from({ length: 51 }, (_, i) => ({ kind: "movie", tmdbId: i + 1 }));
    for (const body of [
      { language: "fr-CA", titles: [theGodfather] },
      { language: "en", titles: [{ kind: "series", tmdbId: 1 }] },
      { language: "en", titles: [{ kind: "movie", tmdbId: "238" }] },
      { language: "en", titles: tooMany },
    ]) {
      expect((await call("POST", "/v1/metadata", body, token)).status).toBe(400);
    }
    expect(tmdb.requests).toHaveLength(0);
  });
});

describe("the metadata cache", () => {
  it("answers a repeat request without reaching TMDB, from any device", async () => {
    await metadata(await registerDevice(), "en", [theGodfather, { kind: "movie", tmdbId: 999999 }]);
    const again = await metadata(await registerDevice(), "en", [theGodfather, { kind: "movie", tmdbId: 999999 }]);

    expect(again.titles.map((t) => t.tmdbId)).toEqual([238]);
    expect(again.notFound).toEqual([{ kind: "movie", tmdbId: 999999 }]);
    expect(tmdb.requests).toHaveLength(2);
  });

  it("keeps each language apart", async () => {
    const token = await registerDevice();
    await metadata(token, "en", [theGodfather]);
    const french = await metadata(token, "fr", [theGodfather]);

    expect(french.titles[0].genres).toEqual(["Drame", "Crime"]);
    expect(tmdb.requests.map((url) => url.searchParams.get("language"))).toEqual(["en", "fr"]);
  });

  it("asks TMDB again once an answer is a week old", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-01T12:00:00Z"));
    const token = await registerDevice();
    await metadata(token, "en", [theGodfather]);
    vi.setSystemTime(new Date("2026-10-07T12:00:00Z"));
    await metadata(token, "en", [theGodfather]);
    expect(tmdb.requests).toHaveLength(1);

    vi.setSystemTime(new Date("2026-10-08T13:00:00Z"));
    await metadata(token, "en", [theGodfather]);
    expect(tmdb.requests).toHaveLength(2);
  });

  it("asks TMDB again for an answer cached before textless backdrops were asked for", async () => {
    const token = await registerDevice();
    await metadata(token, "en", [theGodfather]);
    const row = await env.DB.prepare("SELECT body FROM title_metadata").first<{ body: string }>();
    const { textlessBackdropPath, ...without } = JSON.parse(row!.body);
    await env.DB.prepare("UPDATE title_metadata SET body = ?1").bind(JSON.stringify(without)).run();

    const again = await metadata(token, "en", [theGodfather]);
    expect(again.titles[0].textlessBackdropPath).toBe(textlessBackdropPath);
    expect(tmdb.requests).toHaveLength(2);
  });

  it("serves the last answer while TMDB is down, and asks again for what it never had", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-01T12:00:00Z"));
    const token = await registerDevice();
    await metadata(token, "en", [theGodfather]);
    vi.setSystemTime(new Date("2026-10-20T12:00:00Z"));
    tmdb.failWith = 429;

    const batch = await metadata(token, "en", [theGodfather, { kind: "show", tmdbId: 1396 }]);
    expect(batch.titles.map((t) => t.tmdbId)).toEqual([238]);
    expect(batch.unavailable).toEqual([{ kind: "show", tmdbId: 1396 }]);

    tmdb.failWith = null;
    const later = await metadata(token, "en", [{ kind: "show", tmdbId: 1396 }]);
    expect(later.titles.map((t) => t.tmdbId)).toEqual([1396]);
  });
});

describe("upcoming Episodes", () => {
  // The Last of Us, a season in the middle of airing: E4 aired, E5 airs next.
  beforeEach(() => {
    tmdb.show(100088, () => ({ id: 100088, name: "The Last of Us", next_episode_to_air: { season_number: 2, episode_number: 5, air_date: "2026-10-31", name: "Feel Her Love" } }));
    tmdb.season(100088, 2, (language) => ({
      season_number: 2,
      episodes: [
        { season_number: 2, episode_number: 4, air_date: "2026-10-24", name: "Day One" },
        { season_number: 2, episode_number: 5, air_date: "2026-10-31", name: language === "fr" ? "Ressens son amour" : "Feel Her Love" },
        { season_number: 2, episode_number: 6, air_date: "2026-11-07", name: "" },
        { season_number: 2, episode_number: 7, air_date: null, name: "Convergence" },
      ],
    }));
  });

  const upcoming = async (token: string, tmdbId: number, language = "en") => {
    const response = await call("POST", "/v1/upcoming", { tmdbId, language }, token);
    return { status: response.status, body: (await response.json()) as { episodes: unknown[] } };
  };

  it("lists the Episodes from the next to air to the end of its season, named in the language asked", async () => {
    const token = await registerDevice();
    const { status, body } = await upcoming(token, 100088, "fr");
    expect(status).toBe(200);
    // One without an air date has none to show.
    expect(body.episodes).toEqual([
      { season: 2, episode: 5, airDate: "2026-10-31", name: "Ressens son amour" },
      { season: 2, episode: 6, airDate: "2026-11-07", name: null },
    ]);
  });

  it("has none for a Show with nothing to air, or one TMDB doesn't have", async () => {
    const token = await registerDevice();
    expect((await upcoming(token, 1396)).body.episodes).toEqual([]);
    expect((await upcoming(token, 999)).body.episodes).toEqual([]);
  });

  it("answers from its cache for half a day, and while TMDB is down after that", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-30T08:00:00Z"));
    const token = await registerDevice();
    await upcoming(token, 100088);
    vi.setSystemTime(new Date("2026-10-30T19:00:00Z"));
    await upcoming(token, 100088);
    expect(tmdb.requests).toHaveLength(2);

    vi.setSystemTime(new Date("2026-10-31T08:00:00Z"));
    tmdb.failWith = 503;
    expect((await upcoming(token, 100088)).body.episodes).toHaveLength(2);
    expect((await upcoming(token, 1396)).status).toBe(503);
  });

  it("rejects a malformed request", async () => {
    const token = await registerDevice();
    expect((await call("POST", "/v1/upcoming", { tmdbId: "1399", language: "en" }, token)).status).toBe(400);
    expect((await call("POST", "/v1/upcoming", { tmdbId: 1399, language: "eng" }, token)).status).toBe(400);
  });
});

describe("Charts", () => {
  let netflix: FakeNetflix;
  beforeEach(() => {
    netflix = new FakeNetflix(tmdb);
    // Argentina, Canada and France, newest week first; a Region's block ends where the next country's starts.
    netflix.country("Argentina", "AR", { week: "2026-10-18", films: ["Acid"], tv: ["The Trap"] });
    netflix.country(
      "Canada",
      "CA",
      { week: "2026-10-18", films: ["Animals", "The Godfather", "Nobody Has This"], tv: [["Below", "Below: Limited Series"], ["Breaking Bad", "Breaking Bad: Season 5"]] },
      { week: "2026-10-11", films: ["Old News"], tv: ["Older News"] },
    );
    netflix.country("France", "FR", { week: "2026-10-18", films: ["The Godfather"], tv: ["Breaking Bad"] });
    // Countries are listed by name, so many come after Canada.
    for (let i = 0; i < 40; i++) netflix.country(`Zland ${i}`, `Z${String.fromCharCode(65 + (i % 26))}`, { week: "2026-10-18", films: Array.from({ length: 10 }, (_, n) => `Film ${n}`), tv: [] });
    netflix.worldwide("2026-10-18", "Films (English)", "Animals", 30_800_000);
    netflix.worldwide("2026-10-18", "Films (English)", "The Godfather", 2_000_000);
    netflix.worldwide("2026-10-18", "Films (Non-English)", "Acid", 3_000_000);
    netflix.worldwide("2026-10-18", "TV (English)", "Breaking Bad", 1_000_000);
    netflix.worldwide("2026-10-18", "TV (Non-English)", "Below", 7_100_000);
    netflix.worldwide("2026-10-11", "Films (English)", "Old News", 90_000_000);
    tmdb.searchableMovie(() => ({ id: 1001, title: "Animals", original_title: "Animals", popularity: 80 }));
    // The less popular film of the same name loses.
    tmdb.searchableMovie(() => ({ id: 1002, title: "Animals", original_title: "Animals", popularity: 3 }));
    tmdb.searchableMovie(() => ({ id: 238, title: "The Godfather", original_title: "The Godfather", popularity: 128 }));
    tmdb.searchableMovie(() => ({ id: 1003, title: "Acid", original_title: "Acid", popularity: 5 }));
    tmdb.searchableShow(() => ({ id: 2001, name: "Below", original_name: "Below", popularity: 40 }));
    tmdb.searchableShow(() => ({ id: 1396, name: "Breaking Bad", original_name: "Breaking Bad", popularity: 300 }));
    // A show named like a Top 10 film isn't it: Films are looked up among movies.
    tmdb.searchableShow(() => ({ id: 2002, name: "Animals", original_name: "Animals", popularity: 500 }));
  });

  const charts = async (token: string, region = "CA", language = "en") => {
    const response = await call("POST", "/v1/charts", { region, language }, token);
    expect(response.status).toBe(200);
    return (await response.json()) as {
      top: { worldwide: boolean; movies: { kind: string; tmdbId: number }[]; shows: { kind: string; tmdbId: number }[] };
      trending: { all: { kind: string; tmdbId: number }[]; movies: { kind: string; tmdbId: number }[]; shows: { kind: string; tmdbId: number }[] };
      genres: { kind: string; name: string; tmdbIds: number[] }[];
    };
  };
  /** Every fetch the Worker sent out, to TMDB, Netflix or anyone. */
  const sentOut = () => vi.mocked(globalThis.fetch).mock.calls.length;
  /** One run of the Cron Trigger, as Cloudflare starts it each minute. */
  const run = () => worker.scheduled(createScheduledController({ cron: "* * * * *", scheduledTime: Date.now() }), env);
  /** The Cron Trigger's next [runs] runs, all at the same moment. */
  const fill = async (runs = 10) => {
    for (let i = 0; i < runs; i++) await run();
  };
  /** Asks for the Charts, lets the Cron Trigger fill what that asked for, and asks again. */
  const filled = async (token: string, region = "CA", language = "en") => {
    await charts(token, region, language);
    await fill();
    return charts(token, region, language);
  };
  const ids = (titles: { tmdbId: number }[]) => titles.map((t) => t.tmdbId);

  it("answers the Region's newest Top 10 as TMDB IDs in rank order, names TMDB can't find left out", async () => {
    const { top } = await filled(await registerDevice());

    expect(top.worldwide).toBe(false);
    expect(top.movies).toEqual([{ kind: "movie", tmdbId: 1001 }, { kind: "movie", tmdbId: 238 }]);
    // A show's season points at the show.
    expect(top.shows).toEqual([{ kind: "show", tmdbId: 2001 }, { kind: "show", tmdbId: 1396 }]);
    expect(tmdb.searches.map((url) => url.searchParams.get("query"))).toContain("Below");
  });

  it("never reaches TMDB or Netflix while answering, only the Cron Trigger does", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-20T08:00:00Z"));
    const token = await registerDevice();
    const before = sentOut();
    // Nothing kept yet: the answer has empty lists rather than reading the sources itself.
    expect(await charts(token)).toEqual({ top: { worldwide: false, movies: [], shows: [] }, trending: { all: [], movies: [], shows: [] }, genres: [] });
    expect(sentOut()).toBe(before);

    await fill();
    expect(sentOut()).toBeGreaterThan(before);
    // Everything kept is over a week old: still only D1 is read.
    vi.setSystemTime(new Date("2026-10-30T08:00:00Z"));
    const after = sentOut();
    expect(ids((await charts(token)).top.movies)).toEqual([1001, 238]);
    expect(ids((await charts(token, "AR", "fr")).top.movies)).toEqual([1001, 1003, 238]);
    expect(sentOut()).toBe(after);
  });

  it("reads Netflix's country file once a day for every Region it lists", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-20T08:00:00Z"));
    const token = await registerDevice();
    await filled(token, "CA");
    expect(netflix.asked.countries).toBe(1);
    expect(netflix.sent.countries).toBe(netflix.countriesSize);

    // Argentina was never asked for, but was read with Canada: only its names are looked up.
    vi.setSystemTime(new Date("2026-10-20T12:00:00Z"));
    const { top } = await filled(token, "AR");
    expect(top).toEqual({ worldwide: false, movies: [{ kind: "movie", tmdbId: 1003 }], shows: [] });
    expect(netflix.asked.countries).toBe(1);
  });

  it("reads a country file of megabytes, a country's weeks running over many reads", async () => {
    // Like Netflix's file, which has every week since 2021: 32 MB.
    const old = Array.from({ length: 30_000 }, (_, i) => ({ week: `2021-${i}`, films: ["Old News"], tv: ["Older News"] }));
    netflix.country("Argentina", "AR", { week: "2026-10-18", films: ["Acid"], tv: ["The Trap"] }, ...old);
    netflix.country("Belgium", "BE", { week: "2026-10-18", films: ["Animals"], tv: [] }, ...old);
    expect(netflix.countriesSize).toBeGreaterThan(4_000_000);
    const token = await registerDevice();

    expect(ids((await filled(token, "AR")).top.movies)).toEqual([1003]);
    expect(ids((await filled(token, "BE")).top.movies)).toEqual([1001]);
    expect(ids((await filled(token, "CA")).top.movies)).toEqual([1001, 238]);
    expect(ids((await filled(token, "FR")).top.shows)).toEqual([1396]);
    expect(netflix.asked.countries).toBe(1);
  });

  it("answers the worldwide lists for a Region Netflix doesn't list, English and non-English merged by views", async () => {
    const { top } = await filled(await registerDevice(), "JP");

    expect(top.worldwide).toBe(true);
    expect(ids(top.movies)).toEqual([1001, 1003, 238]);
    expect(ids(top.shows)).toEqual([2001, 1396]);
  });

  it("fills a Region and an App language asked for the first time in the Cron Trigger's next runs, answering what it keeps till then", async () => {
    tmdb.genres("movie", "en", { id: 35, name: "Comedy" });
    tmdb.genres("movie", "fr", { id: 35, name: "Comédie" });
    tmdb.discover("movie", 35, 7, 3);
    const token = await registerDevice();
    await filled(token, "CA", "en");

    // France and French are new: the worldwide Top 10 and the genres named in English stand in.
    const first = await charts(token, "FR", "fr");
    expect(first.top.worldwide).toBe(true);
    expect(ids(first.top.movies)).toEqual([1001, 1003, 238]);
    expect(first.genres).toEqual([{ kind: "movie", name: "Comedy", tmdbIds: [7, 3] }]);

    await run();
    await run();
    const next = await charts(token, "FR", "fr");
    expect(next.top).toEqual({ worldwide: false, movies: [{ kind: "movie", tmdbId: 238 }], shows: [{ kind: "show", tmdbId: 1396 }] });
    expect(next.genres).toEqual([{ kind: "movie", name: "Comédie", tmdbIds: [7, 3] }]);
  });

  it("reads Netflix again after a day, looking up only the names it hasn't before", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-20T08:00:00Z"));
    const token = await registerDevice();
    await filled(token);
    vi.setSystemTime(new Date("2026-10-21T07:00:00Z"));
    await filled(token, "CA", "fr");
    expect(netflix.asked.countries).toBe(1);

    const searches = tmdb.searches.length;
    tmdb.searchableMovie(() => ({ id: 1004, title: "Sinners", original_title: "Sinners", popularity: 60 }));
    netflix.country("Canada", "CA", { week: "2026-10-25", films: ["Sinners", "Animals", "The Godfather"], tv: [] });
    vi.setSystemTime(new Date("2026-10-21T09:00:00Z"));
    const { top } = await filled(token);
    expect(netflix.asked.countries).toBe(2);
    expect(ids(top.movies)).toEqual([1004, 1001, 238]);
    expect(tmdb.searches.length - searches).toBe(1);
  });

  it("serves the last answer while Netflix can't be reached, and empty lists without one", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-20T08:00:00Z"));
    const token = await registerDevice();
    await filled(token);
    vi.setSystemTime(new Date("2026-10-22T08:00:00Z"));
    netflix.failWith = 503;
    await fill();

    expect(ids((await charts(token)).top.movies)).toEqual([1001, 238]);
    await env.DB.prepare("DELETE FROM chart WHERE source LIKE 'netflix%'").run();
    vi.setSystemTime(new Date("2026-10-22T09:00:00Z"));
    expect((await filled(token, "AR")).top).toEqual({ worldwide: false, movies: [], shows: [] });
  });

  it("serves the last answer while TMDB can't look names up, and tries again a while later", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-20T08:00:00Z"));
    const token = await registerDevice();
    tmdb.failWith = 503;
    expect((await filled(token)).top.movies).toEqual([]);
    tmdb.failWith = null;
    vi.setSystemTime(new Date("2026-10-20T08:20:00Z"));
    expect(ids((await filled(token)).top.movies)).toEqual([1001, 238]);
  });

  describe("TMDB trending", () => {
    beforeEach(() => {
      const movie = (id: number) => ({ id, media_type: "movie", title: `Movie ${id}` });
      const show = (id: number) => ({ id, media_type: "tv", name: `Show ${id}` });
      // 25 a week, over two of TMDB's pages; a person TMDB lists among them isn't a Title.
      tmdb.trending("all", "week", movie(1), show(2), { id: 3, media_type: "person", name: "Someone" }, ...Array.from({ length: 22 }, (_, i) => movie(100 + i)));
      tmdb.trending("movie", "week", { id: 1, title: "Movie 1" }, { id: 5, title: "Movie 5" });
      tmdb.trending("tv", "week", { id: 2, name: "Show 2" }, { id: 6, name: "Show 6" });
      tmdb.trending("all", "day", movie(999));
    });

    it("answers this week's lists, mixed, movies and shows, as TMDB Titles in order", async () => {
      const { trending } = await filled(await registerDevice());

      expect(trending.all.slice(0, 3)).toEqual([{ kind: "movie", tmdbId: 1 }, { kind: "show", tmdbId: 2 }, { kind: "movie", tmdbId: 100 }]);
      expect(trending.all).toHaveLength(24);
      expect(trending.movies).toEqual([{ kind: "movie", tmdbId: 1 }, { kind: "movie", tmdbId: 5 }]);
      expect(trending.shows).toEqual([{ kind: "show", tmdbId: 2 }, { kind: "show", tmdbId: 6 }]);
      expect(tmdb.requests.filter((url) => url.pathname.includes("/trending/")).every((url) => url.pathname.endsWith("/week"))).toBe(true);
    });

    it("keeps the lists a week, whatever the Region, and serves them while TMDB is down after that", async () => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(new Date("2026-10-20T08:00:00Z"));
      const token = await registerDevice();
      await filled(token);
      const asked = () => tmdb.requests.filter((url) => url.pathname.includes("/trending/")).length;
      const first = asked();
      vi.setSystemTime(new Date("2026-10-26T08:00:00Z"));
      await filled(token, "AR");
      expect(asked()).toBe(first);

      vi.setSystemTime(new Date("2026-10-28T08:00:00Z"));
      tmdb.failWhen = (url) => url.pathname.includes("/trending/");
      expect((await filled(token)).trending.movies).toHaveLength(2);
      expect(asked()).toBeGreaterThan(first);
    });

    it("has empty lists while TMDB is down and it never had them, and the Top 10 still answers", async () => {
      tmdb.failWhen = (url) => url.pathname.includes("/trending/");
      const answer = await filled(await registerDevice());
      expect(answer.trending).toEqual({ all: [], movies: [], shows: [] });
      expect(answer.top.movies).toHaveLength(2);
    });
  });

  describe("TMDB Discover's genre lists", () => {
    const range = (from: number, count: number) => Array.from({ length: count }, (_, i) => from + i);
    const discoveries = (genre?: number) => tmdb.discoveries.filter((url) => genre === undefined || url.searchParams.get("with_genres") === String(genre));

    beforeEach(() => {
      tmdb.genres("movie", "en", { id: 28, name: "Action" }, { id: 35, name: "Comedy" });
      tmdb.genres("movie", "fr", { id: 28, name: "Action" }, { id: 35, name: "Comédie" });
      tmdb.genres("tv", "en", { id: 18, name: "Drama" });
      tmdb.genres("tv", "fr", { id: 18, name: "Drame" });
      tmdb.discover("movie", 28, 5, 3, 9);
      tmdb.discover("movie", 35, 7, 3);
      tmdb.discover("tv", 18, 1396, 2001);
    });

    it("answers each genre's most popular movies and shows, in TMDB's order, named in the App language", async () => {
      const { genres } = await filled(await registerDevice(), "CA", "fr");

      expect(genres).toEqual([
        { kind: "movie", name: "Action", tmdbIds: [5, 3, 9] },
        { kind: "movie", name: "Comédie", tmdbIds: [7, 3] },
        { kind: "show", name: "Drame", tmdbIds: [1396, 2001] },
      ]);
      expect(discoveries().every((url) => url.searchParams.get("sort_by") === "popularity.desc")).toBe(true);
    });

    it("reads 15 pages of 20 of a genre: 300 Titles", async () => {
      tmdb.discover("movie", 28, ...range(1, 400));

      const { genres } = await filled(await registerDevice());

      expect(genres.find((g) => g.name === "Action")!.tmdbIds).toEqual(range(1, 300));
      expect(discoveries(28).map((url) => Number(url.searchParams.get("page"))).sort((a, b) => a - b)).toEqual(range(1, 15));
    });

    it("never sends more than 40 requests out in one run, filling in every genre over the next runs", async () => {
      const movieGenres = range(1, 19).map((id) => ({ id, name: `Movie genre ${id}` }));
      const showGenres = range(101, 16).map((id) => ({ id, name: `Show genre ${id}` }));
      tmdb.genres("movie", "en", ...movieGenres);
      tmdb.genres("tv", "en", ...showGenres);
      movieGenres.forEach((g) => tmdb.discover("movie", g.id, ...range(g.id * 1000, 400)));
      showGenres.forEach((g) => tmdb.discover("tv", g.id, ...range(g.id * 1000, 400)));
      const token = await registerDevice();
      await charts(token);

      let runs = 0;
      let genres: { tmdbIds: number[] }[] = [];
      while (runs < 30 && (genres.length === 0 || genres.some((g) => g.tmdbIds.length < 300))) {
        const before = sentOut();
        await run();
        expect(sentOut() - before).toBeLessThanOrEqual(40);
        runs++;
        genres = (await charts(token)).genres;
      }
      // Every genre is listed once its names are read; those not read yet are empty.
      expect(genres).toHaveLength(35);
      expect(genres.every((g) => g.tmdbIds.length === 300)).toBe(true);
      // Two lists a run, after the runs for names, trending and Netflix.
      expect(runs).toBeLessThanOrEqual(24);
    });

    it("saves each list as soon as it is read: a run stopped short keeps what it read, and the list it stopped on steps aside a while", async () => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(new Date("2026-10-20T08:00:00Z"));
      tmdb.breakWhen = (url) => url.pathname === "/3/discover/movie" && url.searchParams.get("with_genres") === "35";
      const token = await registerDevice();
      await charts(token);

      // Runs until one is stopped on Comedy, as Cloudflare stops a run that runs out of CPU.
      let stopped = 0;
      for (let i = 0; i < 10 && stopped === 0; i++) await run().catch(() => stopped++);
      expect(stopped).toBe(1);
      expect((await charts(token)).genres.map((g) => g.tmdbIds)).toEqual([[5, 3, 9], [], []]);

      // The next runs read the rest, leaving Comedy till it's been a while.
      await fill();
      expect((await charts(token)).genres.map((g) => g.tmdbIds)).toEqual([[5, 3, 9], [], [1396, 2001]]);
      tmdb.breakWhen = null;
      vi.setSystemTime(new Date("2026-10-20T08:20:00Z"));
      await fill();
      expect((await charts(token)).genres.map((g) => g.tmdbIds)).toEqual([[5, 3, 9], [7, 3], [1396, 2001]]);
    });

    it("keeps each list a week, then reads it again, serving the old one while TMDB can't answer", async () => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(new Date("2026-10-20T08:00:00Z"));
      const token = await registerDevice();
      await filled(token);
      const first = discoveries().length;
      vi.setSystemTime(new Date("2026-10-26T08:00:00Z"));
      await filled(token, "AR");
      expect(discoveries()).toHaveLength(first);

      vi.setSystemTime(new Date("2026-10-28T08:00:00Z"));
      tmdb.discover("movie", 28, 9, 5);
      tmdb.failWhen = (url) => url.pathname.startsWith("/3/discover/");
      expect((await filled(token)).genres.find((g) => g.name === "Action")!.tmdbIds).toEqual([5, 3, 9]);
      expect(discoveries().length).toBeGreaterThan(first);

      tmdb.failWhen = null;
      vi.setSystemTime(new Date("2026-10-28T08:20:00Z"));
      expect((await filled(token)).genres.find((g) => g.name === "Action")!.tmdbIds).toEqual([9, 5]);
    });

    it("reads a list once for every App language, asking only for the genres' names again", async () => {
      const token = await registerDevice();
      await filled(token, "CA", "en");
      const read = discoveries().length;

      const { genres } = await filled(token, "CA", "fr");

      expect(genres.map((g) => g.name)).toEqual(["Action", "Comédie", "Drame"]);
      expect(discoveries()).toHaveLength(read);
      expect(tmdb.requests.filter((url) => url.pathname.startsWith("/3/genre/")).map((url) => url.searchParams.get("language"))).toEqual(["en", "en", "fr", "fr"]);
    });

    it("has empty lists while TMDB is down and it never had them, and the Top 10 still answers", async () => {
      tmdb.failWhen = (url) => url.pathname.startsWith("/3/discover/");
      const token = await registerDevice();
      const answer = await filled(token);
      expect(answer.genres.map((g) => g.tmdbIds)).toEqual([[], [], []]);
      expect(answer.top.movies).toHaveLength(2);
    });

    it("has no genres while it never had their names in the App language or English", async () => {
      tmdb.failWhen = (url) => url.pathname.startsWith("/3/genre/");
      expect((await filled(await registerDevice(), "CA", "fr")).genres).toEqual([]);
    });
  });

  it("rejects a malformed request", async () => {
    const token = await registerDevice();
    expect((await call("POST", "/v1/charts", { region: "Canada", language: "en" }, token)).status).toBe(400);
    expect((await call("POST", "/v1/charts", { region: "CA", language: "eng" }, token)).status).toBe(400);
    expect(await env.DB.prepare("SELECT * FROM chart_wanted").all()).toMatchObject({ results: [] });
  });
});

describe("Games", () => {
  let espn: FakeEspn;
  const nhl = "hockey/nhl";
  /** A Sunday evening in Toronto: the Leafs are playing, the Bruins play later, and the Senators played the night before. */
  const now = new Date("2026-11-01T20:00:00Z");
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(now);
    espn = new FakeEspn(tmdb);
    espn.scoreboard(nhl, "2026-10-31", event("0", "2026-10-31T23:00:00Z", "post", sens, bruins, [{ name: "NHL Net" }, { name: "TSN5", market: "Home" }]));
    espn.scoreboard(
      nhl,
      "2026-11-01",
      event("1", "2026-11-01T19:00:00Z", "in", leafs, habs, [{ name: "ESPN+" }, { name: "MSG", market: "Away" }, { name: "ESPN+" }]),
      event("2", "2026-11-02T00:00:00Z", "pre", bruins, sens),
    );
    espn.scoreboard(nhl, "2026-11-03", event("3", "2026-11-04T00:00:00Z", "pre", leafs, bruins, [{ name: "TNT" }]), event("4", "2026-11-04T00:00:00Z", "pre", habs, sens, [], "STATUS_POSTPONED"));
    // The teams list knows a nickname the scoreboard doesn't, and the same location again.
    espn.teams(nhl, team({ ...leafs, nickname: "Toronto" }), team({ ...habs, nickname: "Montréal" }), team(bruins), team(sens));
    espn.scoreboard("football/nfl", "2026-11-01", event("100", "2026-11-01T21:25:00Z", "pre", broncos, texans, [{ name: "CBS" }]));
    // Out of season, ESPN's CFL endpoint answers with an old game.
    espn.scoreboard("football/cfl", "2026-10-31", event("200", "2022-11-20T23:00:00Z", "post", bombers, argos, [{ name: "ESPN2" }]), event("201", "2026-10-31T18:00:00Z", "post", argos, bombers));
    espn.scoreboard("football/cfl", "2026-11-01", event("202", "2022-11-20T23:00:00Z", "post", bombers, argos, [{ name: "ESPN2" }]));
  });

  const gamesOf = async (token: string, leagues: string[]) => {
    const response = await call("POST", "/v1/games", { leagues }, token);
    expect(response.status).toBe(200);
    return (await response.json()) as { leagues: { league: string; games: Record<string, unknown>[] }[] };
  };
  /** Every fetch the Worker sent out, to ESPN or anyone. */
  const sentOut = () => vi.mocked(globalThis.fetch).mock.calls.length;
  /** One run of the Cron Trigger, as Cloudflare starts it each minute. */
  const run = () => worker.scheduled(createScheduledController({ cron: "* * * * *", scheduledTime: Date.now() }), env);
  /** The Cron Trigger's next [runs] runs, a minute apart as Cloudflare starts them; the clock is left at the last. */
  const fill = async (runs = 1) => {
    for (let i = 0; i < runs; i++) {
      if (i > 0) vi.setSystemTime(Date.now() + 60_000);
      await run();
    }
  };
  /** Asks for the Games, lets the Cron Trigger fill what that asked for, and asks again. */
  const filled = async (token: string, leagues: string[], runs = 1) => {
    await gamesOf(token, leagues);
    await fill(runs);
    return gamesOf(token, leagues);
  };
  const forget = () => espn.requests.splice(0);
  const ids = (answer: { leagues: { league: string; games: Record<string, unknown>[] }[] }, league: string) => answer.leagues.find((l) => l.league === league)?.games.map((g) => g.id);

  it("answers a League's Games for today and the week ahead, by start: state, both teams with aliases and logo, the national Broadcasters, never a score", async () => {
    const { leagues } = await filled(await registerDevice(), ["nhl"]);

    const logo = (abbreviation: string) => `https://a.espncdn.com/i/teamlogos/500/scoreboard/${abbreviation.toLowerCase()}.png`;
    const torontoMapleLeafs = { id: "21", name: "Toronto Maple Leafs", shortName: "Maple Leafs", abbreviation: "TOR", aliases: ["Toronto Maple Leafs", "Maple Leafs", "TOR", "Toronto"], logo: logo("TOR") };
    const montrealCanadiens = { id: "10", name: "Montreal Canadiens", shortName: "Canadiens", abbreviation: "MTL", aliases: ["Montreal Canadiens", "Canadiens", "MTL", "Montreal", "Montréal"], logo: logo("MTL") };
    const bostonBruins = { id: "1", name: "Boston Bruins", shortName: "Bruins", abbreviation: "BOS", aliases: ["Boston Bruins", "Bruins", "BOS", "Boston"], logo: logo("BOS") };
    const ottawaSenators = { id: "14", name: "Ottawa Senators", shortName: "Senators", abbreviation: "OTT", aliases: ["Ottawa Senators", "Senators", "OTT", "Ottawa"], logo: logo("OTT") };
    expect(leagues).toEqual([
      {
        league: "nhl",
        games: [
          // Last night's game is still within a day; only the national network is a Broadcaster.
          { id: "0", league: "nhl", start: "2026-10-31T23:00:00.000Z", state: "over", home: ottawaSenators, away: bostonBruins, broadcasters: ["NHL Net"] },
          { id: "1", league: "nhl", start: "2026-11-01T19:00:00.000Z", state: "live", home: torontoMapleLeafs, away: montrealCanadiens, broadcasters: ["ESPN+"] },
          { id: "2", league: "nhl", start: "2026-11-02T00:00:00.000Z", state: "upcoming", home: bostonBruins, away: ottawaSenators, broadcasters: [] },
          // The postponed game is left out.
          { id: "3", league: "nhl", start: "2026-11-04T00:00:00.000Z", state: "upcoming", home: torontoMapleLeafs, away: bostonBruins, broadcasters: ["TNT"] },
        ],
      },
    ]);
    // No field of ESPN's that tells how the game is going: not a score, a period, a clock or a record.
    expect(JSON.stringify(leagues)).not.toMatch(/"(score|period|clock|records?|displayClock)"/i);
  });

  it("answers only the Leagues asked for, and reads only the Leagues some device has on", async () => {
    const token = await registerDevice();
    const first = await filled(token, ["nhl"]);

    expect(first.leagues.map((l) => l.league)).toEqual(["nhl"]);
    // Yesterday, today and the seven days after, and the teams: nothing of any other League.
    expect(espn.asked.sort()).toEqual([...Array.from({ length: 9 }, (_, i) => `hockey/nhl/${i === 0 ? "2026-10-31" : `2026-11-0${i}`}`), "hockey/nhl/teams"].sort());
    // A League asked for the first time is absent until the Cron Trigger's next run.
    expect((await gamesOf(token, ["nfl", "nhl"])).leagues.map((l) => l.league)).toEqual(["nhl"]);
    await fill();
    const both = await gamesOf(token, ["nfl", "nhl"]);
    expect(both.leagues.map((l) => l.league)).toEqual(["nfl", "nhl"]);
    expect(ids(both, "nfl")).toEqual(["100"]);
  });

  it("never reaches ESPN while answering, only the Cron Trigger does", async () => {
    const token = await registerDevice();
    const before = sentOut();
    expect(await gamesOf(token, ["nhl"])).toEqual({ leagues: [] });
    expect(sentOut()).toBe(before);
    await fill();
    expect(sentOut()).toBeGreaterThan(before);
    const after = sentOut();
    expect(ids(await gamesOf(token, ["nhl"]), "nhl")).toHaveLength(4);
    expect(sentOut()).toBe(after);
  });

  it("reads the current days again every 5 minutes, so Live flips, and the coming days hourly", async () => {
    const token = await registerDevice();
    await filled(token, ["nhl"]);
    forget();
    vi.setSystemTime(new Date("2026-11-01T20:04:00Z"));
    await fill();
    expect(espn.asked).toEqual([]);

    // The Bruins' game starts.
    espn.scoreboard(nhl, "2026-11-01", event("1", "2026-11-01T19:00:00Z", "post", leafs, habs, [{ name: "ESPN+" }]), event("2", "2026-11-02T00:00:00Z", "in", bruins, sens));
    vi.setSystemTime(new Date("2026-11-01T20:05:30Z"));
    await fill();
    expect(espn.asked.sort()).toEqual(["hockey/nhl/2026-10-31", "hockey/nhl/2026-11-01"]);
    const { leagues } = await gamesOf(token, ["nhl"]);
    expect(leagues[0].games.map((g) => [g.id, g.state])).toEqual([["0", "over"], ["1", "over"], ["2", "live"], ["3", "upcoming"]]);

    forget();
    vi.setSystemTime(new Date("2026-11-01T21:01:00Z"));
    await fill();
    // The current days (stale again) and the seven coming days; the teams keep for a week.
    expect(espn.asked.sort()).toEqual(Array.from({ length: 9 }, (_, i) => `hockey/nhl/${i === 0 ? "2026-10-31" : `2026-11-0${i}`}`).sort());
  });

  it("serves the kept copy while ESPN can't answer, and leaves out a League it never had", async () => {
    const token = await registerDevice();
    const before = await filled(token, ["nhl"]);
    espn.failWith = 503;
    vi.setSystemTime(new Date("2026-11-01T20:10:00Z"));
    await fill();
    expect(espn.asked).toContain("hockey/nhl/2026-11-01");

    const during = await gamesOf(token, ["nhl", "nfl"]);
    expect(during.leagues).toEqual(before.leagues);

    // Up again: the NFL, tried while ESPN was down, is read once its parts' wait is over.
    espn.failWith = null;
    vi.setSystemTime(new Date("2026-11-01T20:13:00Z"));
    await fill();
    expect((await gamesOf(token, ["nhl", "nfl"])).leagues.map((l) => l.league)).toEqual(["nhl", "nfl"]);
  });

  it("drops Games that started over a day ago, as ESPN's CFL endpoint answers out of season with an old one", async () => {
    const { leagues } = await filled(await registerDevice(), ["cfl"]);
    // Read, with nothing to show: the League is there, with no Games, rather than absent.
    expect(leagues).toEqual([{ league: "cfl", games: [] }]);
  });

  it("stops reading a League nobody asked for in 30 days, and drops its copy", async () => {
    const token = await registerDevice();
    await filled(token, ["nhl"]);
    vi.setSystemTime(new Date("2026-12-02T20:00:00Z"));
    forget();
    await fill();

    expect(espn.asked).toEqual([]);
    expect((await env.DB.prepare("SELECT count(*) AS n FROM game").first<{ n: number }>())?.n).toBe(0);
    expect((await env.DB.prepare("SELECT count(*) AS n FROM team").first<{ n: number }>())?.n).toBe(0);
    // Asked again, it is read again.
    expect((await gamesOf(token, ["nhl"])).leagues).toEqual([]);
    await fill();
    expect((await gamesOf(token, ["nhl"])).leagues.map((l) => l.league)).toEqual(["nhl"]);
  });

  it("reaches each League's schedule source at ESPN's path for it, with no user agent, a dozen requests a run at most", async () => {
    const token = await registerDevice();
    await gamesOf(token, [...LEAGUES]);
    const perRun: number[] = [];
    for (let i = 0; i < 10; i++) {
      const before = espn.requests.length;
      if (i > 0) vi.setSystemTime(Date.now() + 60_000);
      await run();
      perRun.push(espn.requests.length - before);
    }

    expect(Math.max(...perRun)).toBeLessThanOrEqual(12);
    const paths = {
      nhl: "hockey/nhl",
      nfl: "football/nfl",
      nba: "basketball/nba",
      mlb: "baseball/mlb",
      cfl: "football/cfl",
      mls: "soccer/usa.1",
      "premier-league": "soccer/eng.1",
      "champions-league": "soccer/uefa.champions",
      "la-liga": "soccer/esp.1",
      bundesliga: "soccer/ger.1",
      "serie-a": "soccer/ita.1",
      "ligue-1": "soccer/fra.1",
      "ncaa-football": "football/college-football",
      "ncaa-basketball": "basketball/mens-college-basketball",
      wnba: "basketball/wnba",
    };
    expect(Object.keys(paths).sort()).toEqual([...LEAGUES].sort());
    for (const path of Object.values(paths)) {
      expect(espn.asked).toContain(`${path}/2026-11-01`);
      expect(espn.asked).toContain(`${path}/teams`);
    }
    expect(espn.requests.every((r) => r.userAgent === null)).toBe(true);
  });

  it("rejects a malformed request", async () => {
    const token = await registerDevice();
    expect((await call("POST", "/v1/games", { leagues: ["nhl", "f1"] }, token)).status).toBe(400);
    expect((await call("POST", "/v1/games", { leagues: "nhl" }, token)).status).toBe(400);
    expect((await call("POST", "/v1/games", {}, token)).status).toBe(400);
    expect(await gamesOf(token, [])).toEqual({ leagues: [] });
  });
});

describe("file reads", () => {
  const provider = "a".repeat(64);
  const file = { audio: [{ language: "fre", codec: "audio/eac3", channels: 6 }], subtitles: [], width: 1920, height: 800, hdr: null, bitrate: 8_000_000 };

  it("keeps what one device read for every Viewer of the same Provider", async () => {
    const living = await registerDevice();
    expect((await call("POST", "/v1/file-reads", { provider, files: [{ listingId: 12, file }] }, living)).status).toBe(204);

    const bedroom = await registerDevice();
    const response = await call("POST", "/v1/file-reads/lookup", { provider, listings: [12, 13] }, bedroom);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ files: [{ listingId: 12, file }] });
    // Another Provider's Listing 12 is another file.
    const other = await call("POST", "/v1/file-reads/lookup", { provider: "b".repeat(64), listings: [12] }, bedroom);
    expect(await other.json()).toEqual({ files: [] });
  });

  it("takes a hash of the Provider's host, never the host itself", async () => {
    const token = await registerDevice();
    const response = await call("POST", "/v1/file-reads", { provider: "provider.example:8080", files: [{ listingId: 12, file }] }, token);
    expect(response.status).toBe(400);
  });

  it("refuses reads that aren't small objects", async () => {
    const token = await registerDevice();
    expect((await call("POST", "/v1/file-reads", { provider, files: [{ listingId: 12, file: "x" }] }, token)).status).toBe(400);
    const huge = { audio: Array.from({ length: 500 }, () => ({ language: "eng", codec: "audio/eac3", channels: 6 })) };
    expect((await call("POST", "/v1/file-reads", { provider, files: [{ listingId: 12, file: huge }] }, token)).status).toBe(400);
    expect((await call("POST", "/v1/file-reads/lookup", { provider, listings: [0] }, token)).status).toBe(400);
  });
});

describe("unknown Prefixes", () => {
  const provider = "a".repeat(64);
  const rows = () =>
    env.DB.prepare("SELECT prefix, listing_count, example, first_seen, last_seen FROM unknown_prefix ORDER BY prefix").all().then((r) => r.results);

  it("keeps what each Sync reports, and when each Prefix was first and last seen", async () => {
    const token = await registerDevice();
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-30T08:00:00Z"));
    const first = { provider, prefixes: [{ prefix: "BR", listingCount: 260, example: "BR - Cold Pursuit (2019)" }] };
    expect((await call("POST", "/v1/unknown-prefixes", first, token)).status).toBe(204);

    vi.setSystemTime(new Date("2026-10-31T08:00:00Z"));
    const second = { provider, prefixes: [{ prefix: "BR", listingCount: 261, example: "BR - Sonic the Hedgehog (2020)" }, { prefix: "CD", listingCount: 1, example: "CD - Call Me Kat (US)" }] };
    expect((await call("POST", "/v1/unknown-prefixes", second, token)).status).toBe(204);

    const day1 = Date.parse("2026-10-30T08:00:00Z"), day2 = Date.parse("2026-10-31T08:00:00Z");
    expect(await rows()).toEqual([
      { prefix: "BR", listing_count: 261, example: "BR - Sonic the Hedgehog (2020)", first_seen: day1, last_seen: day2 },
      { prefix: "CD", listing_count: 1, example: "CD - Call Me Kat (US)", first_seen: day2, last_seen: day2 },
    ]);
  });

  it("takes a hash of the Provider's host and well-formed Prefixes", async () => {
    const token = await registerDevice();
    const report = (body: unknown) => call("POST", "/v1/unknown-prefixes", body, token).then((r) => r.status);
    expect(await report({ provider: "provider.example:8080", prefixes: [] })).toBe(400);
    expect(await report({ provider, prefixes: [{ prefix: "BR", listingCount: 0, example: "BR - X" }] })).toBe(400);
    expect(await report({ provider, prefixes: [{ prefix: "", listingCount: 1, example: "X" }] })).toBe(400);
    expect(await report({ provider, prefixes: "BR" })).toBe(400);
  });
});
