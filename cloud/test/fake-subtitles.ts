import { vi } from "vitest";

/** A subtitle file one of the fake services has, for the Movie or Episode [tmdbId] (with [season] and [episode]). */
export interface FakeSubtitle {
  tmdbId: number;
  season?: number;
  episode?: number;
  /** As the service names the language: `en` and `pt-BR` on OpenSubtitles, `EN` and `BR_PT` on Subdl. */
  language: string;
  release: string;
  hearingImpaired?: boolean;
  /** OpenSubtitles only. */
  downloads?: number;
  machineTranslated?: boolean;
  /** The files OpenSubtitles serves, or Subdl puts in its zip, by name. */
  files: Record<string, Uint8Array | string>;
}

/**
 * Stands in for OpenSubtitles' and Subdl's APIs by answering the Worker's outgoing fetches.
 * Subtitles are added with [openSubtitles] and [subdl]; [requests] records what reached either.
 */
export class FakeSubtitleServices {
  readonly requests: Request[] = [];
  /** Answers every request to that service with this HTTP status instead, as when it's down or rate-limiting. */
  openSubtitlesFailWith: number | null = null;
  subdlFailWith: number | null = null;
  /** OpenSubtitles' downloads left today: past them, a download is refused with HTTP 406. */
  downloadsLeft = 20;
  /** The login tokens OpenSubtitles takes; a token can be revoked to make it answer 401. */
  readonly tokens = new Set<string>();
  private readonly os: (FakeSubtitle & { fileId: number })[] = [];
  private readonly sd: (FakeSubtitle & { path: string })[] = [];

