import {
  accountOf, devicesOf, isDeviceId, isMagicToken, modelName, requestDeletion, sendMagicLink, signOut, startSignIn, useDeleteLink, useMagicLink, useResetPinLink,
  type DeleteOutcome, type LinkOutcome, type ResetPinOutcome, type SendOutcome,
} from "./accounts";
import { deviceOf, register } from "./devices";
import type { Env } from "./env";
import { fileReads, type FileRead, isFile, isListingId, isProviderKey, isReportedHints, isReportedPrefix, MAX_FILE_BATCH, saveFileReads } from "./files";
import { fillGames, games, SUBREQUESTS } from "./games";
import { providerTable } from "./provider-tables";
import { isTestHostKey } from "./test-hosts";
import { isCandidateTierTag, isUnknownPrefix, MAX_PREFIX_BATCH, MAX_TIER_TAG_BATCH, saveCandidateTierTags, saveUnknownPrefixes, type CandidateTierTag, type UnknownPrefix } from "./prefixes";
import { isLeague, type League, LEAGUES } from "./schedule";
import { ServiceUnavailable } from "./subtitle-service";
import { downloadSubtitle, isOfferId, MAX_LANGUAGES, searchSubtitles, type SubtitleQuestion } from "./subtitles";
import { MAX_FACTS_BATCH, titleFacts } from "./title-facts";
import { fillTrending, trending } from "./trending";
import type { TitleKey } from "./title";
import { confirmDeletePage, confirmPage, confirmResetPinPage, deletePage, htmlResponse, messagePage, resetPinPage, signInPage } from "./page";
import { accountIdOf, readSyncRequest, sync } from "./sync";

