import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import addresses from "../demo-provider/stream_addresses.json";
import { DEMO_PROVIDER_LOGIN } from "../src/demo-provider";
import worker from "../src/index";

/**
 * Seam 2: the Demo Provider (CONTEXT.md), the Cloud answering as a Provider in the Xtream API the Core reads.
 * Requests are made as the Core's Xtream client makes them: `GET /player_api.php` with the login in the query.
 */
const ask = (parameters: Record<string, string> = {}, login: { username?: string; password?: string } = DEMO_PROVIDER_LOGIN) =>
  worker.fetch(new Request(`https://cloud.example/player_api.php?${new URLSearchParams({ ...login, ...parameters })}`), env);

const answer = async <T>(parameters: Record<string, string> = {}) => {
  const response = await ask(parameters);
  expect(response.status).toBe(200);
  expect(response.headers.get("Content-Type")).toContain("application/json");
  return (await response.json()) as T;
};

type Category = { category_id: string; category_name: string };
type Listing = { name: string; category_id: string; tmdb?: string; stream_id?: number; series_id?: number; stream_type?: string; container_extension?: string };
type ShowDetails = { seasons: unknown[]; episodes: Record<string, { id: string; episode_num: number; season: number; title: string; container_extension: string; info: { duration_secs?: number } }[]> };
type MovieDetails = { info: Record<string, unknown>; movie_data: Record<string, unknown> };

const { username, password } = DEMO_PROVIDER_LOGIN;

/** Asks for a stream as the Core's Xtream client does: `GET /<kind>/<username>/<password>/<id>.<container>`, not following where it leads. */
const stream = (path: string, method = "GET") => worker.fetch(new Request(`https://cloud.example${path}`, { method, redirect: "manual" }), env);

const sha256 = async (text: string) =>
  [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)))].map((b) => b.toString(16).padStart(2, "0")).join("");

const call = (path: string, body?: unknown, token?: string, host = "cloud.example") =>
  worker.fetch(
    new Request(`https://${host}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    env,
  );

const registerDevice = async () => ((await (await call("/v1/devices")).json()) as { token: string }).token;

describe("the Demo Provider's login", () => {
  it("answers its login with an active account that never expires, without a device token", async () => {
    const account = await answer<{ user_info: Record<string, unknown>; server_info: Record<string, unknown> }>();

    expect(account.user_info).toMatchObject({ username: DEMO_PROVIDER_LOGIN.username, auth: 1, status: "Active", exp_date: null, max_connections: "1" });
    expect(JSON.stringify(account)).not.toContain(DEMO_PROVIDER_LOGIN.password);
    expect(account.server_info).toMatchObject({ timezone: "UTC" });
  });

  it("refuses a wrong password, a wrong username and no login as a Provider does: auth 0 and nothing else", async () => {
    for (const login of [{ ...DEMO_PROVIDER_LOGIN, password: "wrong" }, { ...DEMO_PROVIDER_LOGIN, username: "someone" }, {}]) {
      const response = await ask({}, login);
      expect(response.status).toBe(200);
      expect(await response.json()).toEqual({ user_info: { auth: 0 } });
    }
  });

  it("gives a wrong login no part of the catalog", async () => {
    for (const action of ["get_vod_categories", "get_vod_streams", "get_series_categories", "get_series", "get_series_info", "get_live_categories", "get_live_streams"]) {
      const response = await ask({ action, series_id: "2001" }, { ...DEMO_PROVIDER_LOGIN, password: "wrong" });
      expect(await response.json()).toEqual({ user_info: { auth: 0 } });
    }
  });

  it("answers only GET, and any origin, so an app in a browser engine can read it", async () => {
    const posted = await worker.fetch(new Request(`https://cloud.example/player_api.php?${new URLSearchParams(DEMO_PROVIDER_LOGIN)}`, { method: "POST" }), env);
    expect(posted.status).toBe(405);
    expect((await ask()).headers.get("Access-Control-Allow-Origin")).toBe("*");
  });
});

