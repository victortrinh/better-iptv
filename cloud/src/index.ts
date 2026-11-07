import { charts, fill, SUBREQUESTS } from "./charts";
import { accountOf, isMagicToken, sendMagicLink, startSignIn, useMagicLink, type LinkOutcome, type SendOutcome } from "./accounts";
import { deviceOf, register } from "./devices";
import type { Env } from "./env";
import { fileReads, type FileRead, isFile, isListingId, isProviderKey, MAX_FILE_BATCH, saveFileReads } from "./files";
import { fillGames, games } from "./games";
import { match, MAX_MATCH_BATCH, type MatchQuestion } from "./match";
import { MAX_BATCH, metadata } from "./metadata";
import { providerTable } from "./provider-tables";
import { isUnknownPrefix, MAX_PREFIX_BATCH, saveUnknownPrefixes, type UnknownPrefix } from "./prefixes";
import { isLeague, type League, LEAGUES } from "./schedule";
import { MAX_QUERY, search } from "./search";
import { ServiceUnavailable } from "./subtitle-service";
import { downloadSubtitle, isOfferId, MAX_LANGUAGES, searchSubtitles, type SubtitleQuestion } from "./subtitles";
import { TmdbUnavailable, type TitleKey } from "./tmdb";
import { upcoming } from "./upcoming";
import { season } from "./season";
import { confirmPage, htmlResponse, messagePage, signInPage } from "./page";

