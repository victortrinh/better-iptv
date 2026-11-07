import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { MAX_CHANGES, type ProgressChange, type SyncAnswer, TOMBSTONE_KEPT_MS } from "../src/sync";

/** Seam 2: history sync between a household's TVs (#435), two devices over one Account. */
beforeEach(async () => {
  await env.DB.batch(["progress", "device", "account"].map((table) => env.DB.prepare(`DELETE FROM ${table}`)));
});
afterEach(() => vi.useRealTimers());

const PROVIDER = "a".repeat(64);
const OTHER_PROVIDER = "b".repeat(64);

const call = (path: string, body: unknown, token?: string) =>
  worker.fetch(
    new Request(`https://zugig.example${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    }),
    env,
  );

async function registerDevice(): Promise<string> {
  const response = await call("/v1/devices", {});
  return ((await response.json()) as { token: string }).token;
}

/** A device signed in to [email]'s Account, as the magic link leaves it (accounts.test.ts covers the link itself). */
async function signedIn(email = "home@example.com"): Promise<string> {
  const token = await registerDevice();
  await env.DB.prepare("INSERT OR IGNORE INTO account (email, created_at) VALUES (?1, ?2)").bind(email, Date.now()).run();
  const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)))].map((b) => b.toString(16).padStart(2, "0")).join("");
  await env.DB.prepare("UPDATE device SET account_id = (SELECT id FROM account WHERE email = ?1), signed_in_at = ?2 WHERE token_hash = ?3").bind(email, Date.now(), hash).run();
  return token;
}

async function sync(token: string, cursor: number, progress: ProgressChange[] = [], provider: string | null = PROVIDER): Promise<SyncAnswer> {
  const response = await call("/v1/sync", { cursor, provider, progress }, token);
  expect(response.status).toBe(200);
  return (await response.json()) as SyncAnswer;
}

const movie = (tmdbId: number, position: number, at: number, extra: Partial<ProgressChange> = {}): ProgressChange =>
  ({ title: `movie/${tmdbId}`, season: null, episode: null, position, duration: 7_200_000, watched: false, removed: false, at, ...extra });

const episode = (tmdbId: number, season: number, number: number, position: number, at: number): ProgressChange =>
  ({ title: `show/${tmdbId}`, season, episode: number, position, duration: 3_600_000, watched: false, removed: false, at });

const removal = (tmdbId: number, at: number): ProgressChange => movie(tmdbId, 0, at, { duration: null, removed: true });

/** An hour ago: changes are made before they are pushed, and a removal this recent is kept. */
const T = Date.now() - 3600_000;

describe("POST /v1/sync", () => {
  it("answers one device's stop to the Account's other devices, with its position and Episode", async () => {
    const living = await signedIn();
    const bedroom = await signedIn();
    const pushed = await sync(living, 0, [movie(238, 3_733_000, T), episode(1399, 2, 5, 600_000, T + 1)]);
    expect(pushed.cursor).toBeGreaterThan(0);

    const pulled = await sync(bedroom, 0);
    expect(pulled.reset).toBe(false);
    expect(pulled.cursor).toBe(pushed.cursor);
    expect(pulled.progress).toEqual([movie(238, 3_733_000, T), episode(1399, 2, 5, 600_000, T + 1)]);
    // Nothing new since.
    expect((await sync(bedroom, pulled.cursor)).progress).toEqual([]);
  });

  it("keeps the latest change per Movie or Episode, whichever arrives first", async () => {
    const living = await signedIn();
    const bedroom = await signedIn();
    // Both watched the same Movie; the bedroom stopped last, but pushes first.
    await sync(bedroom, 0, [movie(238, 4_000_000, T + 10)]);
    await sync(living, 0, [movie(238, 1_000_000, T)]);
    expect((await sync(living, 0)).progress).toEqual([movie(238, 4_000_000, T + 10)]);
  });

  it("resolves a removal and a watch on two devices by when each was made, either way", async () => {
    const living = await signedIn();
    const bedroom = await signedIn();
    // Removed on one, watched later on the other: the watch wins.
    await sync(living, 0, [removal(238, T)]);
    await sync(bedroom, 0, [movie(238, 2_000_000, T + 5)]);
    // Watched on one, removed later on the other, the removal arriving first: the removal wins.
    await sync(bedroom, 0, [removal(550, T + 20)]);
    await sync(living, 0, [movie(550, 2_000_000, T + 10)]);

    const pulled = (await sync(living, 0)).progress;
    expect(pulled).toContainEqual(movie(238, 2_000_000, T + 5));
    expect(pulled).toContainEqual(removal(550, T + 20));
    expect(pulled).toHaveLength(2);
  });

  it("answers a device offline for a while with everything since its cursor", async () => {
    const living = await signedIn();
    const bedroom = await signedIn();
    const { cursor } = await sync(bedroom, 0, [movie(1, 100_000, T)]);
    await sync(living, 0, [movie(2, 100_000, T + 1)]);
    await sync(living, 0, [movie(3, 100_000, T + 2)]);
    await sync(living, 0, [movie(1, 900_000, T + 3)]);

    const caughtUp = await sync(bedroom, cursor);
    expect(caughtUp.progress.map((change) => [change.title, change.position])).toEqual([["movie/2", 100_000], ["movie/3", 100_000], ["movie/1", 900_000]]);
    expect(caughtUp.reset).toBe(false);
  });

  it("keeps a Listing's progress per Provider, and a TMDB ID's for every Provider", async () => {
    const living = await signedIn();
    const elsewhere = await signedIn();
    await sync(living, 0, [movie(238, 500_000, T), { ...movie(0, 500_000, T), title: "movie/listing/77" }]);

    expect((await sync(elsewhere, 0, [], OTHER_PROVIDER)).progress.map((change) => change.title)).toEqual(["movie/238"]);
    expect((await sync(elsewhere, 0, [], null)).progress.map((change) => change.title)).toEqual(["movie/238"]);
    expect((await sync(elsewhere, 0, [], PROVIDER)).progress.map((change) => change.title)).toEqual(["movie/238", "movie/listing/77"]);
    // The same Listing id on another Provider is another Listing.
    await sync(elsewhere, 0, [{ ...movie(0, 100_000, T + 1), title: "movie/listing/77" }], OTHER_PROVIDER);
    expect((await sync(living, 0)).progress.find((change) => change.title === "movie/listing/77")?.position).toBe(500_000);
  });

  it("never answers another Account's history", async () => {
    const ours = await signedIn("home@example.com");
    const theirs = await signedIn("neighbours@example.com");
    await sync(ours, 0, [movie(238, 500_000, T)]);
    expect((await sync(theirs, 0)).progress).toEqual([]);
  });

  it("keeps a removal 30 days, then sends a device that may have missed it everything again", async () => {
    vi.useFakeTimers({ now: T + 1000, toFake: ["Date"] });
    const living = await signedIn();
    const bedroom = await signedIn();
    const { cursor } = await sync(bedroom, 0, [movie(1, 100_000, T), movie(2, 100_000, T)]);
    await sync(living, 0, [removal(1, T + 1)]);
    expect((await sync(bedroom, cursor)).progress).toEqual([removal(1, T + 1)]);

    vi.setSystemTime(T + TOMBSTONE_KEPT_MS + 1000);
    const late = await sync(bedroom, cursor);
    expect(late.reset).toBe(true);
    expect(late.progress).toEqual([movie(2, 100_000, T)]);
    // A device that just signed in asks from 0, and merges rather than resets.
    expect((await sync(living, 0)).reset).toBe(false);
    expect((await sync(bedroom, late.cursor)).reset).toBe(false);
  });

  it("holds a change from a clock that runs ahead to the cloud's time", async () => {
    vi.useFakeTimers({ now: T, toFake: ["Date"] });
    const living = await signedIn();
    const bedroom = await signedIn();
    await sync(living, 0, [movie(238, 100_000, T + 86_400_000)]);
    vi.setSystemTime(T + 60_000);
    await sync(bedroom, 0, [movie(238, 900_000, T + 60_000)]);
    expect((await sync(living, 0)).progress).toEqual([movie(238, 900_000, T + 60_000)]);
  });

  it("refuses a device that isn't signed in, and one without a token", async () => {
    const anonymous = await registerDevice();
    expect((await call("/v1/sync", { cursor: 0, provider: PROVIDER, progress: [] }, anonymous)).status).toBe(403);
    expect((await call("/v1/sync", { cursor: 0, provider: PROVIDER, progress: [] })).status).toBe(401);
  });

  it("refuses changes that aren't history", async () => {
    const token = await signedIn();
    const refused = async (body: unknown) => expect((await call("/v1/sync", body, token)).status).toBe(400);
    await refused({ cursor: -1, provider: PROVIDER, progress: [] });
    await refused({ cursor: 0, provider: "example.com", progress: [] });
    await refused({ cursor: 0, provider: PROVIDER, progress: [{ ...movie(238, 0, T), title: "channel/12" }] });
    await refused({ cursor: 0, provider: null, progress: [{ ...movie(0, 0, T), title: "movie/listing/77" }] });
    await refused({ cursor: 0, provider: PROVIDER, progress: [{ ...episode(1399, 1, 1, 0, T), season: null }] });
    await refused({ cursor: 0, provider: PROVIDER, progress: [{ ...movie(238, 0, T), season: 1, episode: 1 }] });
    await refused({ cursor: 0, provider: PROVIDER, progress: [{ ...movie(238, 0, T), watched: "yes" }] });
    await refused({ cursor: 0, provider: PROVIDER, progress: Array.from({ length: MAX_CHANGES + 1 }, (_, i) => movie(i + 1, 0, T)) });
    expect((await sync(token, 0)).progress).toEqual([]);
  });

  it("takes a full push in one go", async () => {
    const living = await signedIn();
    const bedroom = await signedIn();
    await sync(living, 0, Array.from({ length: MAX_CHANGES }, (_, i) => movie(i + 1, 100_000, T + i)));
    expect((await sync(bedroom, 0)).progress).toHaveLength(MAX_CHANGES);
  });
});
