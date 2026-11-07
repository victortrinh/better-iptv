import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { FakeEmail } from "./fake-email";
import { FakeTmdb } from "./fake-tmdb";

/** Seam 2: signing a TV in to an Account (ADR 0015), with the email sender faked like TMDB is. */
let tmdb: FakeTmdb;
let email: FakeEmail;
beforeEach(async () => {
  await env.DB.batch(["device", "account", "sign_in_code", "sign_in_miss", "magic_link", "unknown_prefix"].map((table) => env.DB.prepare(`DELETE FROM ${table}`)));
  tmdb = new FakeTmdb();
  email = new FakeEmail(tmdb);
});
afterEach(() => {
  tmdb.restore();
  vi.useRealTimers();
});

const PHONE = "203.0.113.7";

const call = (method: string, path: string, body?: unknown, token?: string) =>
  worker.fetch(
    new Request(`https://zugig.example${path}`, {
      method,
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    env,
  );

/** What the phone's browser sends: a page form, from [ip]. */
const form = (path: string, fields: Record<string, string>, ip = PHONE) =>
  worker.fetch(
    new Request(`https://zugig.example${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", "CF-Connecting-IP": ip },
      body: new URLSearchParams(fields).toString(),
    }),
    env,
  );

async function registerDevice(): Promise<string> {
  const response = await call("POST", "/v1/devices");
  expect(response.status).toBe(201);
  return ((await response.json()) as { token: string }).token;
}

async function signInCode(token: string): Promise<{ code: string; url: string; expiresIn: number }> {
  const response = await call("POST", "/v1/sign-in", {}, token);
  expect(response.status).toBe(200);
  return (await response.json()) as { code: string; url: string; expiresIn: number };
}

async function account(token: string): Promise<string | null> {
  const response = await call("POST", "/v1/account", {}, token);
  expect(response.status).toBe(200);
  return ((await response.json()) as { email: string | null }).email;
}

/** The phone types [code] and [address] on the page, then taps the link the email holds; the page's last words. */
async function phoneSignsIn(code: string, address: string): Promise<string> {
  const sent = await form("/sign-in", { code, email: address });
  expect(sent.status).toBe(200);
  const link = email.lastLinkTo(address);
  expect(link).not.toBeNull();
  return tapLink(link!);
}

/** Opens a magic link on the phone and confirms it, as the page asks. */
async function tapLink(link: string): Promise<string> {
  const url = new URL(link);
  const page = await call("GET", url.pathname + url.search);
  expect(page.status).toBe(200);
  const confirm = await form(url.pathname, { token: url.searchParams.get("token")! });
  return confirm.text();
}

describe("the Sign-in code", () => {
  it("is 6 digits and capitals without 0, O, 1 and I, with the page's address for the QR, for 10 minutes", async () => {
    const token = await registerDevice();
    const codes = new Set<string>();
    for (let i = 0; i < 20; i++) {
      const { code, url, expiresIn } = await signInCode(token);
      expect(code).toMatch(/^[2-9A-HJ-NP-Z]{6}$/);
      expect(url).toBe(`https://zugig.example/sign-in?code=${code}`);
      expect(expiresIn).toBe(600);
      codes.add(code);
    }
    expect(codes.size).toBe(20);
  });

  it("names the app's own domain when SITE_URL sets one", async () => {
    const token = await registerDevice();
    const response = await worker.fetch(
      new Request("https://better-iptv-cloud.example.workers.dev/v1/sign-in", { method: "POST", headers: { Authorization: `Bearer ${token}` } }),
      { ...env, SITE_URL: "https://zugig.app" },
    );
    const { code, url } = (await response.json()) as { code: string; url: string };
    expect(url).toBe(`https://zugig.app/sign-in?code=${code}`);
  });

  it("needs a registered device", async () => {
    expect((await call("POST", "/v1/sign-in", {})).status).toBe(401);
    expect((await call("POST", "/v1/account", {})).status).toBe(401);
  });

  it("replaces the device's previous code", async () => {
    const token = await registerDevice();
    const first = await signInCode(token);
    const second = await signInCode(token);
    const page = await (await form("/sign-in", { code: first.code, email: "home@example.com" })).text();
    expect(page).toContain("isn't right");
    expect(email.sent).toHaveLength(0);
    expect(await (await form("/sign-in", { code: second.code, email: "home@example.com" })).text()).toContain("Check your email");
  });

  it("expires after 10 minutes", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-07T12:00:00Z"));
    const { code } = await signInCode(await registerDevice());
    vi.setSystemTime(new Date("2026-11-07T12:10:01Z"));
    const page = await form("/sign-in", { code, email: "home@example.com" });
    expect(page.status).toBe(400);
    expect(await page.text()).toContain("isn't right or has expired");
    expect(email.sent).toHaveLength(0);
  });

  it("refuses a sixth wrong try from the same phone, even with the right code", async () => {
    const { code } = await signInCode(await registerDevice());
    for (let i = 0; i < 5; i++) {
      const wrong = await form("/sign-in", { code: "ZZZZZ" + "23456789"[i], email: "home@example.com" });
      expect(wrong.status).toBe(400);
    }
    const sixth = await form("/sign-in", { code, email: "home@example.com" });
    expect(sixth.status).toBe(429);
    expect(await sixth.text()).toContain("Too many wrong codes");
    expect(email.sent).toHaveLength(0);
    // Another phone isn't held by them.
    expect((await form("/sign-in", { code, email: "home@example.com" }, "198.51.100.2")).status).toBe(200);
  });

  it("forgets wrong tries after 10 minutes", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-07T12:00:00Z"));
    const token = await registerDevice();
    for (let i = 0; i < 5; i++) await form("/sign-in", { code: "ZZZZZ" + "23456789"[i], email: "home@example.com" });
    vi.setSystemTime(new Date("2026-11-07T12:10:01Z"));
    const { code } = await signInCode(token);
    expect((await form("/sign-in", { code, email: "home@example.com" })).status).toBe(200);
  });

  it("reads a code typed in lower case or with spaces", async () => {
    const { code } = await signInCode(await registerDevice());
    const typed = ` ${code.slice(0, 3).toLowerCase()} ${code.slice(3)} `;
    expect((await form("/sign-in", { code: typed, email: "home@example.com" })).status).toBe(200);
  });

  it("sends at most 5 links per code", async () => {
    const { code } = await signInCode(await registerDevice());
    for (let i = 0; i < 5; i++) expect((await form("/sign-in", { code, email: `home${i}@example.com` })).status).toBe(200);
    expect((await form("/sign-in", { code, email: "home9@example.com" })).status).toBe(429);
    expect(email.sent).toHaveLength(5);
  });
});

