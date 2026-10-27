import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { LOOKUP_QUERY } from "../src/match";
import { CACHE_QUERY } from "../src/metadata";

/** D1 bills every row a query reads, so a batch must search its keys rather than scan the table. */
describe("batch lookups", () => {
  const plan = async (query: string, ...params: string[]) =>
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
});