/**
 * The cloud's HTTP API (test seam 2). It serves data that is the same for every Viewer, and signs TVs in to Accounts by email (ADR 0015);
 * Provider logins, stream URLs and Provider requests never come here (ADR 0001).
 *
 * - `POST /v1/devices`: registers a device, `201 {"token": "…"}`.
 * - `POST /v1/metadata`, with `Authorization: Bearer <token>` and
 *   `{"language": "fr", "titles": [{"kind": "movie", "tmdbId": 238}]}`: Title metadata in that
 *   language, as a {@link MetadataBatch}.
 * - `POST /v1/match`, with `{"listings": [{"kind": "movie", "name": "Titanic", "year": 1997,
 *   "language": "en", "tmdbId": 597}]}` (cleaned names; year, language and tmdbId may be null):
 *   `{"matches": [{"kind": "movie", "tmdbId": 597}]}` in the order asked. tmdbId is null when TMDB
 *   has no Title by that name, and a match is null when TMDB couldn't answer right now.
 * - `POST /v1/search`, with `{"query": "der pate"}`: what TMDB's search finds (ADR 0009),
 *   `{"titles": [{"kind": "movie", "tmdbId": 238, "popularity": 128.4}], "people": [{"name": "Al Pacino",
 *   "titles": [{"kind": "movie", "tmdbId": 238}]}]}`, a person's Titles being every one they acted in
 *   or directed. `503` when TMDB can't search right now and the query was never searched.
 * - `POST /v1/file-reads/lookup`, with `{"provider": "<SHA-256 of the Provider's host>", "listings":
 *   [123]}`: `{"files": [{"listingId": 123, "file": {…}}]}`, what Viewers of that Provider read of
 *   those movie Listings' files. Listings nobody read are left out.
 * - `POST /v1/file-reads`, with `{"provider": "…", "files": [{"listingId": 123, "file": {…}}]}`:
 *   keeps those reads, `204`.
 * - `POST /v1/unknown-prefixes`, with `{"provider": "…", "prefixes": [{"prefix": "BR", "listingCount": 260,
 *   "example": "BR - Cold Pursuit (2019)"}]}`: keeps the Prefixes a Sync found its Prefix table doesn't
 *   know, for the developer to add, `204`.
 * - `POST /v1/provider-table`, with `{"provider": "…"}`: that Provider's Provider table, as text in the
 *   Base table's format (`text/plain`), empty for a Provider the cloud has never seen (ADR 0014).
 * - `POST /v1/subtitles`, with `{"kind": "movie", "tmdbId": 238, "season": null, "episode": null,
 *   "languages": ["fr"]}`: `{"subtitles": [{"id": "…", "language": "fr", "source": "opensubtitles",
 *   "release": "…", "hearingImpaired": false, "downloads": 1200}], "unavailable": []}`, the best
 *   offers from OpenSubtitles and Subdl in each language, best first.
 * - `POST /v1/upcoming`, with `{"tmdbId": 1399, "language": "fr"}`: the Show's Episodes TMDB lists
 *   as not aired yet, from the next one to the end of its season, `{"episodes": [{"season": 3,
 *   "episode": 5, "airDate": "2026-10-31", "name": "…"}]}` (name null when TMDB has none). `503`
 *   when TMDB can't answer right now and the Show was never asked about.
 * - `POST /v1/season`, with `{"tmdbId": 1399, "season": 2, "language": "fr"}`: that season's
 *   Episodes as TMDB lists them, for the Episode picker, `{"episodes": [{"episode": 1, "name": "…",
 *   "stillPath": "/abc.jpg", "runtimeMinutes": 52}]}` (each null when TMDB has none). `503` when
 *   TMDB can't answer right now and the season was never asked about.
 * - `POST /v1/charts`, with `{"region": "CA", "language": "fr"}`: the Charts for that Region,
 *   `{"top": {"worldwide": false, "movies": [{"kind": "movie", "tmdbId": 238}], "shows": […]},
 *   "trending": {"all": […], "movies": […], "shows": […]}, "genres": [{"kind": "movie", "name":
 *   "Comédie", "tmdbIds": [550, …]}]}`: Netflix's Top 10 in rank order as TMDB Titles, the
 *   Region's or (worldwide) the world's when Netflix doesn't list it, TMDB's trending this week,
 *   and each TMDB genre's most popular Titles (Discover), named in the language. Only what the
 *   cloud keeps is answered: the Cron Trigger reads the sources (#214). A list not read yet is
 *   empty; a Region not read yet gets the worldwide Top 10, and a language the genres named in
 *   English, till the Cron Trigger's next runs.
 * - `POST /v1/games`, with `{"leagues": ["nhl", "premier-league"]}` (the Leagues a device has on,
 *   by the ids in schedule.ts): each League's Games for today and the next seven days,
 *   `{"leagues": [{"league": "nhl", "games": [{"id": "401892633", "league": "nhl", "start":
 *   "2026-11-04T00:00:00.000Z", "state": "upcoming", "home": {"id": "21", "name": "Toronto Maple
 *   Leafs", "shortName": "Maple Leafs", "abbreviation": "TOR", "aliases": ["Toronto Maple Leafs",
 *   "Maple Leafs", "TOR", "Toronto"], "logo": "https://…/tor.png", "color": "00205b",
 *   "alternateColor": "ffffff"}, "away": {…}, "broadcasters":
 *   ["ESPN+"]}]}]}`, by start; state is upcoming, live or over, and broadcasters names the
 *   national Broadcasters the schedule knows (US-market). Never a score or a period. Only what the
 *   cloud keeps is answered: the Cron Trigger reads each League's schedule source (ESPN) while a
 *   device wants it (#252). A League not read yet is absent; one read with no Games has `[]`.
 * - `POST /v1/subtitles/download`, with `{"id": "…"}` from a search: `{"format": "srt", "text": "…"}`,
 *   `404` when the service no longer has it, `503` when it can't give it right now.
 * - `POST /v1/sign-in`: a fresh Sign-in code for this device, `{"code": "K7M2QX", "url":
 *   "https://zugig.app/sign-in?code=K7M2QX", "expiresIn": 600}`, the url for the TV's QR (ADR 0015).
 * - `POST /v1/account`: the Account this device is signed in to, `{"email": "…"}`, or `{"email": null}`.
 *
 * And the sign-in page, for the phone: `GET /sign-in` (the code from the QR filled in), `POST /sign-in`
 * (a code and an email: emails the magic link), `GET /sign-in/link?token=…` (the link: a button) and
 * `POST /sign-in/link` (the button: signs the TV in). See src/accounts.ts.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (request.method === "POST" && pathname === "/v1/devices") {
      return json({ token: await register(env.DB) }, 201);
    }
    if (pathname === "/" && request.method === "GET") return new Response(null, { status: 302, headers: { Location: "/sign-in" } });
    if (pathname === "/sign-in" || pathname === "/sign-in/link") return signInPages(request, env, pathname);
    if (!pathname.startsWith("/v1/")) return problem(404, "No such endpoint.");
    const device = await deviceOf(env.DB, request);
    if (device === null) return problem(401, "A registered device token is required.");
    if (request.method === "POST" && pathname === "/v1/sign-in") {
      const { code, expiresIn } = await startSignIn(env.DB, device);
      return json({ code, url: `${site(request, env)}/sign-in?code=${code}`, expiresIn });
    }
    if (request.method === "POST" && pathname === "/v1/account") return json({ email: await accountOf(env.DB, device) });
    if (request.method === "POST" && pathname === "/v1/metadata") {
      const asked = await metadataRequest(request);
      if (typeof asked === "string") return problem(400, asked);
      return json(await metadata(env, asked.titles, asked.language));
    }
    if (request.method === "POST" && pathname === "/v1/match") {
      const asked = await matchRequest(request);
      if (typeof asked === "string") return problem(400, asked);
      return json({ matches: await match(env, asked) });
    }
    if (request.method === "POST" && pathname === "/v1/search") {
      const query = await searchRequest(request);
      if (query === null) return problem(400, `query must be a name of 1 to ${MAX_QUERY} characters.`);
      try {
        return json(await search(env, query));
      } catch (e) {
        if (e instanceof TmdbUnavailable) return problem(503, "TMDB can't search right now.");
        throw e;
      }
    }
    if (request.method === "POST" && pathname === "/v1/file-reads/lookup") {
      const asked = await fileReadsRequest(request, "listings");
      if (typeof asked === "string") return problem(400, asked);
      return json({ files: await fileReads(env.DB, asked.provider, asked.listings) });
    }
    if (request.method === "POST" && pathname === "/v1/file-reads") {
      const asked = await fileReadsRequest(request, "files");
      if (typeof asked === "string") return problem(400, asked);
      await saveFileReads(env.DB, asked.provider, asked.files);
      return new Response(null, { status: 204 });
    }
    if (request.method === "POST" && pathname === "/v1/unknown-prefixes") {
      const asked = await unknownPrefixesRequest(request);
      if (typeof asked === "string") return problem(400, asked);
      await saveUnknownPrefixes(env.DB, asked.provider, asked.prefixes);
      return new Response(null, { status: 204 });
    }
    if (request.method === "POST" && pathname === "/v1/provider-table") {
      const { provider } = ((await request.json().catch(() => null)) ?? {}) as { provider?: unknown };
      if (!isProviderKey(provider)) return problem(400, "provider must be the SHA-256 of the Provider's host, in lowercase hex.");
      return new Response(await providerTable(env.DB, provider), { headers: { "Content-Type": "text/plain; charset=utf-8" } });
    }
    if (request.method === "POST" && pathname === "/v1/subtitles") {
      const asked = await subtitlesRequest(request);
      if (typeof asked === "string") return problem(400, asked);
      return json(await searchSubtitles(env, asked));
    }
    if (request.method === "POST" && pathname === "/v1/upcoming") {
      const { tmdbId, language } = ((await request.json().catch(() => null)) ?? {}) as { tmdbId?: unknown; language?: unknown };
      if (!Number.isSafeInteger(tmdbId) || (tmdbId as number) <= 0 || typeof language !== "string" || !/^[a-z]{2}$/.test(language)) {
        return problem(400, "tmdbId must be a Show's positive TMDB ID, and language an ISO 639-1 code, such as \"fr\".");
      }
      try {
        return json({ episodes: await upcoming(env, tmdbId as number, language) });
      } catch (e) {
        if (e instanceof TmdbUnavailable) return problem(503, "TMDB can't answer right now.");
        throw e;
      }
    }
    if (request.method === "POST" && pathname === "/v1/season") {
      const asked = ((await request.json().catch(() => null)) ?? {}) as { tmdbId?: unknown; season?: unknown; language?: unknown };
      const { tmdbId, language } = asked;
      if (
        !Number.isSafeInteger(tmdbId) || (tmdbId as number) <= 0 || !Number.isSafeInteger(asked.season) || (asked.season as number) < 0 ||
        typeof language !== "string" || !/^[a-z]{2}$/.test(language)
      ) {
        return problem(400, "tmdbId must be a Show's positive TMDB ID, season a season number (0 for specials), and language an ISO 639-1 code, such as \"fr\".");
      }
      try {
        return json({ episodes: await season(env, tmdbId as number, asked.season as number, language) });
      } catch (e) {
        if (e instanceof TmdbUnavailable) return problem(503, "TMDB can't answer right now.");
        throw e;
      }
    }
    if (request.method === "POST" && pathname === "/v1/charts") {
      const { region, language } = ((await request.json().catch(() => null)) ?? {}) as { region?: unknown; language?: unknown };
      if (typeof region !== "string" || !/^[A-Z]{2}$/.test(region) || typeof language !== "string" || !/^[a-z]{2}$/.test(language)) {
        return problem(400, "region must be an ISO 3166 country code, such as \"CA\", and language an ISO 639-1 code, such as \"fr\".");
      }
      return json(await charts(env, region, language));
    }
    if (request.method === "POST" && pathname === "/v1/games") {
      const { leagues } = ((await request.json().catch(() => null)) ?? {}) as { leagues?: unknown };
      if (!Array.isArray(leagues) || leagues.length > LEAGUES.length || !leagues.every(isLeague)) {
        return problem(400, `leagues must be a list of League ids among ${LEAGUES.join(", ")}.`);
      }
      return json(await games(env, leagues as League[]));
    }
    if (request.method === "POST" && pathname === "/v1/subtitles/download") {
      const { id } = ((await request.json().catch(() => null)) ?? {}) as { id?: unknown };
      if (!isOfferId(id)) return problem(400, "id must be the id of an offer a subtitle search gave.");
      try {
        const file = await downloadSubtitle(env, id);
        return file ? json(file) : problem(404, "The service no longer has that subtitle.");
      } catch (e) {
        if (!(e instanceof ServiceUnavailable)) throw e;
        return problem(503, "The subtitle service can't give that subtitle right now.");
      }
    }
    return problem(404, "No such endpoint.");
  },

  /** The Cron Trigger (`triggers.crons` in wrangler.jsonc), each minute: reads the wanted Leagues' schedules, then the Charts' sources, a few parts a run, out of one budget of requests. */
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    const sent = await fillGames(env);
    await fill(env, SUBREQUESTS - sent);
  },
} satisfies ExportedHandler<Env>;