/**
 * The cloud's HTTP API (test seam 2). It serves data that is the same for every Viewer, and signs TVs in to Accounts by email (ADR 0015);
 * Provider logins, stream URLs and Provider requests never come here (ADR 0001).
 *
 * - `POST /v1/devices`: registers a device, `201 {"token": "…"}`.
 * - `POST /v1/file-reads/lookup`, with `{"provider": "<SHA-256 of the Provider's host>", "listings":
 *   [123]}`: `{"files": [{"listingId": 123, "file": {…}}]}`, what Viewers of that Provider read of
 *   those movie Listings' files. Listings nobody read are left out.
 * - `POST /v1/file-reads`, with `{"provider": "…", "files": [{"listingId": 123, "file": {…}, "prefix": "SC",
 *   "hints": ["MULTI-SUBS"]}]}`: keeps those reads, `204`, and learns from their Prefixes (ADR 0014).
 * - `POST /v1/unknown-prefixes`, with `{"provider": "…", "prefixes": [{"prefix": "BR", "listingCount": 260,
 *   "example": "BR - Cold Pursuit (2019)"}], "tierTags": [{"word": "PLUS", "listingCount": 40, "example":
 *   "PLUS: TNT SPORT 1 HD"}]}`: keeps the Prefixes a Sync found its Prefix table doesn't know, and its
 *   candidate Tier tags (optional, #439), for the developer to add, `204`.
 *   Both reports are taken but not kept from a host only tests serve (src/test-hosts.ts, #545).
 * - `POST /v1/provider-table`, with `{"provider": "…"}`: that Provider's Provider table, as text in the
 *   Base table's format (`text/plain`): its hand lines, then its learned lines under `[learned prefixes]`,
 *   empty for a Provider the cloud has never seen (ADR 0014).
 * - `POST /v1/subtitles`, with `{"kind": "movie", "tmdbId": 238, "season": null, "episode": null,
 *   "languages": ["fr"]}`: `{"subtitles": [{"id": "…", "language": "fr", "source": "opensubtitles",
 *   "release": "…", "hearingImpaired": false, "downloads": 1200}], "unavailable": []}`, the best
 *   offers from OpenSubtitles and Subdl in each language, best first, searched by the Title number
 *   and the IMDb ID the Title facts hold.
 * - `POST /v1/title-facts`, with `{"titles": [{"kind": "movie", "tmdbId": 238}]}`, at most 300 Title
 *   numbers: what Wikidata knows of each (#476), `{"titles": [{"kind": "movie", "tmdbId": 238,
 *   "originalLanguage": "en", "imdbId": "tt0068646", "enwiki": "The Godfather", "frwiki": "Le Parrain"}],
 *   "notFound": [{"kind": "movie", "tmdbId": 999}], "unavailable": []}`: the original language as a
 *   primary subtag, the IMDb ID and the English and French Wikipedia article names (each null when the
 *   item lacks it), the Titles Wikidata has no item for, and those it couldn't answer for right now,
 *   which the device asks again. Cached for good, "no item" included.
 * - `POST /v1/trending`, with `{"language": "fr"}` (the App language): Trending's feed (#477),
 *   `{"wikis": [{"wiki": "en", "days": [{"day": "2026-11-06", "articles": [{"name": "Jailer 3", "views":
 *   812345}]}]}, {"wiki": "fr", "days": […]}]}`: English Wikipedia's and the language's most viewed
 *   articles of each of the last seven days the cloud keeps, newest first, named as Wikipedia shows
 *   them, which the device sums and matches to its Titles' article names. Only what the cloud keeps is
 *   answered: the Cron Trigger reads Wikipedia's pageviews once a day per wiki, for English and each
 *   language a device asked in. A day not read yet is absent.
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
 *   `POST /v1/sign-in` may carry `{"model": "Fire TV Stick 4K Max"}`, the only time the model is sent.
 * - `POST /v1/account/devices`: the devices signed in to this one's Account, `{"devices": [{"id":
 *   "3f9a…", "model": "Fire TV Stick 4K Max", "signedInAt": "2026-11-03T20:00:00.000Z", "thisDevice":
 *   true}]}`, by sign-in; `[]` when it is signed in to none.
 * - `POST /v1/account/sign-out`, with `{}` for this device or `{"device": "3f9a…"}` for another on
 *   its Account: clears that device's link, `204`; `404` when no such device is on the Account.
 * - `POST /v1/sync`, with `{"cursor": 0, "provider": "<SHA-256 of the Provider's host>", "progress": [{"title":
 *   "show/1399", "season": 1, "episode": 2, "position": 600000, "duration": 3600000, "watched": false, "removed":
 *   false, "at": 1762500000000}]}`: keeps the device's history changes, latest winning, and answers the Account's
 *   since the cursor, `{"cursor": 42, "reset": false, "progress": […], "entries": […]}` (#435). Since #440 each change
 *   names its `"profile"` (the id its device made for it), the request says which Profile the device is on
 *   (`"current"`), and `"entries"` carry the Profiles, Favourites, "Not this game" marks, per-Profile settings and
 *   player choices, Region and the Household PIN's hash (`{"kind": "favourite", "profile": "…", "key": "…", "value":
 *   "1", "at": …}`, see src/sync.ts). `403` when the device isn't signed in.
 *
 * The metadata, match, search, upcoming, season and charts endpoints that TMDB answered are gone, as is every
 * table that kept its data (#481, ADR 0014): they answer 404 like any path the cloud doesn't serve.
 *
 * Every `/v1/` answer to a registered device carries `Zugig-Account: signed-in` or `none`, so a
 * device signed out elsewhere goes anonymous at its next request (#434).
 *
 * And the sign-in page, for the phone: `GET /sign-in` (the code from the QR filled in), `POST /sign-in`
 * (a code and an email: emails the magic link), `GET /sign-in/link?token=…` (the link: a button) and
 * `POST /sign-in/link` (the button: signs the TV in). "Delete my account": `GET /delete-account` (an
 * email), `POST /delete-account` (emails a delete link), `GET /delete-account/link?token=…` (a button)
 * and `POST /delete-account/link` (the button: deletes the Account). "Reset the Household PIN" (#440), the
 * same four under `/reset-pin`: the button clears the Account's PIN on every TV. See src/accounts.ts.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (request.method === "POST" && pathname === "/v1/devices") {
      return json({ token: await register(env.DB) }, 201);
    }
    if (pathname === "/" && request.method === "GET") return new Response(null, { status: 302, headers: { Location: "/sign-in" } });
    if (pathname === "/sign-in" || pathname === "/sign-in/link") return signInPages(request, env, pathname);
    if (pathname === "/delete-account" || pathname === "/delete-account/link") return deletePages(request, env, pathname);
    if (pathname === "/reset-pin" || pathname === "/reset-pin/link") return resetPinPages(request, env, pathname);
    if (!pathname.startsWith("/v1/")) return problem(404, "No such endpoint.");
    const device = await deviceOf(env.DB, request);
    if (device === null) return problem(401, "A registered device token is required.");
    const answer = await api(request, env, pathname, device.hash);
    // Every answer says whether the device is signed in, so one signed out from another TV, or whose
    // Account was deleted, goes anonymous at its next request, whatever it asked (#434).
    const response = new Response(answer.body, answer);
    if (!response.headers.has(ACCOUNT_HEADER)) response.headers.set(ACCOUNT_HEADER, device.signedIn ? "signed-in" : "none");
    return response;
  },

  /** The Cron Trigger (`triggers.crons` in wrangler.jsonc), each minute: reads the wanted Leagues' schedules, then Wikipedia's most viewed articles, a few parts a run, out of one budget of requests. */
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    const games = await fillGames(env);
    await fillTrending(env, SUBREQUESTS - games);
  },
} satisfies ExportedHandler<Env>;

