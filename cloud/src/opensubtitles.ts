import type { Env } from "./env";
import { type Found, NOT_FOUND, type Searched, type ServiceFile, ServiceUnavailable } from "./subtitle-service";

/** A login is good for 24 hours; it is renewed a little before. */
const SESSION_FOR = 23 * 3600_000;

/** Whether the cloud has OpenSubtitles' API key and the account downloads count against. */
export const configured = (env: Env) => !!(env.OPENSUBTITLES_API_KEY && env.OPENSUBTITLES_USERNAME && env.OPENSUBTITLES_PASSWORD);

/**
 * OpenSubtitles' subtitles for a Movie or Episode in [languages] (ISO 639-1), most downloaded first.
 * Machine- and AI-translated ones are left out: they read badly.
 */
export async function search(env: Env, asked: Searched, languages: string[]): Promise<Found[]> {
  const params: Record<string, string> = { languages: [...new Set(languages.flatMap(serviceLanguages))].sort().join(","), order_by: "download_count" };
  if (asked.season === null) {
    params.tmdb_id = String(asked.tmdbId);
    if (asked.imdbId) params.imdb_id = asked.imdbId.replace(/^tt/, "");
  } else {
    params.parent_tmdb_id = String(asked.tmdbId);
    if (asked.imdbId) params.parent_imdb_id = asked.imdbId.replace(/^tt/, "");
    params.season_number = String(asked.season);
    params.episode_number = String(asked.episode);
  }
  // OpenSubtitles redirects a query whose parameters aren't in alphabetical order.
  const url = new URL(`${env.OPENSUBTITLES_API}/subtitles`);
  for (const name of Object.keys(params).sort()) url.searchParams.set(name, params[name]);
  const response = await call(env, url, { headers: headers(env) });
  const { data } = (await response.json()) as { data?: OsSubtitle[] };
  return (data ?? [])
    .filter((s) => !s.attributes.machine_translated && !s.attributes.ai_translated && s.attributes.files?.length)
    .map((s) => {
      const language = primaryLanguage(s.attributes.language);
      return {
        ref: `${language}:${s.attributes.files![0].file_id}`,
        language,
        release: s.attributes.release ?? s.attributes.files![0].file_name ?? "",
        hearingImpaired: !!s.attributes.hearing_impaired,
        downloads: s.attributes.download_count ?? 0,
      };
    })
    .sort((a, b) => b.downloads - a.downloads);
}

/** Downloads the file a search gave as [ref], converted to SRT by OpenSubtitles. Each one counts against the account's daily quota. */
export async function download(env: Env, ref: string): Promise<ServiceFile | typeof NOT_FOUND> {
  const fileId = Number(ref.split(":")[1]);
  if (!Number.isSafeInteger(fileId) || fileId <= 0) return NOT_FOUND;
  let session = await signedIn(env, false);
  let response = await downloadLink(env, session, fileId);
  if (response.status === 401) {
    session = await signedIn(env, true);
    response = await downloadLink(env, session, fileId);
  }
  if (response.status === 404) return NOT_FOUND;
  // 406: the account's downloads for the day are used up.
  await failUnlessOk(response, "download");
  const { link } = (await response.json()) as { link?: string };
  if (!link) throw new ServiceUnavailable("OpenSubtitles gave no download link");
  const file = await call(env, new URL(link), {});
  return { name: "subtitle.srt", bytes: new Uint8Array(await file.arrayBuffer()) };
}

const downloadLink = (env: Env, session: Session, fileId: number) =>
  fetchOrUnavailable(`https://${session.host}/api/v1/download`, {
    method: "POST",
    headers: { ...headers(env), "Content-Type": "application/json", Authorization: `Bearer ${session.token}` },
    body: JSON.stringify({ file_id: fileId, sub_format: "srt" }),
  });

interface Session {
  token: string;
  /** The API host OpenSubtitles says this account downloads through. */
  host: string;
}

/** The kept login, or a new one when there is none, it is old, or [renew] says OpenSubtitles stopped taking it. */
async function signedIn(env: Env, renew: boolean): Promise<Session> {
  if (!renew) {
    const kept = await env.DB.prepare("SELECT token, base_url, fetched_at FROM service_session WHERE service = 'opensubtitles'")
      .first<{ token: string; base_url: string; fetched_at: number }>();
    if (kept && Date.now() - kept.fetched_at < SESSION_FOR) return { token: kept.token, host: kept.base_url };
  }
  const response = await fetchOrUnavailable(`${env.OPENSUBTITLES_API}/login`, {
    method: "POST",
    headers: { ...headers(env), "Content-Type": "application/json" },
    body: JSON.stringify({ username: env.OPENSUBTITLES_USERNAME, password: env.OPENSUBTITLES_PASSWORD }),
  });
  await failUnlessOk(response, "login");
  const { token, base_url } = (await response.json()) as { token?: string; base_url?: string };
  if (!token) throw new ServiceUnavailable("OpenSubtitles gave no login token");
  const host = base_url && /^[\w.-]+$/.test(base_url) ? base_url : new URL(env.OPENSUBTITLES_API).host;
  await env.DB.prepare("INSERT OR REPLACE INTO service_session (service, token, base_url, fetched_at) VALUES ('opensubtitles', ?1, ?2, ?3)")
    .bind(token, host, Date.now())
    .run();
  return { token, host };
}

const headers = (env: Env) => ({ Accept: "application/json", "Api-Key": env.OPENSUBTITLES_API_KEY!, "User-Agent": env.OPENSUBTITLES_USER_AGENT });

async function call(env: Env, url: URL, init: RequestInit): Promise<Response> {
  const response = await fetchOrUnavailable(url, init);
  await failUnlessOk(response, url.pathname);
  return response;
}

async function fetchOrUnavailable(url: string | URL, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch (e) {
    throw new ServiceUnavailable(String(e));
  }
}

async function failUnlessOk(response: Response, what: string) {
  if (response.ok) return;
  // Shows up in the Worker's logs; OpenSubtitles' message names the problem (a wrong key, the quota), never the key.
  console.warn(`OpenSubtitles answered HTTP ${response.status} for ${what}: ${(await response.text()).slice(0, 200)}`);
  throw new ServiceUnavailable(`OpenSubtitles answered HTTP ${response.status}`);
}

/** OpenSubtitles' codes for a language: Portuguese and Chinese come in two. */
function serviceLanguages(language: string): string[] {
  if (language === "pt") return ["pt-pt", "pt-br"];
  if (language === "zh") return ["zh-cn", "zh-tw"];
  return [language];
}

/** `pt-BR` is `pt`. */
const primaryLanguage = (code: string) => code.toLowerCase().split("-")[0];

/** The parts of an OpenSubtitles search result the cloud uses. */
interface OsSubtitle {
  attributes: {
    language: string;
    download_count?: number;
    hearing_impaired?: boolean;
    machine_translated?: boolean;
    ai_translated?: boolean;
    release?: string;
    files?: { file_id: number; file_name?: string }[];
  };
}
