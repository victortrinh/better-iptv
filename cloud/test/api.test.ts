import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { breakingBad, FakeTmdb, godfather } from "./fake-tmdb";

/** Seam 2: the cloud's HTTP API, run in the Workers runtime with TMDB faked. */
let tmdb: FakeTmdb;
beforeEach(async () => {
  // Each test starts with no devices and an empty cache.
  await env.DB.batch([env.DB.prepare("DELETE FROM device"), env.DB.prepare("DELETE FROM title_metadata")]);
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
