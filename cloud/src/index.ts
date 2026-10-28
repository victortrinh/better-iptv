import { isRegistered, register } from "./devices";
import type { Env } from "./env";
import { fileReads, type FileRead, isFile, isListingId, isProviderKey, MAX_FILE_BATCH, saveFileReads } from "./files";
import { match, MAX_MATCH_BATCH, type MatchQuestion } from "./match";
import { MAX_BATCH, metadata } from "./metadata";
import { MAX_QUERY, search } from "./search";
import { TmdbUnavailable, type TitleKey } from "./tmdb";

/**
 * The cloud's HTTP API (test seam 2). It serves only data that is the same for every Viewer;
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
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (request.method === "POST" && pathname === "/v1/devices") {
      return json({ token: await register(env.DB) }, 201);
    }
    if (!pathname.startsWith("/v1/")) return problem(404, "No such endpoint.");
    if (!(await isRegistered(env.DB, request))) return problem(401, "A registered device token is required.");
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
    return problem(404, "No such endpoint.");
  },
} satisfies ExportedHandler<Env>;

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

const json = (body: unknown, status = 200) => Response.json(body, { status });

const problem = (status: number, error: string) => json({ error }, status);