/** The response header naming whether the requesting device is signed in to an Account: `signed-in` or `none`. */
const ACCOUNT_HEADER = "Zugig-Account";

/** The `/v1/` endpoints, for the registered device whose token hashes to [device]. */
async function api(request: Request, env: Env, pathname: string, device: string): Promise<Response> {
  if (request.method === "POST" && pathname === "/v1/sign-in") {
    const { model } = ((await request.json().catch(() => null)) ?? {}) as { model?: unknown };
    if (model !== undefined && model !== null && typeof model !== "string") return problem(400, "model must be the device's model name.");
    const { code, expiresIn } = await startSignIn(env.DB, device, modelName(model));
    return json({ code, url: `${site(request, env)}/sign-in?code=${code}`, expiresIn });
  }
  if (request.method === "POST" && pathname === "/v1/account") return json({ email: await accountOf(env.DB, device) });
  if (request.method === "POST" && pathname === "/v1/account/devices") return json({ devices: await devicesOf(env.DB, device) });
  if (request.method === "POST" && pathname === "/v1/account/sign-out") {
    const { device: id } = ((await request.json().catch(() => null)) ?? {}) as { device?: unknown };
    if (id !== undefined && !isDeviceId(id)) return problem(400, "device must be the id of a device the Account lists.");
    const out = await signOut(env.DB, device, id);
    if (out === null) return problem(404, "No such device on this Account.");
    return new Response(null, { status: 204, headers: out === "this" ? { [ACCOUNT_HEADER]: "none" } : {} });
  }
  if (request.method === "POST" && pathname === "/v1/sync") {
    const accountId = await accountIdOf(env.DB, device);
    if (accountId === null) return problem(403, "This device isn't signed in to an Account.");
    const asked = readSyncRequest(await request.json().catch(() => null));
    if (typeof asked === "string") return problem(400, asked);
    return json(await sync(env.DB, accountId, asked, device));
  }
  if (request.method === "POST" && pathname === "/v1/file-reads/lookup") {
    const asked = await fileReadsRequest(request, "listings");
    if (typeof asked === "string") return problem(400, asked);
    return json({ files: await fileReads(env.DB, asked.provider, asked.listings) });
  }
  if (request.method === "POST" && pathname === "/v1/file-reads") {
    const asked = await fileReadsRequest(request, "files");
    if (typeof asked === "string") return problem(400, asked);
    // A test host's reads aren't a real Provider's files (#545): taken, but not kept.
    if (!isTestHostKey(asked.provider)) await saveFileReads(env.DB, asked.provider, asked.files);
    return new Response(null, { status: 204 });
  }
  if (request.method === "POST" && pathname === "/v1/unknown-prefixes") {
    const asked = await unknownPrefixesRequest(request);
    if (typeof asked === "string") return problem(400, asked);
    // A test host's catalog isn't a real Provider's (#545): taken, but not kept for the weekly issue.
    if (!isTestHostKey(asked.provider)) {
      await saveUnknownPrefixes(env.DB, asked.provider, asked.prefixes);
      await saveCandidateTierTags(env.DB, asked.provider, asked.tierTags);
    }
    return new Response(null, { status: 204 });
  }
  if (request.method === "POST" && pathname === "/v1/provider-table") {
    const { provider } = ((await request.json().catch(() => null)) ?? {}) as { provider?: unknown };
    if (!isProviderKey(provider)) return problem(400, "provider must be the SHA-256 of the Provider's host, in lowercase hex.");
    return new Response(await providerTable(env.DB, provider), { headers: { "Content-Type": "text/plain; charset=utf-8" } });
  }
  if (request.method === "POST" && pathname === "/v1/title-facts") {
    const asked = await titleFactsRequest(request);
    if (typeof asked === "string") return problem(400, asked);
    return json(await titleFacts(env, asked));
  }
  if (request.method === "POST" && pathname === "/v1/trending") {
    const { language } = ((await request.json().catch(() => null)) ?? {}) as { language?: unknown };
    if (typeof language !== "string" || !/^[a-z]{2}$/.test(language)) return problem(400, "language must be the App language's ISO 639-1 code, such as \"fr\".");
    return json(await trending(env, language));
  }
  if (request.method === "POST" && pathname === "/v1/subtitles") {
    const asked = await subtitlesRequest(request);
    if (typeof asked === "string") return problem(400, asked);
    return json(await searchSubtitles(env, asked));
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
}

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

/** "Delete my account" and its magic links, for the phone (#434). */
async function deletePages(request: Request, env: Env, pathname: string): Promise<Response> {
  const url = new URL(request.url);
  if (pathname === "/delete-account" && request.method === "GET") return htmlResponse(deletePage());
  if (pathname === "/delete-account/link" && request.method === "GET") {
    const token = url.searchParams.get("token");
    if (!isMagicToken(token)) return htmlResponse(deleteLinkPage({ kind: "used" }), 410);
    return htmlResponse(confirmDeletePage(token));
  }
  if (request.method !== "POST") return htmlResponse(messagePage("Not here", "This page only takes its own form."), 405);
  const form = await request.formData().catch(() => null);
  const field = (name: string) => {
    const value = form?.get(name);
    return typeof value === "string" ? value : "";
  };
  if (pathname === "/delete-account/link") {
    const token = field("token");
    const outcome: DeleteOutcome = isMagicToken(token) ? await useDeleteLink(env.DB, token) : { kind: "used" };
    return htmlResponse(deleteLinkPage(outcome), outcome.kind === "deleted" ? 200 : 410);
  }
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const outcome = await requestDeletion(env, site(request, env), field("email"), ip);
  switch (outcome.kind) {
    case "sent":
      return htmlResponse(messagePage("Check your email", `If an account uses ${outcome.email}, we sent it a link. Tap it within 15 minutes to delete the account.`));
    case "bad-email": return htmlResponse(deletePage({ text: "That doesn't look like an email address.", error: true }), 400);
    case "too-many": return htmlResponse(deletePage({ text: "Too many links asked for. Wait 15 minutes and try again.", error: true }), 429);
    case "not-sent": return htmlResponse(deletePage({ text: "We couldn't send the email right now. Try again in a moment.", error: true }), 503);
  }
}

/** "Reset the Household PIN" and its magic links, for the phone (#440). */
async function resetPinPages(request: Request, env: Env, pathname: string): Promise<Response> {
  const url = new URL(request.url);
  if (pathname === "/reset-pin" && request.method === "GET") return htmlResponse(resetPinPage());
  if (pathname === "/reset-pin/link" && request.method === "GET") {
    const token = url.searchParams.get("token");
    if (!isMagicToken(token)) return htmlResponse(resetPinLinkPage({ kind: "used" }), 410);
    return htmlResponse(confirmResetPinPage(token));
  }
  if (request.method !== "POST") return htmlResponse(messagePage("Not here", "This page only takes its own form."), 405);
  const form = await request.formData().catch(() => null);
  const field = (name: string) => {
    const value = form?.get(name);
    return typeof value === "string" ? value : "";
  };
  if (pathname === "/reset-pin/link") {
    const token = field("token");
    const outcome: ResetPinOutcome = isMagicToken(token) ? await useResetPinLink(env.DB, token) : { kind: "used" };
    return htmlResponse(resetPinLinkPage(outcome), outcome.kind === "reset" ? 200 : 410);
  }
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const outcome = await requestDeletion(env, site(request, env), field("email"), ip, "reset-pin");
  switch (outcome.kind) {
    case "sent":
      return htmlResponse(messagePage("Check your email", `If an account uses ${outcome.email}, we sent it a link. Tap it within 15 minutes to reset the Household PIN.`));
    case "bad-email": return htmlResponse(resetPinPage({ text: "That doesn't look like an email address.", error: true }), 400);
    case "too-many": return htmlResponse(resetPinPage({ text: "Too many links asked for. Wait 15 minutes and try again.", error: true }), 429);
    case "not-sent": return htmlResponse(resetPinPage({ text: "We couldn't send the email right now. Try again in a moment.", error: true }), 503);
  }
}

function resetPinLinkPage(outcome: ResetPinOutcome): string {
  switch (outcome.kind) {
    case "reset": return messagePage("The Household PIN is reset", "Each TV signed in to your account forgets the PIN the next time it goes online. The next time someone leaves a Kids profile, the TV asks for a new one.");
    case "used": return messagePage("This link doesn't work", "This link was already used or isn't one we sent. Ask for a new one on the Reset the Household PIN page.");
    case "expired": return messagePage("This link doesn't work", "This link has expired: links work for 15 minutes. Ask for a new one on the Reset the Household PIN page.");
  }
}

function deleteLinkPage(outcome: DeleteOutcome): string {
  switch (outcome.kind) {
    case "deleted": return messagePage("Your account is deleted", "Your account and everything it kept are deleted. Each TV signed in to it is signed out the next time it goes online, and keeps working without an account.");
    case "used": return messagePage("This link doesn't work", "This link was already used or isn't one we sent. Ask for a new one on the Delete my account page.");
    case "expired": return messagePage("This link doesn't work", "This link has expired: links work for 15 minutes. Ask for a new one on the Delete my account page.");
  }
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

/** Reads a Title facts request, or says what's wrong with it. */
async function titleFactsRequest(request: Request): Promise<TitleKey[] | string> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return "The body must be JSON.";
  }
  const { titles } = (body ?? {}) as { titles?: unknown };
  if (!Array.isArray(titles) || titles.length > MAX_FACTS_BATCH) return `titles must be a list of at most ${MAX_FACTS_BATCH} Titles.`;
  const keys = new Map<string, TitleKey>();
  for (const title of titles as { kind?: unknown; tmdbId?: unknown }[]) {
    if ((title?.kind !== "movie" && title?.kind !== "show") || !Number.isSafeInteger(title.tmdbId) || (title.tmdbId as number) <= 0) {
      return "Each Title must be {\"kind\": \"movie\" or \"show\", \"tmdbId\": a positive integer}.";
    }
    keys.set(`${title.kind}/${title.tmdbId}`, { kind: title.kind, tmdbId: title.tmdbId as number });
  }
  return [...keys.values()];
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
  for (const file of asked as { listingId?: unknown; file?: unknown; prefix?: unknown; hints?: unknown }[]) {
    if (!isListingId(file?.listingId) || !isFile(file.file) || !isReportedPrefix(file.prefix) || !isReportedHints(file.hints)) {
      return "Each file must be {\"listingId\": a positive integer, \"file\": an object of at most 8 KB, \"prefix\": a Prefix code or null, \"hints\": a list of Category hint words}.";
    }
  }
  const kept = (asked as FileRead[]).map(({ listingId, file, prefix, hints }) => ({ listingId, file, prefix: prefix ?? null, hints: hints ?? [] }));
  return { provider, listings: [], files: kept };
}