/** The sign-in page's address: the app's own domain, or wherever this request came. */
const site = (request: Request, env: Env) => (env.SITE_URL ?? new URL(request.url).origin).replace(/\/+$/, "");

/** The sign-in page and its magic links, for the phone. */
async function signInPages(request: Request, env: Env, pathname: string): Promise<Response> {
  const url = new URL(request.url);
  if (pathname === "/sign-in" && request.method === "GET") {
    const code = url.searchParams.get("code") ?? "";
    return htmlResponse(signInPage(/^[2-9A-HJ-NP-Z]{6}$/.test(code) ? code : ""));
  }
  if (pathname === "/sign-in/link" && request.method === "GET") {
    const token = url.searchParams.get("token");
    if (!isMagicToken(token)) return htmlResponse(linkPage({ kind: "used" }), 410);
    return htmlResponse(confirmPage(token));
  }
  if (request.method !== "POST") return htmlResponse(messagePage("Not here", "This page only takes its own form."), 405);
  const form = await request.formData().catch(() => null);
  const field = (name: string) => {
    const value = form?.get(name);
    return typeof value === "string" ? value : "";
  };
  if (pathname === "/sign-in/link") {
    const token = field("token");
    const outcome: LinkOutcome = isMagicToken(token) ? await useMagicLink(env.DB, token) : { kind: "used" };
    return htmlResponse(linkPage(outcome), outcome.kind === "signed-in" ? 200 : 410);
  }
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const outcome = await sendMagicLink(env, site(request, env), field("code"), field("email"), ip);
  const [status, text] = sendWords(outcome);
  if (outcome.kind === "sent") return htmlResponse(messagePage("Check your email", text), status);
  return htmlResponse(signInPage(field("code").trim().toUpperCase().slice(0, 8), { text, error: true }), status);
}