  constructor() {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const request = new Request(input, init);
      this.requests.push(request.clone());
      const url = new URL(request.url);
      if (url.host === "api.opensubtitles.com" || url.host === "vip-api.opensubtitles.com" || url.host === "dl.opensubtitles.example") return this.openSubtitlesAnswer(url, request);
      if (url.host === "api.subdl.com" || url.host === "dl.subdl.com") return this.subdlAnswer(url);
      throw new Error(`unexpected fetch: ${url}`);
    });
  }

  openSubtitles(subtitle: FakeSubtitle) {
    this.os.push({ ...subtitle, fileId: 9000 + this.os.length });
  }

  subdl(subtitle: FakeSubtitle) {
    this.sd.push({ ...subtitle, path: `${3_000_000 + this.sd.length}-${4_000_000 + this.sd.length}` });
  }

  /** The requests that reached a service, as `opensubtitles /api/v1/subtitles` or `subdl /api/v1/subtitles`. */
  get calls(): string[] {
    return this.requests.map((r) => {
      const url = new URL(r.url);
      return `${url.host.includes("subdl") ? "subdl" : "opensubtitles"} ${url.pathname}`;
    });
  }

  restore() {
    vi.restoreAllMocks();
  }

  private async openSubtitlesAnswer(url: URL, request: Request): Promise<Response> {
    if (this.openSubtitlesFailWith !== null) return Response.json({ message: "down" }, { status: this.openSubtitlesFailWith });
    if (url.host === "dl.opensubtitles.example") {
      const subtitle = this.os.find((s) => String(s.fileId) === url.pathname.slice(1));
      return subtitle ? new Response(Object.values(subtitle.files)[0]) : new Response("gone", { status: 404 });
    }
    if (request.headers.get("Api-Key") !== "test-os-key" || !request.headers.get("User-Agent")) return Response.json({ message: "bad key" }, { status: 403 });
    if (url.pathname === "/api/v1/login") {
      const { username, password } = (await request.json()) as { username: string; password: string };
      if (username !== "test-os-user" || password !== "test-os-password") return Response.json({ message: "bad login" }, { status: 401 });
      const token = `token-${this.tokens.size + 1}`;
      this.tokens.add(token);
      return Response.json({ token, base_url: "vip-api.opensubtitles.com", user: { allowed_downloads: 20 } });
    }
    if (url.pathname === "/api/v1/subtitles") {
      // Like OpenSubtitles, redirects a query whose parameters aren't sorted.
      const names = [...url.searchParams.keys()];
      if (names.join() !== [...names].sort().join()) return new Response(null, { status: 301, headers: { Location: "elsewhere" } });
      const languages = (url.searchParams.get("languages") ?? "").split(",");
      const episode = url.searchParams.has("parent_tmdb_id");
      const tmdbId = Number(url.searchParams.get(episode ? "parent_tmdb_id" : "tmdb_id"));
      const data = this.os
        .filter((s) => s.tmdbId === tmdbId && languages.includes(s.language.toLowerCase()))
        .filter((s) => !episode || (s.season === Number(url.searchParams.get("season_number")) && s.episode === Number(url.searchParams.get("episode_number"))))
        .map((s) => ({
          id: String(s.fileId),
          type: "subtitle",
          attributes: {
            language: s.language,
            download_count: s.downloads ?? 0,
            hearing_impaired: s.hearingImpaired ?? false,
            machine_translated: s.machineTranslated ?? false,
            ai_translated: false,
            release: s.release,
            files: [{ file_id: s.fileId, file_name: Object.keys(s.files)[0] }],
          },
        }));
      return Response.json({ total_count: data.length, data });
    }
    if (url.pathname === "/api/v1/download" && url.host === "vip-api.opensubtitles.com") {
      if (!this.tokens.has((request.headers.get("Authorization") ?? "").replace("Bearer ", ""))) return Response.json({ message: "invalid token" }, { status: 401 });
      const { file_id } = (await request.json()) as { file_id: number };
      if (this.downloadsLeft <= 0) return Response.json({ message: "You have downloaded your allowed 20 subtitles for 24h" }, { status: 406 });
      if (!this.os.some((s) => s.fileId === file_id)) return Response.json({ message: "not found" }, { status: 404 });
      this.downloadsLeft--;
      return Response.json({ link: `https://dl.opensubtitles.example/${file_id}`, remaining: this.downloadsLeft });
    }
    return Response.json({ message: "no such endpoint" }, { status: 404 });
  }

  private async subdlAnswer(url: URL): Promise<Response> {
    if (this.subdlFailWith !== null) return Response.json({ status: false, error: "down" }, { status: this.subdlFailWith });
    if (url.host === "dl.subdl.com") {
      // Subdl's search gives each zip's url with the caller's key in it.
      if (url.searchParams.get("api_key") !== "test-subdl-key") return new Response("bad key", { status: 403 });
      const subtitle = this.sd.find((s) => url.pathname === `/subtitle/${s.path}.zip`);
      return subtitle ? new Response(await zip(subtitle.files)) : new Response("gone", { status: 404 });
    }
    if (url.searchParams.get("api_key") !== "test-subdl-key") return Response.json({ status: false, error: "bad key" }, { status: 403 });
    const languages = (url.searchParams.get("languages") ?? "").split(",");
    const tmdbId = Number(url.searchParams.get("tmdb_id"));
    const tv = url.searchParams.get("type") === "tv";
    const subtitles = this.sd
      .filter((s) => s.tmdbId === tmdbId && languages.includes(s.language))
      .filter((s) => !tv || (s.season === Number(url.searchParams.get("season_number")) && (s.episode === undefined || s.episode === Number(url.searchParams.get("episode_number")))))
      .map((s) => ({ release_name: s.release, name: `SUBDL::${s.release}.zip`, lang: s.language.toLowerCase(), language: s.language, hi: s.hearingImpaired ?? false, url: `/subtitle/${s.path}.zip?api_key=${url.searchParams.get("api_key")}` }));
    // Like Subdl, a Title with no subtitles is an error.
    if (subtitles.length === 0) return Response.json({ status: false, error: "can't find movie or tv" });
    return Response.json({ status: true, results: [{ tmdb_id: tmdbId }], subtitles });
  }
}

/** A zip archive of [files], deflated, the way Subdl serves them. */
export async function zip(files: Record<string, Uint8Array | string>): Promise<Uint8Array> {
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const [name, contents] of Object.entries(files)) {
    const raw = typeof contents === "string" ? new TextEncoder().encode(contents) : contents;
    const deflated = new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new CompressionStream("deflate-raw"))).arrayBuffer());
    const fileName = new TextEncoder().encode(name);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(8, 8, true);
    local.setUint32(18, deflated.length, true);
    local.setUint32(22, raw.length, true);
    local.setUint16(26, fileName.length, true);
    const entry = new DataView(new ArrayBuffer(46));
    entry.setUint32(0, 0x02014b50, true);
    entry.setUint16(10, 8, true);
    entry.setUint32(20, deflated.length, true);
    entry.setUint32(24, raw.length, true);
    entry.setUint16(28, fileName.length, true);
    entry.setUint32(42, offset, true);
    parts.push(new Uint8Array(local.buffer), fileName, deflated);
    central.push(new Uint8Array(entry.buffer), fileName);
    offset += 30 + fileName.length + deflated.length;
  }
  const directorySize = central.reduce((sum, part) => sum + part.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, Object.keys(files).length, true);
  end.setUint16(10, Object.keys(files).length, true);
  end.setUint32(12, directorySize, true);
  end.setUint32(16, offset, true);
  return new Uint8Array(await new Blob([...parts, ...central, new Uint8Array(end.buffer)]).arrayBuffer());
}