/** Reads an unknown Prefixes report, or says what's wrong with it. */
async function unknownPrefixesRequest(request: Request): Promise<{ provider: string; prefixes: UnknownPrefix[]; tierTags: CandidateTierTag[] } | string> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return "The body must be JSON.";
  }
  const { provider, prefixes, tierTags = [] } = (body ?? {}) as { provider?: unknown; prefixes?: unknown; tierTags?: unknown };
  if (!isProviderKey(provider)) return "provider must be the SHA-256 of the Provider's host, in lowercase hex.";
  if (!Array.isArray(prefixes) || prefixes.length > MAX_PREFIX_BATCH) return `prefixes must be a list of at most ${MAX_PREFIX_BATCH}.`;
  if (!prefixes.every(isUnknownPrefix)) return "Each Prefix must be {\"prefix\": 1 to 32 characters, \"listingCount\": a positive integer, \"example\": a Listing's name}.";
  if (!Array.isArray(tierTags) || tierTags.length > MAX_TIER_TAG_BATCH) return `tierTags must be a list of at most ${MAX_TIER_TAG_BATCH}.`;
  if (!tierTags.every(isCandidateTierTag)) return "Each Tier tag must be {\"word\": one word of 1 to 5 letters, digits or +, \"listingCount\": a positive integer, \"example\": a Listing's name}.";
  return { provider, prefixes, tierTags };
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
