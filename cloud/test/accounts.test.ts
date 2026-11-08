import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { FakeEmail } from "./fake-email";
import { FakeWeb } from "./fake-web";

/** Seam 2: signing a TV in to an Account (ADR 0015), with the email sender faked. */
let web: FakeWeb;
let email: FakeEmail;
beforeEach(async () => {
  await env.DB.batch(["device", "account", "sign_in_code", "sign_in_miss", "magic_link", "delete_link", "delete_request", "unknown_prefix", "synced"].map((table) => env.DB.prepare(`DELETE FROM ${table}`)));
  web = new FakeWeb();
  email = new FakeEmail(web);
});
afterEach(() => {
  web.restore();
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

async function signInCode(token: string, model?: string): Promise<{ code: string; url: string; expiresIn: number }> {
  const response = await call("POST", "/v1/sign-in", model === undefined ? {} : { model }, token);
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

interface ListedDevice {
  id: string;
  model: string | null;
  signedInAt: string;
  thisDevice: boolean;
}

async function devices(token: string): Promise<ListedDevice[]> {
  const response = await call("POST", "/v1/account/devices", {}, token);
  expect(response.status).toBe(200);
  return ((await response.json()) as { devices: ListedDevice[] }).devices;
}

/** A registered TV of [model], signed in to [address]'s Account. */
async function signedInTv(address: string, model?: string): Promise<string> {
  const token = await registerDevice();
  await phoneSignsIn((await signInCode(token, model)).code, address);
  return token;
}

describe("the Account's device list", () => {
  it("lists every device signed in to the Account with its model and sign-in date, this one marked", async () => {
    vi.useFakeTimers({ now: Date.parse("2026-11-03T20:00:00Z"), toFake: ["Date"] });
    const living = await signedInTv("home@example.com", "Fire TV Stick 4K Max");
    vi.setSystemTime(Date.parse("2026-11-05T09:30:00Z"));
    const bedroom = await signedInTv("home@example.com", "Fire TV Cube");
    await signedInTv("elsewhere@example.com", "Chromecast with Google TV");
    await registerDevice();

    const listed = await devices(living);
    expect(listed.map(({ model, signedInAt, thisDevice }) => ({ model, signedInAt, thisDevice }))).toEqual([
      { model: "Fire TV Stick 4K Max", signedInAt: "2026-11-03T20:00:00.000Z", thisDevice: true },
      { model: "Fire TV Cube", signedInAt: "2026-11-05T09:30:00.000Z", thisDevice: false },
    ]);
    expect((await devices(bedroom)).map((d) => d.thisDevice)).toEqual([false, true]);
    expect((await devices(bedroom)).map((d) => d.id)).toEqual(listed.map((d) => d.id));
  });

  it("names a device by an opaque id, never its token or the token's hash", async () => {
    const token = await signedInTv("home@example.com", "Fire TV Stick 4K Max");
    const [listed] = await devices(token);
    const hash = (await env.DB.prepare("SELECT token_hash FROM device").first<{ token_hash: string }>())!.token_hash;
    expect(listed.id).toMatch(/^[0-9a-f]{16}$/);
    expect(hash).not.toContain(listed.id);
    expect(listed.id).not.toBe(token.slice(0, 16));
  });

  it("is empty for a device that isn't signed in", async () => {
    expect(await devices(await registerDevice())).toEqual([]);
  });

  it("keeps the model from the sign-in, trimmed to a line, and none when the TV sent none", async () => {
    const plain = await signedInTv("home@example.com");
    await signedInTv("home@example.com", `  ${"Fire TV ".repeat(20)}\n`);
    const [first, second] = await devices(plain);
    expect(first.model).toBeNull();
    expect(second.model!.length).toBeLessThanOrEqual(60);
    expect(second.model).not.toMatch(/\s$|\n/);
  });

  it("refuses a sign-in whose model isn't text", async () => {
    const token = await registerDevice();
    expect((await call("POST", "/v1/sign-in", { model: 42 }, token)).status).toBe(400);
  });
});

describe("signing a device out", () => {
  it("of this device clears its link at once", async () => {
    const token = await signedInTv("home@example.com", "Fire TV Stick 4K Max");
    const out = await call("POST", "/v1/account/sign-out", {}, token);
    expect(out.status).toBe(204);
    expect(out.headers.get("Zugig-Account")).toBe("none");
    expect(await account(token)).toBeNull();
    expect(await env.DB.prepare("SELECT account_id, signed_in_at, model FROM device").first()).toEqual({ account_id: null, signed_in_at: null, model: null });
  });

  it("of another device clears it in the cloud, and that device hears it on its next request", async () => {
    const living = await signedInTv("home@example.com", "Fire TV Stick 4K Max");
    const bedroom = await signedInTv("home@example.com", "Fire TV Cube");
    const before = await call("POST", "/v1/games", { leagues: ["nhl"] }, bedroom);
    expect(before.headers.get("Zugig-Account")).toBe("signed-in");

    const other = (await devices(living)).find((d) => !d.thisDevice)!;
    expect((await call("POST", "/v1/account/sign-out", { device: other.id }, living)).status).toBe(204);

    const after = await call("POST", "/v1/games", { leagues: ["nhl"] }, bedroom);
    expect(after.status).toBe(200);
    expect(after.headers.get("Zugig-Account")).toBe("none");
    expect(await account(bedroom)).toBeNull();
    expect(await account(living)).toBe("home@example.com");
    expect((await devices(living)).map((d) => d.model)).toEqual(["Fire TV Stick 4K Max"]);
  });

  it("can't reach a device on another Account", async () => {
    const mine = await signedInTv("home@example.com");
    const theirs = await signedInTv("elsewhere@example.com");
    const [their] = await devices(theirs);
    expect((await call("POST", "/v1/account/sign-out", { device: their.id }, mine)).status).toBe(404);
    expect(await account(theirs)).toBe("elsewhere@example.com");
  });

  it("is refused without being signed in, or with an id that isn't one", async () => {
    expect((await call("POST", "/v1/account/sign-out", {}, await registerDevice())).status).toBe(404);
    const mine = await signedInTv("home@example.com");
    expect((await call("POST", "/v1/account/sign-out", { device: 7 }, mine)).status).toBe(400);
  });

  it("leaves the device registered, so it signs in again with the same token", async () => {
    const token = await signedInTv("home@example.com");
    await call("POST", "/v1/account/sign-out", {}, token);
    await phoneSignsIn((await signInCode(token)).code, "home@example.com");
    expect(await account(token)).toBe("home@example.com");
  });

  it("says on every answer to a device that never signed in that it isn't", async () => {
    const token = await registerDevice();
    expect((await call("POST", "/v1/games", { leagues: ["nhl"] }, token)).headers.get("Zugig-Account")).toBe("none");
  });
});

/** The phone asks for a "Delete my account" link for [address] on the page; the page's answer. */
const askToDelete = (address: string, ip = PHONE) => form("/delete-account", { email: address }, ip);

/** Opens a delete link on the phone and confirms it; the page's answer. */
async function tapDeleteLink(link: string): Promise<Response> {
  const url = new URL(link);
  const page = await call("GET", url.pathname + url.search);
  expect(page.status).toBe(200);
  expect(await page.text()).toContain('<form method="post" action="/delete-account/link">');
  return form(url.pathname, { token: url.searchParams.get("token")! });
}

describe("Delete my account", () => {
  it("is offered from the sign-in page, with the privacy note", async () => {
    expect(await (await call("GET", "/sign-in")).text()).toContain('href="/delete-account"');
    const page = await call("GET", "/delete-account");
    expect(page.status).toBe(200);
    const html = await page.text();
    expect(html).toContain('<form method="post" action="/delete-account">');
    expect(html).toContain("never keep your TV service's login");
  });

  it("emails a link, and tapping it deletes the Account; every device goes anonymous at its next contact", async () => {
    const living = await signedInTv("home@example.com", "Fire TV Stick 4K Max");
    const bedroom = await signedInTv("home@example.com", "Fire TV Cube");
    const other = await signedInTv("elsewhere@example.com");

    const asked = await askToDelete("Home@Example.com");
    expect(asked.status).toBe(200);
    expect(await asked.text()).toContain("home@example.com");
    const link = email.lastDeleteLinkTo("home@example.com");
    expect(link).toMatch(/^https:\/\/zugig\.example\/delete-account\/link\?token=[A-Za-z0-9_-]{43}$/);
    // Asking alone deletes nothing.
    expect(await account(living)).toBe("home@example.com");

    const done = await tapDeleteLink(link!);
    expect(done.status).toBe(200);
    expect(await done.text()).toContain("deleted");

    for (const tv of [living, bedroom]) {
      const next = await call("POST", "/v1/games", { leagues: ["nhl"] }, tv);
      expect(next.status).toBe(200);
      expect(next.headers.get("Zugig-Account")).toBe("none");
      expect(await account(tv)).toBeNull();
    }
    expect(await account(other)).toBe("elsewhere@example.com");
    expect(await env.DB.prepare("SELECT email FROM account").all()).toMatchObject({ results: [{ email: "elsewhere@example.com" }] });
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM magic_link WHERE email = 'home@example.com'").first()).toEqual({ n: 0 });
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM device WHERE model IS NOT NULL").first()).toEqual({ n: 0 });
  });

  it("asks for a tap before deleting, so a mail scanner opening the link deletes nothing", async () => {
    const tv = await signedInTv("home@example.com");
    await askToDelete("home@example.com");
    const url = new URL(email.lastDeleteLinkTo("home@example.com")!);
    expect((await call("GET", url.pathname + url.search)).status).toBe(200);
    expect(await account(tv)).toBe("home@example.com");
  });

  it("works once, for 15 minutes", async () => {
    await signedInTv("home@example.com");
    await askToDelete("home@example.com");
    const link = email.lastDeleteLinkTo("home@example.com")!;
    expect((await tapDeleteLink(link)).status).toBe(200);
    expect((await tapDeleteLink(link)).status).toBe(410);

    vi.useFakeTimers({ now: Date.now(), toFake: ["Date"] });
    await signedInTv("home@example.com");
    await askToDelete("home@example.com");
    vi.setSystemTime(Date.now() + 15 * 60_000 + 1);
    const late = await tapDeleteLink(email.lastDeleteLinkTo("home@example.com")!);
    expect(late.status).toBe(410);
    expect(await late.text()).toContain("expired");
  });

  it("answers the same for an address with no Account, and emails nobody", async () => {
    await signedInTv("home@example.com");
    const sentBefore = email.sent.length;
    const asked = await askToDelete("nobody@example.com");
    expect(asked.status).toBe(200);
    expect(await asked.text()).toContain("nobody@example.com");
    expect(email.sent.length).toBe(sentBefore);
  });

  it("sends at most 3 links an address and 5 a phone in 15 minutes", async () => {
    await signedInTv("home@example.com");
    for (let i = 0; i < 3; i++) expect((await askToDelete("home@example.com")).status).toBe(200);
    expect((await askToDelete("home@example.com", "198.51.100.1")).status).toBe(429);
    for (let i = 0; i < 2; i++) expect((await askToDelete(`x${i}@example.com`)).status).toBe(200);
    expect((await askToDelete("y@example.com")).status).toBe(429);
  });

  it("refuses an address that isn't one", async () => {
    expect((await askToDelete("not an address")).status).toBe(400);
  });

  it("keeps only hashes of the link and of who asked", async () => {
    await signedInTv("home@example.com");
    await askToDelete("home@example.com");
    const token = new URL(email.lastDeleteLinkTo("home@example.com")!).searchParams.get("token")!;
    const kept = JSON.stringify([await env.DB.prepare("SELECT * FROM delete_link").all(), await env.DB.prepare("SELECT * FROM delete_request").all()]);
    expect(kept).not.toContain(token);
    expect(kept).not.toContain(PHONE);
    expect(kept).not.toContain("home@example.com");
  });
});

/** A pull of everything the Account keeps, by [tv]: its household PIN entry, if any. */
async function pinOf(tv: string): Promise<{ value: string | null; at: number } | undefined> {
  const response = await call("POST", "/v1/sync", { cursor: 0, provider: null, progress: [], entries: [] }, tv);
  expect(response.status).toBe(200);
  const { entries } = (await response.json()) as { entries: { kind: string; key: string; value: string | null; at: number }[] };
  return entries.find((e) => e.kind === "household" && e.key === "pin");
}

describe("Reset the Household PIN (#440)", () => {
  const HASH = "pbkdf2-sha256:10000:c2FsdA:aGFzaA";

  async function withPin(address: string): Promise<string> {
    const tv = await signedInTv(address);
    const at = Date.now() - 60_000;
    const pushed = await call("POST", "/v1/sync", { cursor: 0, provider: null, progress: [], entries: [{ kind: "household", profile: null, key: "pin", value: HASH, at }] }, tv);
    expect(pushed.status).toBe(200);
    return tv;
  }

  async function tapResetLink(link: string): Promise<Response> {
    const url = new URL(link);
    const page = await call("GET", url.pathname + url.search);
    expect(page.status).toBe(200);
    expect(await page.text()).toContain('<form method="post" action="/reset-pin/link">');
    return form(url.pathname, { token: url.searchParams.get("token")! });
  }

  it("is offered from the sign-in page", async () => {
    expect(await (await call("GET", "/sign-in")).text()).toContain('href="/reset-pin"');
    const page = await call("GET", "/reset-pin");
    expect(page.status).toBe(200);
    expect(await page.text()).toContain('<form method="post" action="/reset-pin">');
  });

  it("emails a link, and tapping it clears the PIN every TV of the Account pulls next", async () => {
    const living = await withPin("home@example.com");
    const neighbours = await withPin("neighbours@example.com");
    expect((await form("/reset-pin", { email: "Home@Example.com" })).status).toBe(200);
    const link = email.lastResetPinLinkTo("home@example.com");
    expect(link).toMatch(/^https:\/\/zugig\.example\/reset-pin\/link\?token=[A-Za-z0-9_-]{43}$/);
    // Asking, and opening the link, change nothing.
    expect((await pinOf(living))?.value).toBe(HASH);

    const done = await tapResetLink(link!);
    expect(done.status).toBe(200);
    expect(await done.text()).toContain("reset");
    const pin = await pinOf(living);
    expect(pin?.value).toBeNull();
    expect(pin!.at).toBeGreaterThan(Date.now() - 60_000);
    expect((await pinOf(neighbours))?.value).toBe(HASH);
    // The Account is still there.
    expect(await account(living)).toBe("home@example.com");
  });

  it("works once, and a delete link doesn't reset nor a reset link delete", async () => {
    const tv = await withPin("home@example.com");
    await form("/reset-pin", { email: "home@example.com" });
    const link = email.lastResetPinLinkTo("home@example.com")!;
    const token = new URL(link).searchParams.get("token")!;
    expect((await form("/delete-account/link", { token })).status).toBe(410);
    expect(await account(tv)).toBe("home@example.com");
    expect((await tapResetLink(link)).status).toBe(200);
    expect((await tapResetLink(link)).status).toBe(410);

    await askToDelete("home@example.com");
    const deleteToken = new URL(email.lastDeleteLinkTo("home@example.com")!).searchParams.get("token")!;
    expect((await form("/reset-pin/link", { token: deleteToken })).status).toBe(410);
  });

  it("answers the same for an address with no Account, and emails nobody", async () => {
    const sentBefore = email.sent.length;
    const asked = await form("/reset-pin", { email: "nobody@example.com" });
    expect(asked.status).toBe(200);
    expect(email.sent.length).toBe(sentBefore);
    expect((await form("/reset-pin", { email: "not an address" })).status).toBe(400);
  });
});
