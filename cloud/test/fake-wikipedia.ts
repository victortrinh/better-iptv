import type { FakeWeb } from "./fake-web";

/** The Wikimedia pageviews API's root, as the Worker's `WIKIMEDIA_PAGEVIEWS` says. */
const PAGEVIEWS = "https://wikimedia.org/api/rest_v1/metrics/pageviews/";

/**
 * Stands in for the Wikimedia pageviews API's daily top articles, served through [web]'s fetch
 * stand-in. A day's list is added with [top], by wiki (`en`, `fr`) and day (`2026-11-06`); a day
 * not added isn't published yet, and answers 404 as the API does. [requests] records every request
 * that reached it. Like the API, it refuses a request that names no User-Agent (403).
 */
export class FakeWikipedia {
  readonly requests: { wiki: string; day: string; userAgent: string | null }[] = [];
  /** Answers every request with this HTTP status instead, as the API does when rate-limiting (429) or down (5xx). */
  failWith: number | null = null;
  private readonly days = new Map<string, { article: string; views: number }[]>();

  constructor(web: FakeWeb) {
    web.alsoServe(PAGEVIEWS, (url, init) => {
      const userAgent = new Headers(init?.headers).get("User-Agent");
      const path = /^\/api\/rest_v1\/metrics\/pageviews\/top\/([a-z]+)\.wikipedia\/all-access\/(\d{4})\/(\d{2})\/(\d{2})$/.exec(url.pathname);
      if (path === null) return Response.json({ type: "https://mediawiki.org/wiki/HyperSwitch/errors/bad_request" }, { status: 400 });
      const [, wiki, year, month, day] = path;
      this.requests.push({ wiki, day: `${year}-${month}-${day}`, userAgent });
      if (userAgent === null || userAgent.trim() === "") return new Response("Please set a User-Agent", { status: 403 });
      if (this.failWith !== null) return new Response("", { status: this.failWith });
      const articles = this.days.get(`${wiki}/${year}-${month}-${day}`);
      if (articles === undefined) return Response.json({ type: "https://mediawiki.org/wiki/HyperSwitch/errors/not_found", title: "Not found." }, { status: 404 });
      return Response.json({
        items: [{ project: `${wiki}.wikipedia`, access: "all-access", year, month, day, articles: articles.map((a, i) => ({ ...a, rank: i + 1 })) }],
      });
    });
  }

  /**
   * Publishes [wiki]'s most viewed articles of [day], most viewed first, each named as the API names
   * it (`Jailer_3`, underscores for spaces) with its views. Replaces what that day had.
   */
  top(wiki: string, day: string, articles: [string, number][]) {
    this.days.set(`${wiki}/${day}`, articles.map(([article, views]) => ({ article, views })));
  }

  /** The days asked of [wiki], in order, one per request. */
  asked(wiki: string): string[] {
    return this.requests.filter((r) => r.wiki === wiki).map((r) => r.day);
  }
}
