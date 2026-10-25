import { isRegistered, register } from "./devices";
import type { Env } from "./env";
import { match, MAX_MATCH_BATCH, type MatchQuestion } from "./match";
import { MAX_BATCH, metadata } from "./metadata";
import type { TitleKey } from "./tmdb";

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

const json = (body: unknown, status = 200) => Response.json(body, { status });

const problem = (status: number, error: string) => json({ error }, status);