describe("the Demo Provider's movies", () => {
  it("lists its movie Categories", async () => {
    const categories = await answer<Category[]>({ action: "get_vod_categories" });

    expect(categories.map((category) => category.category_name)).toEqual(["EN - ANIMATION", "EN - KIDS", "EN - SCIENCE FICTION"]);
    expect(new Set(categories.map((category) => category.category_id)).size).toBe(categories.length);
  });

  it("lists its movie Listings, each with a Prefix, a year, a Title number and a Category it has", async () => {
    const categories = await answer<Category[]>({ action: "get_vod_categories" });
    const movies = await answer<Listing[]>({ action: "get_vod_streams" });

    expect(movies).toHaveLength(7);
    expect(new Set(movies.map((movie) => movie.stream_id)).size).toBe(movies.length);
    for (const movie of movies) {
      expect(movie.name).toMatch(/^EN - .+ \(20\d\d\)$/);
      expect(Number(movie.tmdb)).toBeGreaterThan(0);
      expect(movie.stream_type).toBe("movie");
      expect(categories.map((category) => category.category_id)).toContain(movie.category_id);
    }
    // One Listing a film: no host that may be linked, and that the app can read, has a second copy of one.
    expect(new Set(movies.map((movie) => movie.tmdb)).size).toBe(7);
  });

  it("lists one Category's movie Listings when asked for it, as a Head start asks", async () => {
    const scienceFiction = await answer<Listing[]>({ action: "get_vod_streams", category_id: "3" });

    expect(scienceFiction.map((movie) => movie.name)).toEqual(["EN - Tears of Steel (2012)"]);
    expect(await answer<Listing[]>({ action: "get_vod_streams", category_id: "99" })).toEqual([]);
  });

  it("gives a movie its details: what the film is, who made it, when, and how long it runs", async () => {
    const details = await answer<MovieDetails>({ action: "get_vod_info", vod_id: "1003" });

    expect(details.info).toMatchObject({ genre: "Animation / Fantasy", director: "Colin Levy", releasedate: "2010-09-27", duration_secs: 888, duration: "00:14:48" });
    expect(details.info.plot).toMatch(/dragon/);
    expect(details.movie_data).toMatchObject({ stream_id: 1003, name: "EN - Sintel (2010)", container_extension: "mp4", category_id: "1" });
  });

  it("gives every movie Listing details", async () => {
    const movies = await answer<Listing[]>({ action: "get_vod_streams" });
    for (const movie of movies) {
      const details = await answer<MovieDetails>({ action: "get_vod_info", vod_id: String(movie.stream_id) });
      expect(details.info.plot, movie.name).toBeTruthy();
      expect(details.info.duration_secs, movie.name).toBeGreaterThan(0);
      expect(details.movie_data.stream_id).toBe(movie.stream_id);
    }
  });

  it("answers a movie it doesn't have as a Provider does: no details", async () => {
    expect(await answer({ action: "get_vod_info", vod_id: "9999" })).toEqual({ info: [], movie_data: {} });
    expect(await answer({ action: "get_vod_info" })).toEqual({ info: [], movie_data: {} });
  });
});

describe("the Demo Provider's Shows", () => {
  it("lists its show Categories", async () => {
    const categories = await answer<Category[]>({ action: "get_series_categories" });

    expect(categories.map((category) => category.category_name)).toEqual(["EN - DOCUMENTARY", "EN - KIDS"]);
  });

  it("lists its show Listings, and one Category's when asked", async () => {
    const shows = await answer<Listing[]>({ action: "get_series" });

    expect(shows.map((show) => [show.series_id, show.name, show.tmdb])).toEqual([
      [2001, "EN - BBS: The Documentary (2005)", "84836"],
      // The Title numbers list this one as three films, not a Show, so it carries none.
      [2002, "EN - Caminandes (2013)", ""],
    ]);
    expect((await answer<Listing[]>({ action: "get_series", category_id: "2" })).map((show) => show.series_id)).toEqual([2002]);
  });

  it("gives each Show its Episodes, numbered in one season, with ids no other Episode has", async () => {
    const ids = new Set<string>();
    for (const [show, count] of [[2001, 8], [2002, 3]]) {
      const details = await answer<ShowDetails>({ action: "get_series_info", series_id: String(show) });
      expect(Object.keys(details.episodes)).toEqual(["1"]);
      const episodes = details.episodes["1"];
      episodes.forEach((episode) => expect(episode.season).toBe(1));
      expect(episodes.map((episode) => episode.episode_num)).toEqual(Array.from({ length: count }, (_, i) => i + 1));
      episodes.forEach((episode) => expect(episode.info.duration_secs, episode.title).toBeGreaterThan(0));
      episodes.forEach((episode) => ids.add(episode.id));
    }
    expect(ids.size).toBe(11);
  });

  it("answers a Show it doesn't have as a Provider does: no seasons, no Episodes", async () => {
    expect(await answer({ action: "get_series_info", series_id: "9999" })).toEqual({ seasons: [], info: [], episodes: [] });
    expect(await answer({ action: "get_series_info" })).toEqual({ seasons: [], info: [], episodes: [] });
  });
});

