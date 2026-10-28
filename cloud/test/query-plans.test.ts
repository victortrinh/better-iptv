import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { LOOKUP_QUERY } from "../src/match";
import { CACHE_QUERY } from "../src/metadata";
import { SEARCH_CACHE_QUERY } from "../src/subtitles";

/** D1 bills every row a query reads, so a batch must search its keys rather than scan the table. */
describe("batch lookups", () => {
  const plan = async (query: string, ...params: (string | number)[]) =>
    (await env.DB.prepare(`EXPLAIN QUERY PLAN ${query}`).bind(...params).all<{ detail: string }>()).results.map((row) => row.detail);

  it("search title_lookup by its primary key", async () => {
    const details = await plan(LOOKUP_QUERY, '[["movie","titanic",1997,""]]');
    expect(details).toContainEqual(expect.stringMatching(/^SEARCH t .*\(kind=\? AND name=\? AND year=\? AND language=\?\)/));
    expect(details.filter((detail) => detail.includes("title_lookup") && detail.startsWith("SCAN"))).toEqual([]);
  });

  it("search title_metadata by its primary key", async () => {
    const details = await plan(CACHE_QUERY, "en", '[["movie",597]]');
    expect(details).toContainEqual(expect.stringMatching(/^SEARCH t .*\(kind=\? AND tmdb_id=\? AND language=\?\)/));
    expect(details.filter((detail) => detail.includes("title_metadata") && detail.startsWith("SCAN"))).toEqual([]);
  });

  it("search subtitle_search by its primary key", async () => {
    const details = await plan(SEARCH_CACHE_QUERY, "show", 1396, 1, 2, '["en","fr"]');
    expect(details).toContainEqual(expect.stringMatching(/^SEARCH subtitle_search .*\(kind=\? AND tmdb_id=\? AND season=\? AND episode=\? AND language=\?\)/));
    expect(details.filter((detail) => detail.includes("subtitle_search") && detail.startsWith("SCAN"))).toEqual([]);
  });
});
