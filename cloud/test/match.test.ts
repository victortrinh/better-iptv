import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { FakeTmdb, godfather } from "./fake-tmdb";

/** Seam 2: Matching Listings by name, run in the Workers runtime with TMDB faked. */
let tmdb: FakeTmdb;
let token: string;
beforeEach(async () => {
  await env.DB.batch(["device", "title_metadata", "title_lookup"].map((table) => env.DB.prepare(`DELETE FROM ${table}`)));
  tmdb = new FakeTmdb();
  tmdb.movie(238, godfather);
  tmdb.show(1399, gameOfThrones);
  tmdb.searchableMovie((language) => ({ id: 597, title: language === "fr" ? "Titanic" : "Titanic", original_title: "Titanic", release_date: "1997-11-18", popularity: 90 }));
  tmdb.searchableMovie(() => ({ id: 2699, title: "Titanic II", original_title: "Titanic II", release_date: "2010-08-07", popularity: 5 }));
  tmdb.searchableMovie(() => ({ id: 16535, title: "Titanic", original_title: "Titanic", release_date: "1953-04-16", popularity: 8 }));
  tmdb.searchableMovie((language) => ({ id: 238, title: language === "fr" ? "Le Parrain" : "The Godfather", original_title: "The Godfather", release_date: "1972-03-14", popularity: 128 }));
  tmdb.searchableShow(() => ({ id: 36175, name: "Trotro", original_name: "Trotro", first_air_date: "2004-09-06", popularity: 3 }));
  const registered = await worker.fetch(new Request("https://cloud.example/v1/devices", { method: "POST" }), env);
  token = ((await registered.json()) as { token: string }).token;
});
afterEach(() => {
  tmdb.restore();
  vi.useRealTimers();
});

/** Game of Thrones' TV details, with what a Mismatch check reads. */
const gameOfThrones = () => ({
  id: 1399,
  name: "Game of Thrones",
  original_name: "Game of Thrones",
  original_language: "en",
  first_air_date: "2011-04-17",
  translations: {
    translations: [
      { iso_639_1: "en", iso_3166_1: "US", data: { name: "" } },
      { iso_639_1: "fr", iso_3166_1: "FR", data: { name: "Le Trône de fer" } },
    ],
  },
  alternative_titles: { results: [{ iso_3166_1: "US", title: "GoT" }] },
});

