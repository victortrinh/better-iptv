import type { FakeTmdb } from "./fake-tmdb";

/** The Wikidata Query Service's SPARQL endpoint, as the Worker's `WIKIDATA_SPARQL` says. */
const SPARQL = "https://query.wikidata.org/sparql";

/** What one of the fake's items carries, each left out when the item lacks it. */
export interface Item {
  /** The item's original language as P364 gives it, through P218: an ISO 639-1 code. */
  language?: string;
  imdbId?: string;
  /** The English and French Wikipedia article names, as Wikipedia shows them. */
  enwiki?: string;
  frwiki?: string;
}

/**
 * Stands in for the Wikidata Query Service, served through [tmdb]'s fetch stand-in. Items are added
 * with [movie] and [show], by the TMDB ID Wikidata files them under (P4947 and P4983); [requests]
 * records every query that reached it. Like the service, it refuses a request that names no
 * User-Agent (403), and answers only what the query asks for: a field the SPARQL doesn't mention
 * comes back empty.
 */
export class FakeWikidata {
  readonly requests: { query: string; userAgent: string | null; accept: string | null }[] = [];
  /** Answers every request with this HTTP status instead, as the service does when overloaded (429), down (5xx) or a query ran out of time (504). */
  failWith: number | null = null;
  /** Answers every request with HTTP 200 and a body that isn't JSON. */
  breakAnswers = false;
  private readonly items = new Map<string, Item>();

  constructor(tmdb: FakeTmdb) {
    tmdb.alsoServe(SPARQL, (url, init) => {
      const headers = new Headers(init?.headers);
      const query = init?.body instanceof URLSearchParams ? init.body.get("query") ?? "" : typeof init?.body === "string" ? new URLSearchParams(init.body).get("query") ?? "" : url.searchParams.get("query") ?? "";
      const userAgent = headers.get("User-Agent");
      this.requests.push({ query, userAgent, accept: headers.get("Accept") });
      if (userAgent === null || userAgent.trim() === "") return new Response("Please set a User-Agent", { status: 403 });
      if (this.failWith !== null) return new Response("", { status: this.failWith });
      if (this.breakAnswers) return new Response("<html>gateway</html>", { status: 200 });
      return Response.json({ head: { vars: ["kind", "id", "imdbId", "lang", "enwiki", "frwiki"] }, results: { bindings: this.answer(query) } });
    });
  }

  /** An item with TMDB movie ID (P4947) [tmdbId], carrying [item]. */
  movie(tmdbId: number, item: Item = {}) {
    this.items.set(`P4947/${tmdbId}`, item);
  }

  /** An item with TMDB TV series ID (P4983) [tmdbId], carrying [item]. */
  show(tmdbId: number, item: Item = {}) {
    this.items.set(`P4983/${tmdbId}`, item);
  }

  /** The Titles each query asked about, as `movie/238`, in the query's order. */
  get asked(): string[][] {
    return this.requests.map(({ query }) => [...query.matchAll(VALUE)].map((m) => `${m[1]}/${m[3]}`));
  }

  /**
   * The bindings for [query]: one per `VALUES` row whose property and ID name an item, with only the
   * fields the query asks for (its IMDb ID with P345, its language with P364 and P218, each article
   * name with its wiki's sitelinks).
   */
  private answer(query: string): object[] {
    const literal = (value: string | undefined, lang?: string) => (value === undefined ? undefined : { type: "literal", value, ...(lang ? { "xml:lang": lang } : {}) });
    const bindings: object[] = [];
    for (const [, kind, property, id] of query.matchAll(VALUE)) {
      const item = this.items.get(`${property}/${id}`);
      if (item === undefined) continue;
      const binding: Record<string, object | undefined> = {
        kind: literal(kind),
        id: literal(id),
        imdbId: query.includes("wdt:P345") ? literal(item.imdbId) : undefined,
        lang: query.includes("wdt:P364") && query.includes("wdt:P218") ? literal(item.language) : undefined,
        enwiki: query.includes("https://en.wikipedia.org/") ? literal(item.enwiki, "en") : undefined,
        frwiki: query.includes("https://fr.wikipedia.org/") ? literal(item.frwiki, "fr") : undefined,
      };
      bindings.push(Object.fromEntries(Object.entries(binding).filter(([, v]) => v !== undefined)));
    }
    return bindings;
  }
}

/** One row of the query's `VALUES`: the kind, the property and the TMDB ID. */
const VALUE = /\("(movie|show)" wdt:(P\d+) "(\d+)"\)/g;