describe("the Demo Provider's Channels", () => {
  it("lists its live Categories under a country code", async () => {
    expect((await answer<Category[]>({ action: "get_live_categories" })).map((category) => category.category_name)).toEqual(["US| DOCUMENTARY"]);
  });

  it("lists its live Listings, and one Category's when asked", async () => {
    const channels = await answer<Listing[]>({ action: "get_live_streams" });

    expect(channels.map((channel) => [channel.stream_id, channel.name, channel.stream_type])).toEqual([
      [3001, "US: NASA TV HD", "live"],
    ]);
    expect(await answer<Listing[]>({ action: "get_live_streams", category_id: "1" })).toEqual(channels);
    expect(await answer<Listing[]>({ action: "get_live_streams", category_id: "2" })).toEqual([]);
  });

  it("has no guide", async () => {
    expect(await answer({ action: "get_short_epg", stream_id: "3001" })).toEqual({ epg_listings: [] });
    expect(await answer({ action: "get_simple_data_table", stream_id: "3001" })).toEqual({ epg_listings: [] });
  });
});

describe("what the Demo Provider doesn't serve", () => {
  it("answers an action it doesn't know with an empty list", async () => {
    expect(await answer({ action: "get_something_else" })).toEqual([]);
  });
});

describe("the Demo Provider's stream addresses", () => {
  it("sends a movie to where its file is hosted, and serves no video itself", async () => {
    const response = await stream(`/movie/${username}/${password}/1001.mp4`);

    expect(response.status).toBe(302);
    expect(response.headers.get("Location")).toBe("https://archive.org/download/BigBuckBunnyFULLHD60FPS/Big%20Buck%20Bunny%20-%20FULL%20HD%2060FPS.mp4");
    expect(await response.text()).toBe("");
  });

  it("sends an Episode to its file", async () => {
    const response = await stream(`/series/${username}/${password}/10001.mp4`);

    expect(response.status).toBe(302);
    expect(response.headers.get("Location")).toBe("https://archive.org/download/BBS.The.Documentary/BBS.The.Documentary.ep1_512kb.mp4");
  });

  it("sends a Channel asked for as HLS to its broadcaster's playlist, and has no MPEG-TS of it", async () => {
    const hls = await stream(`/live/${username}/${password}/3001.m3u8`);

    expect(hls.status).toBe(302);
    expect(hls.headers.get("Location")).toBe("https://ee119c17e6ed643893ff6ae377fe0693.7wzuvg.channel-assembly.mediatailor.us-east-1.amazonaws.com/v1/channel/NASAPrime/NASAPlus.m3u8");
    expect((await stream(`/live/${username}/${password}/3001.ts`)).status).toBe(404);
  });

  it("answers a HEAD as it answers a GET, and nothing else", async () => {
    expect((await stream(`/movie/${username}/${password}/1001.mp4`, "HEAD")).status).toBe(302);
    expect((await stream(`/movie/${username}/${password}/1001.mp4`, "POST")).status).toBe(405);
  });

  it("refuses a wrong login, and has nothing for a stream it doesn't list or one asked for as another kind", async () => {
    expect((await stream(`/movie/${username}/wrong/1001.mp4`)).status).toBe(403);
    expect((await stream(`/movie/someone/${password}/1001.mp4`)).status).toBe(403);
    for (const path of [`/movie/${username}/${password}/9999.mp4`, `/movie/${username}/${password}/10001.mp4`, `/series/${username}/${password}/1001.mp4`, `/live/${username}/${password}/1001.m3u8`, `/movie/${username}/${password}/abc.mp4`, `/movie/${username}/${password}`]) {
      const response = await stream(path);
      expect(response.status, path).toBe(404);
      expect(response.headers.has("Location")).toBe(false);
    }
  });

  it("has an address for every movie Listing, every Episode and every Channel, in the container the Listing names", async () => {
    const listed: [string, number, string][] = [];
    for (const movie of await answer<Listing[]>({ action: "get_vod_streams" })) listed.push(["movie", movie.stream_id!, movie.container_extension!]);
    for (const show of await answer<Listing[]>({ action: "get_series" })) {
      const details = await answer<ShowDetails>({ action: "get_series_info", series_id: String(show.series_id) });
      for (const episode of Object.values(details.episodes).flat()) listed.push(["series", Number(episode.id), episode.container_extension]);
    }
    for (const channel of await answer<Listing[]>({ action: "get_live_streams" })) listed.push(["live", channel.stream_id!, "m3u8"]);

    expect(listed).toHaveLength(19);
    for (const [kind, id, container] of listed) {
      const response = await stream(`/${kind}/${username}/${password}/${id}.${container}`);
      expect(response.status, `${kind} ${id}`).toBe(302);
      expect(new URL(response.headers.get("Location")!).pathname.endsWith(`.${container}`), `${kind} ${id}`).toBe(true);
    }
    // And no address is left over for a Listing that is gone.
    expect(addresses.map((address) => `${address.kind} ${address.id}`).sort()).toEqual(listed.map(([kind, id]) => `${kind} ${id}`).sort());
  });

  it("links only hosts whose files may be linked, each with the page that states its licence", () => {
    const hosts = new Set(["archive.org", new URL("https://ee119c17e6ed643893ff6ae377fe0693.7wzuvg.channel-assembly.mediatailor.us-east-1.amazonaws.com/v1/channel/NASAPrime/NASAPlus.m3u8").hostname]);
    for (const address of addresses) {
      const url = new URL(address.url);
      expect(url.protocol, address.title).toBe("https:");
      expect(hosts.has(url.hostname), address.title).toBe(true);
      expect(address.page, address.title).toMatch(/^https:\/\/(archive\.org\/details\/|plus\.nasa\.gov\/)/);
      expect(address.licence, address.title).toMatch(/^(CC BY(-SA)? \d\.\d( US)?|US government work, public domain)$/);
      expect(address.licenceUrl, address.title).toMatch(/^https:\/\//);
      expect(address.credit, address.title).toBeTruthy();
    }
  });
});

describe("what a device on the Demo Provider reports", () => {
  const file = { audio: [{ language: "eng", codec: "audio/opus", channels: 2 }], subtitles: [], width: 1920, height: 1080, hdr: null, bitrate: 2_000_000 };
  const kept = (table: string, provider: string) => env.DB.prepare(`SELECT count(*) AS n FROM ${table} WHERE provider = ?`).bind(provider).first().then((row) => row?.n);

  it("isn't kept: its Header reads aren't shared and teach no Learned line", async () => {
    // The Demo Provider's address is the Cloud's own, so its Provider key is the hash of the host the device reached the Cloud at.
    const demo = await sha256("cloud.example");
    const token = await registerDevice();
    // Enough agreeing reads to learn a line on a household's Provider.
    const files = Array.from({ length: 12 }, (_, i) => ({ listingId: 1001 + i, file, prefix: "EN", hints: [] }));

    expect((await call("/v1/file-reads", { provider: demo, files }, token)).status).toBe(204);

    const another = await registerDevice();
    expect(await (await call("/v1/file-reads/lookup", { provider: demo, listings: [1001, 1002] }, another)).json()).toEqual({ files: [] });
    expect(await kept("file_read", demo)).toBe(0);
    expect(await kept("learned_line", demo)).toBe(0);
    expect(await (await call("/v1/provider-table", { provider: demo }, another)).text()).not.toContain("EN |");
  });

  it("isn't kept for the curator either: no unknown Prefix, no candidate Tier tag", async () => {
    const demo = await sha256("cloud.example");
    const token = await registerDevice();
    const report = { provider: demo, prefixes: [{ prefix: "ZZ", listingCount: 3, example: "ZZ - A Film (2020)" }], tierTags: [{ word: "PLUS", listingCount: 2, example: "PLUS: NASA TV" }] };

    expect((await call("/v1/unknown-prefixes", report, token)).status).toBe(204);

    expect(await kept("unknown_prefix", demo)).toBe(0);
    expect(await kept("tier_tag_candidate", demo)).toBe(0);
  });

  it("is told apart by the host it reached the Cloud at, whatever the letter case", async () => {
    const demo = await sha256("zugig.example");
    const token = await registerDevice();

    expect((await call("/v1/file-reads", { provider: demo, files: [{ listingId: 1001, file, prefix: "EN", hints: [] }] }, token, "Zugig.Example")).status).toBe(204);

    expect(await kept("file_read", demo)).toBe(0);
  });

  it("leaves a household's Provider alone: its reads are kept and shared as before", async () => {
    const household = "b".repeat(64);
    const token = await registerDevice();

    expect((await call("/v1/file-reads", { provider: household, files: [{ listingId: 12, file, prefix: "EN", hints: [] }] }, token)).status).toBe(204);

    expect(await (await call("/v1/file-reads/lookup", { provider: household, listings: [12] }, await registerDevice())).json()).toEqual({ files: [{ listingId: 12, file }] });
  });
});
