import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";

/** Learned lines (ADR 0014): file-read reports carry the Listing's Prefix and hints, and the cloud learns what most reads agree on. */
const call = (path: string, body: unknown, token?: string) =>
  worker.fetch(
    new Request(`https://cloud.example${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    }),
    env,
  );

const provider = "a".repeat(64);

const audio = (...languages: (string | null)[]) => languages.map((language) => ({ language, codec: "audio/aac", channels: 2 }));
const file = (languages: (string | null)[], extra: Record<string, unknown> = {}) => ({ audio: audio(...languages), subtitles: [], width: 1920, height: 1080, ...extra });

let token: string;
let nextListing = 1;

/** Reports one read per file, each of a fresh Listing under [prefix]. */
async function report(prefix: string | null, files: Record<string, unknown>[], hints: string[] = []) {
  for (let i = 0; i < files.length; i += 50) {
    const batch = files.slice(i, i + 50).map((f) => ({ listingId: nextListing++, file: f, prefix, hints }));
    const response = await call("/v1/file-reads", { provider, files: batch }, token);
    expect(response.status).toBe(204);
  }
}

const times = <T>(n: number, value: T): T[] => Array.from({ length: n }, () => value);

async function served(): Promise<string> {
  return (await call("/v1/provider-table", { provider }, token)).text();
}

/** The learned section's line for [prefix], or undefined. */
async function learned(prefix: string): Promise<string | undefined> {
  const text = await served();
  const section = text.split("[learned prefixes]\n")[1] ?? "";
  return section.split("\n").find((line) => line.split("|")[0].trim() === prefix);
}

describe("Learned lines", () => {
  beforeEach(async () => {
    await env.DB.batch(["DELETE FROM file_read", "DELETE FROM learned_line", "DELETE FROM provider_table", "DELETE FROM unknown_prefix"].map((s) => env.DB.prepare(s)));
    token = ((await (await call("/v1/devices", {})).json()) as { token: string }).token;
  });

  it("keeps a report's Prefix and hints with the file", async () => {
    await report("SC", [file(["swe"])], ["MULTI-SUBS"]);
    const row = await env.DB.prepare("SELECT prefix, hints FROM file_read WHERE provider = ?1").bind(provider).first<{ prefix: string; hints: string }>();
    expect(row).toEqual({ prefix: "SC", hints: '["MULTI-SUBS"]' });
  });

  it("learns a language once 10 reads carry it alone", async () => {
    await report("XF", times(9, file(["fre"])));
    expect(await learned("XF")).toBeUndefined();
    await report("XF", [file(["fra"])]);
    expect(await learned("XF")).toBe("XF | language=fr; quality=HD");
  });

  it("learns at 9 agreeing and 1 differing, not at 8 and 2", async () => {
    await report("XF", [...times(9, file(["fre"])), file(["ger"])]);
    expect(await learned("XF")).toContain("language=fr");
    await report("YF", [...times(8, file(["fre"])), file(["ger"]), file(["ita"])]);
    expect(await learned("YF") ?? "").not.toContain("language=");
  });

  it("learns from all of a Prefix's Listings when it has fewer than 10", async () => {
    await call("/v1/unknown-prefixes", { provider, prefixes: [{ prefix: "QQ", listingCount: 4, example: "QQ - A Film (2020)" }] }, token);
    await report("QQ", times(3, file(["por"])));
    expect(await learned("QQ")).toBeUndefined();
    await report("QQ", [file(["por"])]);
    expect(await learned("QQ")).toContain("language=pt");
  });

  it("learns Multi-audio, not a language, when most files carry several audio languages", async () => {
    await report("NX", [...times(7, file(["eng", "fre", "ger"])), ...times(3, file(["eng"]))]);
    const line = await learned("NX");
    expect(line).toContain("multi-audio");
    expect(line).not.toContain("language=");
  });

  it("learns quality, HDR and Dolby audio from the files, never a Source, Clutter or Tier tag", async () => {
    const uhd = { audio: [{ language: "eng", codec: "audio/eac3", channels: 6 }], subtitles: [], width: 3840, height: 2160, hdr: "DolbyVision" };
    await report("UH", times(10, uhd), ["NETFLIX"]);
    const line = (await learned("UH"))!;
    expect(line).toBe("UH | language=en; quality=4K; hdr=DV; dolby-audio");
    expect(line).not.toMatch(/source|clutter|tier/);
  });

  it("withdraws a line when a later batch drops agreement under the bar", async () => {
    await report("XF", times(10, file(["fre"])));
    expect(await learned("XF")).toContain("language=fr");
    await report("XF", times(5, file(["ger"])));
    expect(await learned("XF") ?? "").not.toContain("language=");
    const row = await env.DB.prepare("SELECT status, withdrawn_at FROM learned_line WHERE provider = ?1 AND prefix = 'XF' AND attribute = 'language'")
      .bind(provider).first<{ status: string; withdrawn_at: number | null }>();
    expect(row?.status).not.toBe("learned");
    expect(row?.withdrawn_at).not.toBeNull();
  });

  it("keeps no line for a split Prefix, and keeps the split with each language's share", async () => {
    await report("SC", [...times(11, file(["swe"])), ...times(9, file(["dan"]))]);
    expect(await learned("SC") ?? "").not.toContain("language=");
    const row = await env.DB.prepare("SELECT status, reads, shares FROM learned_line WHERE provider = ?1 AND prefix = 'SC' AND attribute = 'language'")
      .bind(provider).first<{ status: string; reads: number; shares: string }>();
    expect(row?.status).toBe("split");
    expect(row?.reads).toBe(20);
    expect(JSON.parse(row!.shares)).toEqual({ sv: 11, da: 9 });
  });

  it("serves hand lines over learned lines", async () => {
    await env.DB.prepare("INSERT INTO provider_table (provider, hand, loaded_at) VALUES (?1, ?2, 1)").bind(provider, "[prefixes]\nXF | language=de\n").run();
    await report("XF", times(10, file(["fre"])));
    const text = await served();
    expect(text.indexOf("XF | language=de")).toBeGreaterThanOrEqual(0);
    expect(text.indexOf("XF | language=de")).toBeLessThan(text.indexOf("[learned prefixes]"));
    expect(await learned("XF")).toContain("language=fr");
  });

  it("learns nothing from reads without a Prefix, and refuses a Prefix that isn't a code", async () => {
    await report(null, times(10, file(["fre"])));
    expect(await served()).toBe("");
    const bad = await call("/v1/file-reads", { provider, files: [{ listingId: 1, file: file(["fre"]), prefix: "EN - The Godfather (1972)", hints: [] }] }, token);
    expect(bad.status).toBe(400);
    const badHints = await call("/v1/file-reads", { provider, files: [{ listingId: 1, file: file(["fre"]), prefix: "EN", hints: "MULTI-SUBS" }] }, token);
    expect(badHints.status).toBe(400);
  });

  it("re-tallies a Listing that moved to another Prefix", async () => {
    await report("XF", times(10, file(["fre"])));
    // The same ten Listings, now under another Prefix.
    nextListing -= 10;
    await report("ZZ", times(10, file(["fre"])));
    expect(await learned("XF")).toBeUndefined();
    expect(await learned("ZZ")).toContain("language=fr");
  });
});
