import { vi } from "vitest";

/**
 * Stands in for TMDB's API by answering the Worker's outgoing fetches. Titles are added with
 * [movie] and [show]; [requests] records what reached TMDB.
 */
export class FakeTmdb {
  readonly requests: URL[] = [];
  /** The credential each request carried: its bearer token, or its `api_key`. */
  readonly tokens: (string | null)[] = [];
  /** Answers every request with this HTTP status instead, as TMDB does when rate-limiting (429) or down. */
  failWith: number | null = null;
  /** Answers only the requests this picks with HTTP 503. */
  failWhen: ((url: URL) => boolean) | null = null;
  private readonly titles = new Map<string, (language: string) => object>();
  private readonly searchable: { path: string; result: (language: string) => SearchResult }[] = [];
  private readonly multi: { names: string[]; result: object }[] = [];
  private readonly people = new Map<number, { cast: object[]; crew: object[] }>();

  constructor() {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      if (!url.href.startsWith("https://api.themoviedb.org/3/")) throw new Error(`unexpected fetch: ${url}`);
      this.requests.push(url);
      this.tokens.push(new Headers(init?.headers).get("Authorization") ?? (url.searchParams.has("api_key") ? `api_key=${url.searchParams.get("api_key")}` : null));
      if (this.failWith !== null) return new Response("{}", { status: this.failWith });
      if (this.failWhen?.(url)) return new Response("{}", { status: 503 });
      const search = /^\/3\/search\/(movie|tv)$/.exec(url.pathname);
      if (search) {
        // Like TMDB, finds a query in the title in the requested language or in the original title, punctuation aside.
        const loose = (text: string) => text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");
        const query = loose(url.searchParams.get("query") ?? "");
        const language = url.searchParams.get("language") ?? "en";
        const results = this.searchable
          .filter((entry) => entry.path === search[1])
          .map((entry) => entry.result(language))
          .filter((r) => [r.title, r.name, r.original_title, r.original_name].some((t) => t !== undefined && loose(t).includes(query)));
        return Response.json({ page: 1, results, total_results: results.length });
      }
      if (url.pathname === "/3/search/multi") {
        // Like TMDB, finds a query in any of a result's names, in every language.
        const query = (url.searchParams.get("query") ?? "").toLowerCase();
        const results = this.multi.filter((entry) => entry.names.some((name) => name.toLowerCase().includes(query))).map((entry) => entry.result);
        return Response.json({ page: 1, results, total_results: results.length });
      }
      const person = /^\/3\/person\/(\d+)\/combined_credits$/.exec(url.pathname);
      if (person) {
        const credits = this.people.get(Number(person[1]));
        if (credits === undefined) return Response.json({ success: false, status_code: 34 }, { status: 404 });
        return Response.json({ id: Number(person[1]), ...credits });
      }
      const title = this.titles.get(url.pathname.replace("/3/", ""));
      if (title === undefined) return Response.json({ success: false, status_code: 34 }, { status: 404 });
      return Response.json(title(url.searchParams.get("language") ?? "en"));
    });
  }

  /** TMDB's movie details for [id], with [answer] building the response in the requested language. */
  movie(id: number, answer: (language: string) => object) {
    this.titles.set(`movie/${id}`, answer);
  }

  show(id: number, answer: (language: string) => object) {
    this.titles.set(`tv/${id}`, answer);
  }

  /** TMDB's details of [show]'s season [season], with [answer] building the response in the requested language. */
  season(show: number, season: number, answer: (language: string) => object) {
    this.titles.set(`tv/${show}/season/${season}`, answer);
  }

  /** A movie TMDB's search finds, with [result] building it in the requested language. */
  searchableMovie(result: (language: string) => SearchResult) {
    this.searchable.push({ path: "movie", result });
  }

  searchableShow(result: (language: string) => SearchResult) {
    this.searchable.push({ path: "tv", result });
  }

  /** A movie TMDB's multi search finds by any of [names]. */
  multiMovie(id: number, popularity: number, ...names: string[]) {
    this.multi.push({ names, result: { media_type: "movie", id, title: names[0], popularity } });
  }

  multiShow(id: number, popularity: number, ...names: string[]) {
    this.multi.push({ names, result: { media_type: "tv", id, name: names[0], popularity } });
  }

  /** A person TMDB's multi search finds by [name], with their combined credits. */
  person(id: number, name: string, popularity: number, credits: { cast?: object[]; crew?: object[] }) {
    this.multi.push({ names: [name], result: { media_type: "person", id, name, popularity, known_for: [] } });
    this.people.set(id, { cast: credits.cast ?? [], crew: credits.crew ?? [] });
  }

  /** The search requests that reached TMDB. */
  get searches(): URL[] {
    return this.requests.filter((url) => url.pathname.startsWith("/3/search/"));
  }

  restore() {
    vi.restoreAllMocks();
  }
}

