import type { FakeTmdb } from "./fake-tmdb";

/** Where Netflix publishes its Top 10 files, as the Worker's `NETFLIX_TOP10` says. */
const TOP10 = "https://www.netflix.com/tudum/top10/data/";

/**
 * Stands in for Netflix's Top 10 files, served through [tmdb]'s fetch stand-in. Each file is sent a
 * small chunk at a time, only as the Worker reads, so [sent] tells how far into a file it read.
 */
export class FakeNetflix {
  /** Each country's rows: name, ISO code, then its weeks newest first, each with its Films and TV names. */
  private readonly countries: { name: string; iso: string; weeks: { week: string; films: string[]; tv: string[] }[] }[] = [];
  /** The worldwide file's rows, newest week first: week, category, name, weekly views. */
  private readonly global: { week: string; category: string; name: string; views: number }[] = [];
  /** How many bytes of each file (`countries`, `global`) the Worker was sent, over every request. */
  readonly sent = { countries: 0, global: 0 };
  /** How many times each file was asked for. */
  readonly asked = { countries: 0, global: 0 };
  /** Answers with this HTTP status instead, as when Netflix can't be reached. */
  failWith: number | null = null;
  private static readonly CHUNK = 512;

  constructor(tmdb: FakeTmdb) {
    tmdb.alsoServe(TOP10, (url) => {
      const file = url.pathname.endsWith("/all-weeks-countries.tsv") ? "countries" : url.pathname.endsWith("/all-weeks-global.tsv") ? "global" : null;
      if (file === null) return new Response("Not found", { status: 404 });
      this.asked[file]++;
      if (this.failWith !== null) return new Response("", { status: this.failWith });
      return new Response(this.stream(file, file === "countries" ? this.countriesFile() : this.globalFile()), {
        headers: { "Content-Type": "text/tab-separated-values" },
      });
    });
  }

  /**
   * A country's lists, newest week first: each week's Films and TV in rank order. A show is named
   * with its season, as Netflix lists it: `["Below", "Below: Limited Series"]`. Replaces the
   * country's lists given before.
   */
  country(name: string, iso: string, ...weeks: { week: string; films: string[]; tv: (string | [string, string])[] }[]) {
    this.countries.splice(0, this.countries.length, ...this.countries.filter((c) => c.iso !== iso));
    this.countries.push({ name, iso, weeks: weeks.map((w) => ({ ...w, tv: w.tv.map((t) => (typeof t === "string" ? `${t}\tN/A` : `${t[0]}\t${t[1]}`)) })) });
    // Like Netflix's file, grouped by country name.
    this.countries.sort((a, b) => a.name.localeCompare(b.name));
  }

  /** One row of the worldwide file, its weeks newest first. [category] is `Films (English)`, `TV (Non-English)` and so on. */
  worldwide(week: string, category: string, name: string, views: number) {
    this.global.push({ week, category, name, views });
  }

  /** The country file's size, in bytes. */
  get countriesSize(): number {
    return new TextEncoder().encode(this.countriesFile()).length;
  }

  private countriesFile(): string {
    const rows = this.countries.flatMap(({ name, iso, weeks }) =>
      weeks.flatMap(({ week, films, tv }) => [
        ...films.map((film, i) => `${name}\t${iso}\t${week}\tFilms\t${i + 1}\t${film}\tN/A\t1`),
        ...tv.map((show, i) => `${name}\t${iso}\t${week}\tTV\t${i + 1}\t${show}\t1`),
      ]),
    );
    return ["country_name\tcountry_iso2\tweek\tcategory\tweekly_rank\tshow_title\tseason_title\tcumulative_weeks_in_top_10", ...rows].join("\n") + "\n";
  }

  private globalFile(): string {
    const rank = new Map<string, number>();
    const rows = this.global.map(({ week, category, name, views }) => {
      const r = (rank.get(week + category) ?? 0) + 1;
      rank.set(week + category, r);
      return `${week}\t${category}\t${r}\t${name}\tN/A\t${views * 2}\t2\t${views}\t1`;
    });
    return ["week\tcategory\tweekly_rank\tshow_title\tseason_title\tweekly_hours_viewed\truntime\tweekly_views\tcumulative_weeks_in_top_10", ...rows].join("\n") + "\n";
  }

  private stream(file: "countries" | "global", text: string): ReadableStream<Uint8Array> {
    const bytes = new TextEncoder().encode(text);
    let at = 0;
    return new ReadableStream(
      {
        // A byte stream, like a fetch's body, so the Worker can read it into a buffer of its own.
        type: "bytes",
        pull: (controller) => {
          if (at >= bytes.length) return controller.close();
          const chunk = bytes.slice(at, at + FakeNetflix.CHUNK);
          at += chunk.length;
          this.sent[file] += chunk.length;
          controller.enqueue(chunk);
        },
      },
      { highWaterMark: 0 },
    );
  }
}