function sendWords(outcome: SendOutcome): [number, string] {
  switch (outcome.kind) {
    case "sent": return [200, `Check your email: we sent a link to ${outcome.email}. Tap it within 15 minutes to sign the TV in.`];
    case "bad-email": return [400, "That doesn't look like an email address."];
    case "wrong-code": return [400, "That code isn't right or has expired. Check the code the TV shows now."];
    case "too-many-tries": return [429, "Too many wrong codes. Wait 10 minutes and try again."];
    case "too-many-sends": return [429, "That code has sent all the links it can. Open Sign in again on the TV for a new code."];
    case "not-sent": return [503, "We couldn't send the email right now. Try again in a moment."];
  }
}

function linkPage(outcome: LinkOutcome): string {
  switch (outcome.kind) {
    case "signed-in": return messagePage("You're signed in", `The TV is signed in as ${outcome.email}. You can close this page.`);
    case "used": return messagePage("This link doesn't work", "This link was already used or isn't one we sent. Open Sign in on the TV for a new code.");
    case "expired": return messagePage("This link doesn't work", "This link has expired: links work for 15 minutes. Open Sign in on the TV for a new code.");
  }
}

/** Reads a metadata request, or says what's wrong with it. */
async function metadataRequest(request: Request): Promise<{ language: string; titles: TitleKey[] } | string> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return "The body must be JSON.";
  }
  const { language, titles } = (body ?? {}) as { language?: unknown; titles?: unknown };
  if (typeof language !== "string" || !/^[a-z]{2}$/.test(language)) return "language must be an ISO 639-1 code, such as \"fr\".";
  if (!Array.isArray(titles) || titles.length > MAX_BATCH) return `titles must be a list of at most ${MAX_BATCH} Titles.`;
  const keys = new Map<string, TitleKey>();
  for (const title of titles as { kind?: unknown; tmdbId?: unknown }[]) {
    if ((title?.kind !== "movie" && title?.kind !== "show") || !Number.isSafeInteger(title.tmdbId) || (title.tmdbId as number) <= 0) {
      return "Each Title must be {\"kind\": \"movie\" or \"show\", \"tmdbId\": a positive integer}.";
    }
    keys.set(`${title.kind}/${title.tmdbId}`, { kind: title.kind, tmdbId: title.tmdbId as number });
  }
  return { language, titles: [...keys.values()] };
}

