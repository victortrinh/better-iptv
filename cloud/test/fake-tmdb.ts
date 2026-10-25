import { vi } from "vitest";

/**
 * Stands in for TMDB's API by answering the Worker's outgoing fetches. Titles are added with
 * [movie] and [show]; [requests] records what reached TMDB.
 */
export class FakeTmdb {
  readonly requests: URL[] = [];
  readonly tokens: (string | null)[] = [];
  /** Answers every request with this HTTP status instead, as TMDB does when rate-limiting (429) or down. */
  failWith: number | null = null;
  private readonly titles = new Map<string, (language: string) => object>();

  constructor() {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      if (!url.href.startsWith("https://api.themoviedb.org/3/")) throw new Error(`unexpected fetch: ${url}`);
      this.requests.push(url);
      this.tokens.push(new Headers(init?.headers).get("Authorization"));
      if (this.failWith !== null) return new Response("{}", { status: this.failWith });
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

  restore() {
    vi.restoreAllMocks();
  }
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