const post = (body: unknown, auth = token) =>
  worker.fetch(
    new Request("https://cloud.example/v1/match", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${auth}` },
      body: JSON.stringify(body),
    }),
    env,
  );

async function match(...listings: object[]) {
  const response = await post({ listings });
  expect(response.status).toBe(200);
  return ((await response.json()) as { matches: unknown[] }).matches;
}

describe("Matching a Listing without a TMDB ID", () => {
  it("finds the Title by its cleaned name and year", async () => {
    expect(await match({ kind: "movie", name: "Titanic", year: 1997 })).toEqual([{ kind: "movie", tmdbId: 597 }]);
  });

  it("allows the year to be one off TMDB's, and no more", async () => {
    // `EN - Titanic  (1996)`: TMDB says 1997. Titanic II, also found, has another name.
    expect(await match({ kind: "movie", name: "Titanic", year: 1996 }, { kind: "movie", name: "Titanic", year: 1954 }, { kind: "movie", name: "Titanic", year: 1999 })).toEqual([
      { kind: "movie", tmdbId: 597 },
      { kind: "movie", tmdbId: 16535 },
      { kind: "movie", tmdbId: null },
    ]);
  });

  it("without a year, takes the most popular Title by that name", async () => {
    expect(await match({ kind: "movie", name: "TITANIC" })).toEqual([{ kind: "movie", tmdbId: 597 }]);
  });

  it("searches in the Listing's language, so a translated name is found", async () => {
    expect(await match({ kind: "movie", name: "Le Parrain", year: 1972, language: "fr" })).toEqual([{ kind: "movie", tmdbId: 238 }]);
    expect(tmdb.searches[0].searchParams.get("language")).toBe("fr");
  });

  it("reads an acronym the same with or without its dots", async () => {
    tmdb.searchableMovie(() => ({ id: 19625, title: "Munna Bhai M.B.B.S.", original_title: "मुन्ना भाई एम.बी.बी.एस.", release_date: "2003-12-19", popularity: 6 }));
    expect(await match({ kind: "movie", name: "Munna Bhai MBBS", year: 2003 })).toEqual([{ kind: "movie", tmdbId: 19625 }]);
  });

  it("answers null when TMDB has no Title by that name", async () => {
    expect(await match({ kind: "movie", name: "Mohgiben MBBS", year: 2023, language: "gu" })).toEqual([{ kind: "movie", tmdbId: null }]);
  });
});

describe("Checking a Listing's TMDB ID", () => {
  it("keeps the ID when the name is one of TMDB's titles for it, in any language, without searching", async () => {
    expect(
      await match(
        { kind: "movie", name: "Der Pate", year: 1972, language: "de", tmdbId: 238 },
        { kind: "show", name: "Le Trône de fer", language: "fr", tmdbId: 1399 },
        { kind: "show", name: "Game of Thrones", year: 2011, language: "fr", tmdbId: 1399 },
        // An alternative title.
        { kind: "movie", name: "Mario Puzo's The Godfather", tmdbId: 238 },
      ),
    ).toEqual([
      { kind: "movie", tmdbId: 238 },
      { kind: "show", tmdbId: 1399 },
      { kind: "show", tmdbId: 1399 },
      { kind: "movie", tmdbId: 238 },
    ]);
    expect(tmdb.searches).toHaveLength(0);
  });

  it("keeps the ID when the name holds one of TMDB's titles, as numbered top lists and double titles do", async () => {
    expect(
      await match(
        { kind: "movie", name: "02. The Godfather", year: 1972, language: "en", tmdbId: 238 },
        { kind: "movie", name: "Le Parrain - The Godfather", year: 1972, language: "fr", tmdbId: 238 },
      ),
    ).toEqual([
      { kind: "movie", tmdbId: 238 },
      { kind: "movie", tmdbId: 238 },
    ]);
  });

  it("treats any other name as a Mismatch and Matches it again by name", async () => {
    // `FR - Trotro`, filed under Game of Thrones.
    expect(await match({ kind: "show", name: "Trotro", language: "fr", tmdbId: 1399 })).toEqual([{ kind: "show", tmdbId: 36175 }]);
    // `IN-GU - Mohgiben.MBBS.2023`, filed under Munna Bhai M.B.B.S., which TMDB's search doesn't find either.
    expect(await match({ kind: "movie", name: "Mohgiben MBBS", year: 2023, language: "gu", tmdbId: 238 })).toEqual([{ kind: "movie", tmdbId: null }]);
  });

  it("Matches by name when TMDB has no Title with the ID", async () => {
    expect(await match({ kind: "movie", name: "Titanic", year: 1997, tmdbId: 999999 })).toEqual([{ kind: "movie", tmdbId: 597 }]);
  });
});

describe("the lookup cache", () => {
  it("answers a repeat lookup, found or not, without reaching TMDB", async () => {
    await match({ kind: "movie", name: "Titanic", year: 1996 }, { kind: "movie", name: "Mohgiben MBBS", year: 2023, language: "gu" });
    const again = await match({ kind: "movie", name: "titanic", year: 1996 }, { kind: "movie", name: "Mohgiben MBBS", year: 2023, language: "gu" });

    expect(again).toEqual([{ kind: "movie", tmdbId: 597 }, { kind: "movie", tmdbId: null }]);
    expect(tmdb.searches).toHaveLength(2);
  });

  it("checks an ID against TMDB once, from the metadata cache after that", async () => {
    await match({ kind: "movie", name: "Der Pate", tmdbId: 238 });
    await match({ kind: "movie", name: "Le Parrain", tmdbId: 238 });

    expect(tmdb.requests.map((url) => url.pathname)).toEqual(["/3/movie/238"]);
  });

  it("asks TMDB again a day after it found nothing", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-01T12:00:00Z"));
    await match({ kind: "movie", name: "Mohgiben MBBS", year: 2023 });
    vi.setSystemTime(new Date("2026-10-02T13:00:00Z"));
    await match({ kind: "movie", name: "Mohgiben MBBS", year: 2023 });

    expect(tmdb.searches).toHaveLength(2);
  });

  it("answers null while TMDB is down, and doesn't remember that", async () => {
    tmdb.failWith = 429;
    expect(await match({ kind: "movie", name: "Titanic", year: 1997 }, { kind: "movie", name: "Der Pate", tmdbId: 238 })).toEqual([null, null]);

    tmdb.failWith = null;
    expect(await match({ kind: "movie", name: "Titanic", year: 1997 }, { kind: "movie", name: "Der Pate", tmdbId: 238 })).toEqual([
      { kind: "movie", tmdbId: 597 },
      { kind: "movie", tmdbId: 238 },
    ]);
  });
});

describe("match requests", () => {
  it("need a registered device's token", async () => {
    expect((await post({ listings: [{ kind: "movie", name: "Titanic" }] }, "A".repeat(43))).status).toBe(401);
    expect(tmdb.requests).toHaveLength(0);
  });

  it("are rejected when malformed", async () => {
    const tooMany = Array.from({ length: 26 }, () => ({ kind: "movie", name: "Titanic" }));
    for (const listings of [
      [{ kind: "series", name: "Titanic" }],
      [{ kind: "movie", name: "" }],
      [{ kind: "movie", name: "Titanic", year: "1997" }],
      [{ kind: "movie", name: "Titanic", tmdbId: -1 }],
      [{ kind: "movie", name: "Titanic", language: "fr-CA" }],
      tooMany,
    ]) {
      expect((await post({ listings })).status).toBe(400);
    }
    expect(tmdb.requests).toHaveLength(0);
  });
});
