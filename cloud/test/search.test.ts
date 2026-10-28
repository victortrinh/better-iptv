import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { FakeTmdb } from "./fake-tmdb";

/** Seam 2: `/v1/search`, run in the Workers runtime with TMDB faked (ADR 0009). */
let tmdb: FakeTmdb;
let token: string;
beforeEach(async () => {
  await env.DB.batch(["device", "title_search", "person_credits"].map((table) => env.DB.prepare(`DELETE FROM ${table}`)));
  tmdb = new FakeTmdb();
  tmdb.multiMovie(238, 128.4, "The Godfather", "Der Pate", "Le Parrain");
  tmdb.multiMovie(240, 60, "The Godfather Part II", "Der Pate 2");
  tmdb.multiShow(1399, 400, "Game of Thrones", "Le Trône de fer");
  tmdb.person(1158, "Al Pacino", 40, {
    cast: [{ id: 238, media_type: "movie" }, { id: 240, media_type: "movie" }, { id: 1399, media_type: "tv" }],
    crew: [{ id: 999, media_type: "movie", job: "Producer" }],
  });
  tmdb.person(525, "Christopher Nolan", 30, { crew: [{ id: 27205, media_type: "movie", job: "Director" }, { id: 27205, media_type: "movie", job: "Writer" }] });
  for (const [id, popularity] of [[1, 5], [2, 4], [3, 3], [4, 2]]) tmdb.person(id, `Chris Number ${id}`, popularity, { cast: [{ id: 100 + id, media_type: "movie" }] });
  token = ((await (await worker.fetch(new Request("https://cloud.example/v1/devices", { method: "POST" }), env)).json()) as { token: string }).token;
});
afterEach(() => {
  tmdb.restore();
  vi.useRealTimers();
});

const call = (body: unknown) =>
  worker.fetch(
    new Request("https://cloud.example/v1/search", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    }),
    env,
  );

async function search(query: string) {
  const response = await call({ query });
  expect(response.status).toBe(200);
  return (await response.json()) as { titles: { kind: string; tmdbId: number; popularity: number }[]; people: { name: string; titles: { kind: string; tmdbId: number }[] }[] };
}

describe("search", () => {
  it("finds Titles by any of TMDB's names for them", async () => {
    expect((await search("der pate")).titles).toEqual([
      { kind: "movie", tmdbId: 238, popularity: 128.4 },
      { kind: "movie", tmdbId: 240, popularity: 60 },
    ]);
    expect((await search("trône")).titles).toEqual([{ kind: "show", tmdbId: 1399, popularity: 400 }]);
  });

  it("finds people with every Title they acted in or directed", async () => {
    expect((await search("pacino")).people).toEqual([
      { name: "Al Pacino", titles: [{ kind: "movie", tmdbId: 238 }, { kind: "movie", tmdbId: 240 }, { kind: "show", tmdbId: 1399 }] },
    ]);
    expect((await search("nolan")).people).toEqual([{ name: "Christopher Nolan", titles: [{ kind: "movie", tmdbId: 27205 }] }]);
  });

  it("fetches credits for the 3 most popular people only, so a query makes at most 4 TMDB requests", async () => {
    const found = await search("chris number");
    expect(found.people.map((p) => p.name)).toEqual(["Chris Number 1", "Chris Number 2", "Chris Number 3"]);
    expect(tmdb.requests).toHaveLength(4);
  });

  it("answers the same query again, however it is written, without asking TMDB", async () => {
    await search("Pacino");
    const before = tmdb.requests.length;
    expect((await search("  PACINO ")).people).toHaveLength(1);
    expect(tmdb.requests).toHaveLength(before);
  });

  it("searches TMDB again after a day, and keeps credits a week", async () => {
    vi.useFakeTimers({ now: Date.parse("2026-10-28T12:00:00Z"), toFake: ["Date"] });
    await search("pacino");
    vi.setSystemTime(Date.parse("2026-10-29T13:00:00Z"));
    tmdb.requests.length = 0;
    await search("pacino");
    expect(tmdb.requests.map((url) => url.pathname)).toEqual(["/3/search/multi"]);
  });

  it("serves yesterday's answer when TMDB can't search, and 503 when there is none", async () => {
    vi.useFakeTimers({ now: Date.parse("2026-10-28T12:00:00Z"), toFake: ["Date"] });
    await search("pacino");
    vi.setSystemTime(Date.parse("2026-10-30T12:00:00Z"));
    tmdb.failWith = 429;
    expect((await search("pacino")).people).toHaveLength(1);
    expect((await call({ query: "brando" })).status).toBe(503);
  });

  it("leaves out a person whose credits TMDB can't give right now", async () => {
    tmdb.failWhen = (url) => url.pathname.includes("/person/");
    expect((await search("pacino")).people).toEqual([]);
    tmdb.failWhen = null;
    expect((await search("pacino")).people).toHaveLength(1);
  });

  it("needs a device token", async () => {
    token = "";
    expect((await call({ query: "pacino" })).status).toBe(401);
  });

  it("rejects a request without a query", async () => {
    expect((await call({})).status).toBe(400);
    expect((await call({ query: "   " })).status).toBe(400);
    expect((await call({ query: "x".repeat(101) })).status).toBe(400);
    expect(tmdb.requests).toHaveLength(0);
  });
});
