import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { DEVICES_QUERY } from "../src/accounts";
import { READS_QUERY } from "../src/learned-lines";
import { SEARCH_CACHE_QUERY } from "../src/subtitles";
import { CACHE_QUERY } from "../src/title-facts";

/** D1 bills every row a query reads, so a batch must search its keys rather than scan the table. */
describe("batch lookups", () => {
  const plan = async (query: string, ...params: (string | number)[]) =>
    (await env.DB.prepare(`EXPLAIN QUERY PLAN ${query}`).bind(...params).all<{ detail: string }>()).results.map((row) => row.detail);

  it("search title_facts by its primary key", async () => {
    const details = await plan(CACHE_QUERY, '[["movie",597]]');
    expect(details).toContainEqual(expect.stringMatching(/^SEARCH t .*\(kind=\? AND tmdb_id=\?\)/));
    expect(details.filter((detail) => detail.includes("title_facts") && detail.startsWith("SCAN"))).toEqual([]);
  });

  it("search subtitle_search by its primary key", async () => {
    const details = await plan(SEARCH_CACHE_QUERY, "show", 1396, 1, 2, '["en","fr"]');
    expect(details).toContainEqual(expect.stringMatching(/^SEARCH subtitle_search .*\(kind=\? AND tmdb_id=\? AND season=\? AND episode=\? AND language=\?\)/));
    expect(details.filter((detail) => detail.includes("subtitle_search") && detail.startsWith("SCAN"))).toEqual([]);
  });

  it("search file_read by Provider and Prefix when a report is tallied", async () => {
    const details = await plan(READS_QUERY, "a".repeat(64), '["EN","SC"]');
    expect(details).toContainEqual(expect.stringMatching(/SEARCH file_read USING INDEX file_read_by_prefix \(provider=\? AND prefix=\?\)/));
    expect(details.filter((detail) => detail.includes("file_read") && detail.startsWith("SCAN"))).toEqual([]);
  });

  it("search the device list by its Account, not every device", async () => {
    const details = await plan(DEVICES_QUERY, "a".repeat(64));
    expect(details.filter((detail) => detail.includes("device") && detail.startsWith("SCAN"))).toEqual([]);
  });
});
