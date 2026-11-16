import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
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
type ShowDetails = { seasons: unknown[]; episodes: Record<string, { id: string; episode_num: number; season: number; title: string }[]> };

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

    expect(categories.map((category) => category.category_name)).toEqual(["EN - ANIMATION", "EN - KIDS", "EN - SCIENCE FICTION", "EN - CLASSICS", "EN - MOVIES 4K"]);
    expect(new Set(categories.map((category) => category.category_id)).size).toBe(categories.length);
  });

  it("lists its movie Listings, each with a Prefix, a year, a Title number and a Category it has", async () => {
    const categories = await answer<Category[]>({ action: "get_vod_categories" });
    const movies = await answer<Listing[]>({ action: "get_vod_streams" });

    expect(movies).toHaveLength(15);
    expect(new Set(movies.map((movie) => movie.stream_id)).size).toBe(movies.length);
    for (const movie of movies) {
      expect(movie.name).toMatch(/^(EN|4K-EN) - .+ \((19|20)\d\d\)$/);
      expect(Number(movie.tmdb)).toBeGreaterThan(0);
      expect(movie.stream_type).toBe("movie");
      expect(movie.container_extension).toBe("mp4");
      expect(categories.map((category) => category.category_id)).toContain(movie.category_id);
    }
    // About a dozen films.
    expect(new Set(movies.map((movie) => movie.tmdb)).size).toBe(12);
  });

  it("lists Big Buck Bunny twice under one Title number, in HD and in 4K, so Match makes one Title with two Versions", async () => {
    const movies = await answer<Listing[]>({ action: "get_vod_streams" });

    expect(movies.filter((movie) => movie.tmdb === "10378").map((movie) => movie.name)).toEqual(["EN - Big Buck Bunny (2008)", "4K-EN - Big Buck Bunny (2008)"]);
  });

  it("lists one Category's movie Listings when asked for it, as a Head start asks", async () => {
    const classics = await answer<Listing[]>({ action: "get_vod_streams", category_id: "4" });

    expect(classics.map((movie) => movie.name)).toEqual([
      "EN - A Trip to the Moon (1902)",
      "EN - The Great Train Robbery (1903)",
      "EN - The Cabinet of Dr. Caligari (1920)",
      "EN - Battleship Potemkin (1925)",
      "EN - Man with a Movie Camera (1929)",
    ]);
    expect(await answer<Listing[]>({ action: "get_vod_streams", category_id: "99" })).toEqual([]);
  });

  it("has no details for a movie yet", async () => {
    expect(await answer({ action: "get_vod_info", vod_id: "1001" })).toEqual({ info: [], movie_data: {} });
  });
});

describe("the Demo Provider's Shows", () => {
  it("lists its show Categories", async () => {
    const categories = await answer<Category[]>({ action: "get_series_categories" });

    expect(categories.map((category) => category.category_name)).toEqual(["EN - DOCUMENTARY", "EN - KIDS", "FR - SÉRIES CLASSIQUES"]);
  });

  it("lists its show Listings, and one Category's when asked", async () => {
    const shows = await answer<Listing[]>({ action: "get_series" });

    expect(shows.map((show) => [show.series_id, show.name, show.tmdb])).toEqual([
      [2001, "EN - BBS: The Documentary (2005)", "84836"],
      // The Title numbers list these two as films, not Shows, so they carry none.
      [2002, "EN - Caminandes (2013)", ""],
      [2003, "FR - Les Vampires (1915)", ""],
    ]);
    expect((await answer<Listing[]>({ action: "get_series", category_id: "3" })).map((show) => show.series_id)).toEqual([2003]);
  });

  it("gives each Show its Episodes, numbered in one season, with ids no other Episode has", async () => {
    const ids = new Set<string>();
    for (const [show, count] of [[2001, 8], [2002, 3], [2003, 10]]) {
      const details = await answer<ShowDetails>({ action: "get_series_info", series_id: String(show) });
      expect(Object.keys(details.episodes)).toEqual(["1"]);
      const episodes = details.episodes["1"];
      episodes.forEach((episode) => expect(episode.season).toBe(1));
      expect(episodes.map((episode) => episode.episode_num)).toEqual(Array.from({ length: count }, (_, i) => i + 1));
      episodes.forEach((episode) => ids.add(episode.id));
    }
    expect(ids.size).toBe(21);
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
      [3002, "US: NASA ISS LIVE HD", "live"],
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

  it("serves no stream yet", async () => {
    const { username, password } = DEMO_PROVIDER_LOGIN;
    for (const path of [`/movie/${username}/${password}/1001.mp4`, `/series/${username}/${password}/10001.mp4`, `/live/${username}/${password}/3001.ts`]) {
      expect((await worker.fetch(new Request(`https://cloud.example${path}`), env)).status).toBe(404);
    }
  });
});