/** Reads a match request, or says what's wrong with it. */
async function matchRequest(request: Request): Promise<MatchQuestion[] | string> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return "The body must be JSON.";
  }
  const { listings } = (body ?? {}) as { listings?: unknown };
  if (!Array.isArray(listings) || listings.length > MAX_MATCH_BATCH) return `listings must be a list of at most ${MAX_MATCH_BATCH} Listings.`;
  const questions: MatchQuestion[] = [];
  for (const listing of listings as { kind?: unknown; name?: unknown; year?: unknown; language?: unknown; tmdbId?: unknown }[]) {
    const { kind, name, year = null, language = null, tmdbId = null } = listing ?? {};
    if (
      (kind !== "movie" && kind !== "show") ||
      typeof name !== "string" || name.trim() === "" || name.length > 200 ||
      (year !== null && (!Number.isSafeInteger(year) || (year as number) < 1870 || (year as number) > 2100)) ||
      (language !== null && (typeof language !== "string" || !/^[a-z]{2,3}$/.test(language))) ||
      (tmdbId !== null && (!Number.isSafeInteger(tmdbId) || (tmdbId as number) <= 0))
    ) {
      return "Each Listing must be {\"kind\": \"movie\" or \"show\", \"name\": its cleaned name, and optionally \"year\", \"language\" (ISO 639) and \"tmdbId\"}.";
    }
    questions.push({ kind, name: name.trim(), year: year as number | null, language: language as string | null, tmdbId: tmdbId as number | null });
  }
  return questions;
}

