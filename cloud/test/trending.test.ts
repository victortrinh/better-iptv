import { createScheduledController, env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { USER_AGENT } from "../src/title-facts";
import { FakeTmdb } from "./fake-tmdb";
import { FakeWikipedia } from "./fake-wikipedia";

/** Seam 2: Trending's feed and the cron that reads Wikipedia's most viewed articles (#477), run in the Workers runtime with the pageviews API faked. */
let tmdb: FakeTmdb;
let wikipedia: FakeWikipedia;
let token: string;
beforeEach(async () => {
  await env.DB.batch(["device", "trending_day", "trending_wanted"].map((table) => env.DB.prepare(`DELETE FROM ${table}`)));
  tmdb = new FakeTmdb();
  wikipedia = new FakeWikipedia(tmdb);
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-11-07T08:00:00Z"));
  token = await registerDevice();
});
afterEach(() => {
  tmdb.restore();
  vi.useRealTimers();
});

const call = (path: string, body?: unknown, auth: string | null = token) =>
  worker.fetch(
    new Request(`https://cloud.example${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(auth ? { Authorization: `Bearer ${auth}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    env,
  );

async function registerDevice(): Promise<string> {
  const response = await call("/v1/devices", undefined, null);
  return ((await response.json()) as { token: string }).token;
}

interface Feed {
  wikis: { wiki: string; days: { day: string; articles: { name: string; views: number }[] }[] }[];
}

async function feed(language: string): Promise<Feed> {
  const response = await call("/v1/trending", { language });
  expect(response.status).toBe(200);
  return (await response.json()) as Feed;
}

/** One run of the Cron Trigger, as Cloudflare starts it each minute. */
const run = () => worker.scheduled(createScheduledController({ cron: "* * * * *", scheduledTime: Date.now() }), env);
/** The Cron Trigger's next [runs] runs, all at the same moment. */
const fill = async (runs = 5) => {
  for (let i = 0; i < runs; i++) await run();
};

/** The seven days before 2026-11-07, newest first: what the cloud keeps on that day. */
const week = ["2026-11-06", "2026-11-05", "2026-11-04", "2026-11-03", "2026-11-02", "2026-11-01", "2026-10-31"];

/** Publishes a list for each of [days] on [wiki], its articles' views growing with the day so each day's is told apart. */
function publish(wiki: string, days: string[]) {
  days.forEach((day, i) => wikipedia.top(wiki, day, [["Main_Page", 5_000_000], ["Jailer_3", 100_000 + i], ["Black_Doves", 50_000 + i]]));
}

const days = (answer: Feed, wiki: string) => answer.wikis.find((w) => w.wiki === wiki)?.days.map((d) => d.day);

describe("Trending's feed", () => {
  it("answers English's and the asked language's kept days, newest first, each article named as Wikipedia shows it with its views", async () => {
    publish("en", week);
    publish("fr", week);
    await feed("fr");
    await fill();

    const answer = await feed("fr");

    expect(answer.wikis.map((w) => w.wiki)).toEqual(["en", "fr"]);
    expect(days(answer, "en")).toEqual(week);
    expect(days(answer, "fr")).toEqual(week);
    expect(answer.wikis[0].days[0]).toEqual({
      day: "2026-11-06",
      articles: [{ name: "Main Page", views: 5_000_000 }, { name: "Jailer 3", views: 100_000 }, { name: "Black Doves", views: 50_000 }],
    });
  });

  it("answers English alone to a device in English", async () => {
    publish("en", week);
    await feed("en");
    await fill();

    expect((await feed("en")).wikis.map((w) => w.wiki)).toEqual(["en"]);
  });

  it("answers no days before the cron has read any, and reaches no one while answering", async () => {
    const before = vi.mocked(globalThis.fetch).mock.calls.length;

    expect(await feed("fr")).toEqual({ wikis: [{ wiki: "en", days: [] }, { wiki: "fr", days: [] }] });
    expect(vi.mocked(globalThis.fetch).mock.calls.length).toBe(before);
  });

  it("refuses a language that isn't an ISO 639-1 code, and a device without a token", async () => {
    expect((await call("/v1/trending", { language: "French" })).status).toBe(400);
    expect((await call("/v1/trending", {})).status).toBe(400);
    expect((await call("/v1/trending", { language: "fr" }, null)).status).toBe(401);
  });
});

describe("Trending's cron", () => {
  it("reads nothing while no device has asked for Trending", async () => {
    await fill();

    expect(wikipedia.requests).toEqual([]);
  });

  it("wants a wiki once any device has asked in its language, English with any of them", async () => {
    publish("en", week);
    publish("fr", week);
    publish("de", week);
    await feed("en");
    await fill();
    expect(wikipedia.asked("fr")).toEqual([]);

    await feed("fr");
    await fill();
    expect(wikipedia.asked("fr")).toEqual(week);
    expect(wikipedia.asked("de")).toEqual([]);
  });

  it("makes one call per wanted wiki per day, naming itself, however often it runs", async () => {
    publish("en", week);
    publish("fr", week);
    await feed("fr");
    await fill(10);

    expect(wikipedia.asked("en")).toEqual(week);
    expect(wikipedia.asked("fr")).toEqual(week);
    expect(new Set(wikipedia.requests.map((r) => r.userAgent))).toEqual(new Set([USER_AGENT]));

    // The next day: only that day is new.
    vi.setSystemTime(new Date("2026-11-08T08:00:00Z"));
    publish("en", ["2026-11-07"]);
    publish("fr", ["2026-11-07"]);
    await fill(10);
    expect(wikipedia.asked("en")).toEqual([...week, "2026-11-07"]);
    expect(wikipedia.asked("fr")).toEqual([...week, "2026-11-07"]);
  });

  it("keeps seven days and drops the eighth", async () => {
    publish("en", week);
    await feed("en");
    await fill();
    vi.setSystemTime(new Date("2026-11-08T08:00:00Z"));
    publish("en", ["2026-11-07"]);
    await fill();

    expect(days(await feed("en"), "en")).toEqual(["2026-11-07", ...week.slice(0, 6)]);
    const kept = await env.DB.prepare("SELECT day FROM trending_day WHERE wiki = 'en' ORDER BY day").all<{ day: string }>();
    expect(kept.results.map((r) => r.day)).not.toContain("2026-10-31");
    expect(kept.results).toHaveLength(7);
  });

  it("asks again for a day Wikipedia hasn't published yet, but not each minute", async () => {
    // Yesterday's list comes out some hours after midnight UTC.
    vi.setSystemTime(new Date("2026-11-07T00:10:00Z"));
    publish("en", week.slice(1));
    await feed("en");
    await fill();
    expect(wikipedia.asked("en").filter((d) => d === "2026-11-06")).toHaveLength(1);

    vi.setSystemTime(new Date("2026-11-07T00:11:00Z"));
    await fill();
    expect(wikipedia.asked("en").filter((d) => d === "2026-11-06")).toHaveLength(1);

    vi.setSystemTime(new Date("2026-11-07T01:30:00Z"));
    publish("en", ["2026-11-06"]);
    await fill();
    expect(wikipedia.asked("en").filter((d) => d === "2026-11-06")).toHaveLength(2);
    expect(days(await feed("en"), "en")).toEqual(week);
  });

  it("keeps the days it has while Wikipedia is down", async () => {
    publish("en", week);
    await feed("en");
    await fill();
    vi.setSystemTime(new Date("2026-11-08T08:00:00Z"));
    wikipedia.failWith = 503;
    await fill();

    expect(days(await feed("en"), "en")).toEqual(week.slice(0, 6));
  });

  it("stops reading a wiki no device has asked for in 30 days", async () => {
    publish("en", week);
    publish("fr", week);
    await feed("fr");
    await fill();
    vi.setSystemTime(new Date("2026-12-08T08:00:00Z"));
    const before = wikipedia.requests.length;
    await fill();

    expect(wikipedia.requests.length).toBe(before);
  });
});
