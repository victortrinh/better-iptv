import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { FakeSubtitleServices } from "./fake-subtitles";

/** Seam 2: the cloud's subtitle endpoints, run in the Workers runtime with OpenSubtitles and Subdl faked. */
let services: FakeSubtitleServices;
let token: string;
beforeEach(async () => {
  await env.DB.batch(
    ["device", "title_metadata", "subtitle_search", "subtitle_file", "service_session"].map((table) => env.DB.prepare(`DELETE FROM ${table}`)),
  );
  services = new FakeSubtitleServices();
  token = await registerDevice();
  // The Godfather's TMDB metadata, as a metadata request would have cached it: it carries the IMDb ID.
  await env.DB.prepare("INSERT INTO title_metadata (kind, tmdb_id, language, body, fetched_at) VALUES ('movie', 238, 'en', ?1, ?2)")
    .bind(JSON.stringify({ kind: "movie", tmdbId: 238, imdbId: "tt0068646" }), Date.now())
    .run();
});
afterEach(() => {
  services.restore();
  vi.useRealTimers();
});

const call = (path: string, body?: unknown, auth: string | null = token, withEnv: Env = env) =>
  worker.fetch(
    new Request(`https://cloud.example${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(auth ? { Authorization: `Bearer ${auth}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    withEnv,
  );

type Env = typeof env;

async function registerDevice(): Promise<string> {
  const response = await call("/v1/devices", undefined, null);
  return ((await response.json()) as { token: string }).token;
}

interface Offer {
  id: string;
  language: string;
  source: string;
  release: string;
  hearingImpaired: boolean;
  downloads: number | null;
}

async function search(body: object, withEnv: Env = env) {
  const response = await call("/v1/subtitles", body, token, withEnv);
  expect(response.status).toBe(200);
  return (await response.json()) as { subtitles: Offer[]; unavailable: string[] };
}

async function download(id: string) {
  const response = await call("/v1/subtitles/download", { id });
  return { status: response.status, body: (await response.json()) as { format?: string; text?: string; error?: string } };
}

const godfather = { kind: "movie", tmdbId: 238, languages: ["fr"] };
const srt = (line: string) => `1\n00:00:01,000 --> 00:00:03,000\n${line}\n`;

describe("subtitle search", () => {
  it("offers both services' subtitles in each language, OpenSubtitles' most downloaded first, those for the hard of hearing last", async () => {
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "The.Godfather.1972.BluRay", downloads: 300, files: { "a.srt": srt("Bonjour") } });
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "The.Godfather.1972.WEB", downloads: 900, files: { "b.srt": srt("Salut") } });
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "The.Godfather.SDH", downloads: 5000, hearingImpaired: true, files: { "c.srt": srt("[Musique]") } });
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "Machine", downloads: 9999, machineTranslated: true, files: { "d.srt": srt("Bonjour") } });
    services.openSubtitles({ tmdbId: 238, language: "de", release: "German", downloads: 50, files: { "e.srt": srt("Hallo") } });
    services.subdl({ tmdbId: 238, language: "FR", release: "The.Godfather.1972.DVDRip", files: { "f.srt": srt("Bonsoir") } });

    const { subtitles, unavailable } = await search(godfather);

    expect(subtitles.map((s) => [s.source, s.release, s.hearingImpaired])).toEqual([
      ["opensubtitles", "The.Godfather.1972.WEB", false],
      ["opensubtitles", "The.Godfather.1972.BluRay", false],
      ["subdl", "The.Godfather.1972.DVDRip", false],
      ["opensubtitles", "The.Godfather.SDH", true],
    ]);
    expect(subtitles.every((s) => s.language === "fr")).toBe(true);
    expect(subtitles[0].downloads).toBe(900);
    expect(unavailable).toEqual([]);
    // Subdl's zip urls carry the cloud's key: an offer's id goes to devices, so it must not.
    expect(subtitles.some((s) => s.id.includes("test-subdl-key"))).toBe(false);
  });

  it("asks each service by TMDB ID and, from the cached metadata, IMDb ID, with the cloud's own keys", async () => {
    await search(godfather);

    const [os, subdl] = [services.requests.find((r) => r.url.includes("opensubtitles"))!, services.requests.find((r) => r.url.includes("subdl"))!];
    const osUrl = new URL(os.url);
    expect(osUrl.searchParams.get("tmdb_id")).toBe("238");
    expect(osUrl.searchParams.get("imdb_id")).toBe("0068646");
    expect(os.headers.get("Api-Key")).toBe("test-os-key");
    const subdlUrl = new URL(subdl.url);
    expect(subdlUrl.searchParams.get("tmdb_id")).toBe("238");
    expect(subdlUrl.searchParams.get("imdb_id")).toBe("tt0068646");
    expect(subdlUrl.searchParams.get("type")).toBe("movie");
  });

  it("finds an Episode's subtitles by its Show, season and episode", async () => {
    services.openSubtitles({ tmdbId: 1396, season: 1, episode: 2, language: "en", release: "Breaking.Bad.S01E02", downloads: 10, files: { "bb.srt": srt("Hi") } });
    services.openSubtitles({ tmdbId: 1396, season: 1, episode: 3, language: "en", release: "Breaking.Bad.S01E03", downloads: 10, files: { "bb3.srt": srt("Hi") } });
    services.subdl({ tmdbId: 1396, season: 1, language: "EN", release: "Breaking.Bad.S01.Complete", files: { "S01E02.srt": srt("Hi") } });

    const { subtitles } = await search({ kind: "show", tmdbId: 1396, season: 1, episode: 2, languages: ["en"] });

    expect(subtitles.map((s) => s.release)).toEqual(["Breaking.Bad.S01E02", "Breaking.Bad.S01.Complete"]);
    const os = new URL(services.requests.find((r) => r.url.includes("opensubtitles"))!.url);
    expect(os.searchParams.get("parent_tmdb_id")).toBe("1396");
    expect(os.searchParams.get("season_number")).toBe("1");
    expect(new URL(services.requests.find((r) => r.url.includes("subdl"))!.url).searchParams.get("type")).toBe("tv");
  });

  it("asks for Portuguese and Chinese in both their forms, and answers in ISO 639-1", async () => {
    services.openSubtitles({ tmdbId: 238, language: "pt-BR", release: "Brasil", downloads: 1, files: { "pt.srt": srt("Olá") } });
    services.subdl({ tmdbId: 238, language: "BR_PT", release: "Brasil 2", files: { "pt2.srt": srt("Olá") } });

    const { subtitles } = await search({ kind: "movie", tmdbId: 238, languages: ["pt"] });

    expect(subtitles.map((s) => [s.language, s.release])).toEqual([["pt", "Brasil"], ["pt", "Brasil 2"]]);
  });

  it("keeps at most five offers per language", async () => {
    for (let i = 0; i < 8; i++) services.openSubtitles({ tmdbId: 238, language: "fr", release: `r${i}`, downloads: i, files: { "x.srt": srt("x") } });
    expect((await search(godfather)).subtitles).toHaveLength(5);
  });

  it("answers a repeat search from the cache, and asks only about languages it hasn't searched", async () => {
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "fr", downloads: 1, files: { "x.srt": srt("x") } });
    await search(godfather);
    const asked = services.requests.length;

    expect((await search(godfather)).subtitles).toHaveLength(1);
    expect(services.requests).toHaveLength(asked);

    await search({ ...godfather, languages: ["fr", "es"] });
    expect(new URL(services.requests.at(-1)!.url).searchParams.get("languages")).toBe("ES");
  });

  it("searches again after a week, or a day when nothing was found", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "fr", downloads: 1, files: { "x.srt": srt("x") } });
    await search({ ...godfather, languages: ["fr", "es"] });
    const asked = services.requests.length;

    vi.setSystemTime(Date.now() + 25 * 3600_000);
    await search({ ...godfather, languages: ["fr", "es"] });
    expect(new URL(services.requests.at(-1)!.url).searchParams.get("languages")).toBe("ES");
    const second = services.requests.length;
    expect(second).toBeGreaterThan(asked);

    vi.setSystemTime(Date.now() + 7 * 24 * 3600_000);
    await search(godfather);
    expect(services.requests.length).toBeGreaterThan(second);
  });

  it("serves what one service found while the other is down, says so, and keeps none of it", async () => {
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "os", downloads: 1, files: { "x.srt": srt("x") } });
    services.subdl({ tmdbId: 238, language: "FR", release: "subdl", files: { "y.srt": srt("y") } });
    services.subdlFailWith = 503;

    const down = await search(godfather);
    expect(down.subtitles.map((s) => s.release)).toEqual(["os"]);
    expect(down.unavailable).toEqual(["subdl"]);

    services.subdlFailWith = null;
    expect((await search(godfather)).subtitles.map((s) => s.release)).toEqual(["os", "subdl"]);
  });

  it("doesn't ask a service the cloud has no keys for", async () => {
    const { subtitles, unavailable } = await search(godfather, { ...env, SUBDL_API_KEY: undefined } as Env);

    expect(services.calls).toEqual(["opensubtitles /api/v1/subtitles"]);
    expect(subtitles).toEqual([]);
    expect(unavailable).toEqual([]);
  });

  it("refuses a search that isn't for a Movie or Episode in a few ISO 639-1 languages", async () => {
    const bad = [
      { ...godfather, languages: [] },
      { ...godfather, languages: ["fre"] },
      { ...godfather, languages: ["a", "b", "c", "d", "e", "f"] },
      { ...godfather, season: 1, episode: 1 },
      { kind: "show", tmdbId: 1396, languages: ["en"] },
      { kind: "film", tmdbId: 238, languages: ["en"] },
    ];
    for (const body of bad) expect((await call("/v1/subtitles", body)).status).toBe(400);
    expect((await call("/v1/subtitles", godfather, null)).status).toBe(401);
    expect(services.requests).toHaveLength(0);
  });
});