/** Reads a search request's query, or null when it has none. */
async function searchRequest(request: Request): Promise<string | null> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return null;
  }
  const { query } = (body ?? {}) as { query?: unknown };
  if (typeof query !== "string" || query.trim() === "" || query.length > MAX_QUERY) return null;
  return query.trim();
}

/** Reads a file-read lookup ([part] `listings`) or save ([part] `files`) request, or says what's wrong with it. */
async function fileReadsRequest(request: Request, part: "listings" | "files"): Promise<{ provider: string; listings: number[]; files: FileRead[] } | string> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return "The body must be JSON.";
  }
  const { provider, listings = [], files = [] } = (body ?? {}) as { provider?: unknown; listings?: unknown; files?: unknown };
  if (!isProviderKey(provider)) return "provider must be the SHA-256 of the Provider's host, in lowercase hex.";
  const asked = part === "listings" ? listings : files;
  if (!Array.isArray(asked) || asked.length > MAX_FILE_BATCH) return `${part} must be a list of at most ${MAX_FILE_BATCH}.`;
  if (part === "listings") {
    if (!asked.every(isListingId)) return "Each Listing must be a positive integer id.";
    return { provider, listings: [...new Set(asked as number[])], files: [] };
  }
  for (const file of asked as { listingId?: unknown; file?: unknown }[]) {
    if (!isListingId(file?.listingId) || !isFile(file.file)) return "Each file must be {\"listingId\": a positive integer, \"file\": an object of at most 8 KB}.";
  }
  return { provider, listings: [], files: asked as FileRead[] };
}

/** Reads an unknown Prefixes report, or says what's wrong with it. */
async function unknownPrefixesRequest(request: Request): Promise<{ provider: string; prefixes: UnknownPrefix[] } | string> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return "The body must be JSON.";
  }
  const { provider, prefixes } = (body ?? {}) as { provider?: unknown; prefixes?: unknown };
  if (!isProviderKey(provider)) return "provider must be the SHA-256 of the Provider's host, in lowercase hex.";
  if (!Array.isArray(prefixes) || prefixes.length > MAX_PREFIX_BATCH) return `prefixes must be a list of at most ${MAX_PREFIX_BATCH}.`;
  if (!prefixes.every(isUnknownPrefix)) return "Each Prefix must be {\"prefix\": 1 to 32 characters, \"listingCount\": a positive integer, \"example\": a Listing's name}.";
  return { provider, prefixes };
}

/** Reads a subtitle search request, or says what's wrong with it. */
async function subtitlesRequest(request: Request): Promise<SubtitleQuestion | string> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return "The body must be JSON.";
  }
  const { kind, tmdbId, season = null, episode = null, languages } = (body ?? {}) as Record<string, unknown>;
  const positive = (n: unknown) => Number.isSafeInteger(n) && (n as number) > 0;
  if ((kind !== "movie" && kind !== "show") || !positive(tmdbId)) return "kind must be \"movie\" or \"show\", and tmdbId a positive integer.";
  // Season 0 holds a Show's specials.
  if (kind === "show" ? !(Number.isSafeInteger(season) && (season as number) >= 0 && positive(episode)) : season !== null || episode !== null) {
    return "An Episode needs its season and episode numbers; a Movie has neither.";
  }
  if (!Array.isArray(languages) || languages.length === 0 || languages.length > MAX_LANGUAGES || !languages.every((l) => typeof l === "string" && /^[a-z]{2}$/.test(l))) {
    return `languages must be 1 to ${MAX_LANGUAGES} ISO 639-1 codes, such as "fr".`;
  }
  return { kind, tmdbId: tmdbId as number, season: season as number | null, episode: episode as number | null, languages: [...new Set(languages as string[])] };
}

const json = (body: unknown, status = 200) => Response.json(body, { status });

const problem = (status: number, error: string) => json({ error }, status);