/** One result of TMDB's movie or TV search. */
export interface SearchResult {
  id: number;
  title?: string;
  name?: string;
  original_title?: string;
  original_name?: string;
  release_date?: string;
  first_air_date?: string;
  popularity: number;
}

/** The Godfather, shaped like TMDB's movie details with everything the app appends. */
export const godfather = (language: string) => ({
  id: 238,
  title: language === "fr" ? "Le Parrain" : "The Godfather",
  original_title: "The Godfather",
  original_language: "en",
  overview: language === "fr" ? "En 1945, à New York…" : "Spanning the years 1945 to 1955…",
  genres: language === "fr" ? [{ id: 18, name: "Drame" }, { id: 80, name: "Crime" }] : [{ id: 18, name: "Drama" }, { id: 80, name: "Crime" }],
  popularity: 128.4,
  release_date: "1972-03-14",
  runtime: 175,
  poster_path: language === "fr" ? "/parrain.jpg" : "/godfather.jpg",
  backdrop_path: "/godfather-backdrop.jpg",
  imdb_id: "tt0068646",
  translations: {
    translations: [
      { iso_639_1: "en", iso_3166_1: "US", data: { title: "", overview: "Spanning the years 1945 to 1955…" } },
      { iso_639_1: "fr", iso_3166_1: "FR", data: { title: "Le Parrain", overview: "En 1945, à New York…" } },
      { iso_639_1: "fr", iso_3166_1: "CA", data: { title: "Le Parrain", overview: "" } },
      { iso_639_1: "de", iso_3166_1: "DE", data: { title: "Der Pate", overview: "Don Vito Corleone…" } },
    ],
  },
  alternative_titles: { titles: [{ iso_3166_1: "US", title: "Mario Puzo's The Godfather" }] },
  credits: {
    cast: [
      { name: "Al Pacino", character: "Michael Corleone", profile_path: "/pacino.jpg", order: 1 },
      { name: "Marlon Brando", character: "Don Vito Corleone", profile_path: "/brando.jpg", order: 0 },
    ],
  },
  videos: {
    results: [
      { key: "teaser1", site: "YouTube", type: "Teaser", official: true },
      { key: "fan-trailer", site: "YouTube", type: "Trailer", official: false },
      { key: "official-trailer", site: "YouTube", type: "Trailer", official: true },
      { key: "vimeo1", site: "Vimeo", type: "Trailer", official: true },
    ],
  },
  external_ids: { imdb_id: "tt0068646" },
});

/** Breaking Bad, shaped like TMDB's TV details: `name` and `first_air_date` instead of `title` and `release_date`. */
export const breakingBad = (language: string) => ({
  id: 1396,
  name: "Breaking Bad",
  original_name: "Breaking Bad",
  original_language: "en",
  genres: [{ id: 18, name: language === "fr" ? "Drame" : "Drama" }],
  popularity: 300.1,
  first_air_date: "2008-01-20",
  episode_run_time: [45, 47],
  poster_path: "/bb.jpg",
  backdrop_path: null,
  translations: { translations: [{ iso_639_1: "en", iso_3166_1: "US", data: { name: "Breaking Bad", overview: "Walter White…" } }] },
  alternative_titles: { results: [{ iso_3166_1: "MX", title: "Hacerse malo" }] },
  credits: { cast: [{ name: "Bryan Cranston", character: "Walter White", profile_path: null, order: 0 }] },
  videos: { results: [] },
  external_ids: { imdb_id: "tt0903747" },
});