describe("subtitle download", () => {
  it("downloads an OpenSubtitles file with the cloud's login, which it reuses, and never downloads a file twice", async () => {
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "a", downloads: 2, files: { "a.srt": srt("Bonjour") } });
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "b", downloads: 1, files: { "b.srt": srt("Salut") } });
    const [first, second] = (await search(godfather)).subtitles;

    expect(await download(first.id)).toEqual({ status: 200, body: { format: "srt", text: srt("Bonjour") } });
    expect((await download(second.id)).body.text).toBe(srt("Salut"));
    expect(services.calls.filter((c) => c.endsWith("/login"))).toHaveLength(1);

    const before = services.requests.length;
    expect((await download(first.id)).body.text).toBe(srt("Bonjour"));
    expect(services.requests).toHaveLength(before);
    expect(services.downloadsLeft).toBe(18);
  });

  it("signs in again when OpenSubtitles stops taking the kept login", async () => {
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "a", downloads: 2, files: { "a.srt": srt("Bonjour") } });
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "b", downloads: 1, files: { "b.srt": srt("Salut") } });
    const [first, second] = (await search(godfather)).subtitles;
    await download(first.id);
    services.tokens.clear();

    expect((await download(second.id)).status).toBe(200);
    expect(services.calls.filter((c) => c.endsWith("/login"))).toHaveLength(2);
  });

  it("answers 503 once the day's OpenSubtitles downloads are used up", async () => {
    services.openSubtitles({ tmdbId: 238, language: "fr", release: "a", downloads: 2, files: { "a.srt": srt("Bonjour") } });
    const [offer] = (await search(godfather)).subtitles;
    services.downloadsLeft = 0;

    expect((await download(offer.id)).status).toBe(503);
  });

  it("takes the subtitle out of Subdl's zip: the asked Episode's from a season's", async () => {
    services.subdl({
      tmdbId: 1396,
      season: 1,
      language: "EN",
      release: "Breaking.Bad.S01",
      files: { "Breaking.Bad.S01E01.srt": srt("One"), "Breaking.Bad.S01E02.srt": srt("Two"), "Breaking.Bad.S01E12.srt": srt("Twelve"), "readme.txt": "hi" },
    });
    const [offer] = (await search({ kind: "show", tmdbId: 1396, season: 1, episode: 2, languages: ["en"] })).subtitles;

    expect(await download(offer.id)).toEqual({ status: 200, body: { format: "srt", text: srt("Two") } });
  });

  it("keeps an ASS file's format, and reads a file in its language's old code page as UTF-8", async () => {
    // "Été" in windows-1252.
    services.subdl({ tmdbId: 238, language: "FR", release: "old", files: { "old.srt": new Uint8Array([0xc9, 0x74, 0xe9]) } });
    services.subdl({ tmdbId: 238, language: "RU", release: "ass", files: { "styled.ass": "[Script Info]\nTitle: Привет" } });
    const fr = (await search(godfather)).subtitles[0];
    const ru = (await search({ ...godfather, languages: ["ru"] })).subtitles[0];

    expect((await download(fr.id)).body).toEqual({ format: "srt", text: "Été" });
    expect((await download(ru.id)).body).toEqual({ format: "ass", text: "[Script Info]\nTitle: Привет" });
  });

  it("answers 404 for a file the service no longer has, or a zip without a subtitle in it", async () => {
    services.subdl({ tmdbId: 238, language: "FR", release: "empty", files: { "readme.txt": "nothing here" } });
    const [offer] = (await search(godfather)).subtitles;

    expect((await download(offer.id)).status).toBe(404);
    expect((await download("subdl:fr:999-999")).status).toBe(404);
    expect((await download("opensubtitles:fr:12345")).status).toBe(404);
  });

  it("refuses an id no search gives", async () => {
    for (const id of ["", "elsewhere:fr:1", "subdl:fr:../../etc", 42]) expect((await call("/v1/subtitles/download", { id })).status).toBe(400);
    expect(services.requests).toHaveLength(0);
  });
});
