import liveCategories from "../demo-provider/live_categories.json";
import liveListings from "../demo-provider/live_streams.json";
import showCategories from "../demo-provider/series_categories.json";
import bbsTheDocumentary from "../demo-provider/series_info_2001.json";
import caminandes from "../demo-provider/series_info_2002.json";
import lesVampires from "../demo-provider/series_info_2003.json";
import showListings from "../demo-provider/series_streams.json";
import movieCategories from "../demo-provider/vod_categories.json";
import movieListings from "../demo-provider/vod_streams.json";

/**
 * The Demo Provider (CONTEXT.md, ADR 0021): the Cloud answering as a small Provider of its own, in the Xtream
 * API the Core reads, so store reviewers, store screenshots and each platform's tests have a Provider to sign in
 * to. Its address is the Cloud's own (the Core asks `/player_api.php` at an address's root).
 *
 * Its catalog is the files in `demo-provider/`, named as a survey of a Provider names them, which the Core's
 * DemoProviderTest Syncs: only films, Shows and Channels that are free to show (their sources and licences are
 * in the README). Listings are named with Prefixes and Categories the Base table understands and carry Title
 * numbers, so Match groups them as it does a household's Provider's.
 *
 * Nothing plays yet: no stream address is served (#575), and a movie has no details.
 */

/**
 * The Demo Provider's one login, given to store reviewers. It guards nothing (the catalog is free to show and
 * public in this repository), so it is no secret; it is there because the app signs in to a Provider.
 */
export const DEMO_PROVIDER_LOGIN = { username: "demo", password: "zugig-demo" };

/** The path an Xtream Provider's API answers at. */
export const DEMO_PROVIDER_PATH = "/player_api.php";

const SHOW_DETAILS: Record<string, unknown> = { 2001: bbsTheDocumentary, 2002: caminandes, 2003: lesVampires };

/** What a Provider answers for a Show it has no Episodes for. */
const NO_EPISODES = { seasons: [], info: [], episodes: [] };

/** Answers a `player_api.php` request as an Xtream Provider does. */
export function demoProvider(request: Request): Response {
  if (request.method !== "GET") return answer({ error: "The Demo Provider answers GET only." }, 405);
  const query = new URL(request.url).searchParams;
  // A wrong login is refused the way Xtream panels refuse one, whatever was asked: HTTP 200 and `auth: 0`.
  if (query.get("username") !== DEMO_PROVIDER_LOGIN.username || query.get("password") !== DEMO_PROVIDER_LOGIN.password) {
    return answer({ user_info: { auth: 0 } });
  }
  const category = query.get("category_id");
  switch (query.get("action")) {
    case null:
      return answer(account(request));
    case "get_vod_categories":
      return answer(movieCategories);
    case "get_vod_streams":
      return answer(filedUnder(movieListings, category));
    case "get_vod_info":
      return answer({ info: [], movie_data: {} });
    case "get_series_categories":
      return answer(showCategories);
    case "get_series":
      return answer(filedUnder(showListings, category));
    case "get_series_info":
      return answer(SHOW_DETAILS[query.get("series_id") ?? ""] ?? NO_EPISODES);
    case "get_live_categories":
      return answer(liveCategories);
    case "get_live_streams":
      return answer(filedUnder(liveListings, category));
    case "get_short_epg":
    case "get_simple_data_table":
      return answer({ epg_listings: [] });
    default:
      return answer([]);
  }
}

/** The account answer: an active subscription that never expires, one stream at a time like most households'. */
function account(request: Request) {
  const { hostname, protocol } = new URL(request.url);
  const now = new Date();
  return {
    user_info: {
      username: DEMO_PROVIDER_LOGIN.username,
      message: "",
      auth: 1,
      status: "Active",
      exp_date: null,
      is_trial: "0",
      active_cons: "0",
      created_at: "1763164800",
      max_connections: "1",
      allowed_output_formats: ["m3u8", "ts"],
    },
    server_info: {
      url: hostname,
      port: "80",
      https_port: "443",
      server_protocol: protocol.replace(":", ""),
      timezone: "UTC",
      timestamp_now: Math.floor(now.getTime() / 1000),
      time_now: now.toISOString().slice(0, 19).replace("T", " "),
    },
  };
}

/** The Listings filed under [category], or all of them without one. */
function filedUnder<T extends { category_id: string }>(listings: T[], category: string | null): T[] {
  return category === null ? listings : listings.filter((listing) => listing.category_id === category);
}

/** A JSON answer any origin may read, as a Provider's is, so an app running in a browser engine (Samsung's) can Sync it. */
function answer(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" } });
}