describe("the sign-in page", () => {
  it("is served on the app's domain with its privacy note and the code from the QR filled in", async () => {
    const response = await call("GET", "/sign-in?code=AB23CD");
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("text/html; charset=utf-8");
    const page = await response.text();
    expect(page).toContain('<form method="post" action="/sign-in">');
    expect(page).toContain('value="AB23CD"');
    expect(page).toContain('type="email"');
    expect(page).toMatch(/We keep your email address.*never keep your TV service's login/s);
    expect(page).not.toContain("<script");
  });

  it("leaves a code that isn't one out of the form", async () => {
    const page = await (await call("GET", '/sign-in?code="><b>x')).text();
    expect(page).not.toContain("<b>x");
    expect(page).toContain('value=""');
  });

  it("answers the root with the page", async () => {
    const response = await call("GET", "/");
    expect(response.status).toBe(302);
    expect(response.headers.get("Location")).toBe("/sign-in");
  });

  it("refuses an address that isn't one", async () => {
    const { code } = await signInCode(await registerDevice());
    const page = await form("/sign-in", { code, email: "not an address" });
    expect(page.status).toBe(400);
    expect(await page.text()).toContain("email address");
    expect(email.sent).toHaveLength(0);
  });

  it("says so when the email can't go out, and the code still works after", async () => {
    const { code } = await signInCode(await registerDevice());
    email.failWith = 500;
    const page = await form("/sign-in", { code, email: "home@example.com" });
    expect(page.status).toBe(503);
    expect(await page.text()).toContain("couldn't send");
    email.failWith = null;
    expect((await form("/sign-in", { code, email: "home@example.com" })).status).toBe(200);
  });
});

describe("the magic link", () => {
  it("is emailed once, from the app's sender, to the address typed", async () => {
    const { code } = await signInCode(await registerDevice());
    const page = await form("/sign-in", { code, email: "Home@Example.com" });
    expect(await page.text()).toContain("Check your email");
    expect(email.sent).toHaveLength(1);
    const [message] = email.sent;
    expect(message.to).toEqual(["home@example.com"]);
    expect(message.from).toBe("Zugig <sign-in@zugig.example>");
    expect(message.subject).toBe("Sign in to Zugig");
    expect(message.authorization).toBe("Bearer test-resend-key");
    expect(email.lastLinkTo("home@example.com")).toMatch(/^https:\/\/zugig\.example\/sign-in\/link\?token=[A-Za-z0-9_-]{43}$/);
  });

  it("asks for a tap before signing in, so a mail scanner opening it signs nobody in", async () => {
    const token = await registerDevice();
    const { code } = await signInCode(token);
    await form("/sign-in", { code, email: "home@example.com" });
    const link = new URL(email.lastLinkTo("home@example.com")!);
    const page = await (await call("GET", link.pathname + link.search)).text();
    expect(page).toContain('<form method="post" action="/sign-in/link">');
    expect(await account(token)).toBeNull();
  });

  it("signs the TV in to a new Account, and the TV's next poll says so", async () => {
    const token = await registerDevice();
    expect(await account(token)).toBeNull();
    const { code } = await signInCode(token);
    expect(await phoneSignsIn(code, "home@example.com")).toContain("The TV is signed in as home@example.com");
    expect(await account(token)).toBe("home@example.com");
    const accounts = await env.DB.prepare("SELECT email FROM account").all<{ email: string }>();
    expect(accounts.results).toEqual([{ email: "home@example.com" }]);
  });

  it("binds a second TV to the same Account", async () => {
    const living = await registerDevice();
    const bedroom = await registerDevice();
    await phoneSignsIn((await signInCode(living)).code, "home@example.com");
    await phoneSignsIn((await signInCode(bedroom)).code, "HOME@example.com");
    expect(await account(bedroom)).toBe("home@example.com");
    const bound = await env.DB.prepare("SELECT COUNT(DISTINCT account_id) AS accounts, COUNT(*) AS devices FROM device WHERE account_id IS NOT NULL").first();
    expect(bound).toEqual({ accounts: 1, devices: 2 });
  });

  it("is refused when used twice, with a plain message", async () => {
    const token = await registerDevice();
    await form("/sign-in", { code: (await signInCode(token)).code, email: "home@example.com" });
    const link = email.lastLinkTo("home@example.com")!;
    expect(await tapLink(link)).toContain("signed in");
    const url = new URL(link);
    const again = await form(url.pathname, { token: url.searchParams.get("token")! });
    expect(again.status).toBe(410);
    expect(await again.text()).toContain("This link was already used");
  });

  it("is refused after 15 minutes, with a plain message", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-07T12:00:00Z"));
    const token = await registerDevice();
    await form("/sign-in", { code: (await signInCode(token)).code, email: "home@example.com" });
    const url = new URL(email.lastLinkTo("home@example.com")!);
    vi.setSystemTime(new Date("2026-11-07T12:15:01Z"));
    const late = await form(url.pathname, { token: url.searchParams.get("token")! });
    expect(late.status).toBe(410);
    expect(await late.text()).toContain("This link has expired");
    expect(await account(token)).toBeNull();
  });

  it("still works after the code's 10 minutes, within its own 15", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-07T12:00:00Z"));
    const token = await registerDevice();
    await form("/sign-in", { code: (await signInCode(token)).code, email: "home@example.com" });
    vi.setSystemTime(new Date("2026-11-07T12:14:00Z"));
    expect(await tapLink(email.lastLinkTo("home@example.com")!)).toContain("signed in as home@example.com");
    expect(await account(token)).toBe("home@example.com");
  });

  it("is refused when it is no link the cloud sent", async () => {
    const page = await form("/sign-in/link", { token: "A".repeat(43) });
    expect(page.status).toBe(410);
    expect(await page.text()).toContain("This link was already used or isn't one we sent");
  });

  it("is kept only as a hash, beside the address", async () => {
    await form("/sign-in", { code: (await signInCode(await registerDevice())).code, email: "home@example.com" });
    const token = new URL(email.lastLinkTo("home@example.com")!).searchParams.get("token")!;
    const rows = await env.DB.prepare("SELECT * FROM magic_link").all<Record<string, unknown>>();
    expect(rows.results).toHaveLength(1);
    expect(JSON.stringify(rows.results)).not.toContain(token);
    expect(rows.results[0].email).toBe("home@example.com");
    expect(JSON.stringify(await env.DB.prepare("SELECT * FROM sign_in_miss").all())).not.toContain(PHONE);
  });
});

describe("a signed-in device", () => {
  it("keeps its device token, and every endpoint keeps answering it", async () => {
    const token = await registerDevice();
    await phoneSignsIn((await signInCode(token)).code, "home@example.com");
    const report = await call("POST", "/v1/unknown-prefixes", { provider: "a".repeat(64), prefixes: [{ prefix: "BR", listingCount: 3, example: "BR - Cold Pursuit (2019)" }] }, token);
    expect(report.status).toBe(204);
    expect((await call("POST", "/v1/games", { leagues: ["nhl"] }, token)).status).toBe(200);
  });

  it("leaves a device that never signs in as it was", async () => {
    const token = await registerDevice();
    expect(await account(token)).toBeNull();
    const row = await env.DB.prepare("SELECT account_id, signed_in_at FROM device").first();
    expect(row).toEqual({ account_id: null, signed_in_at: null });
  });
});
