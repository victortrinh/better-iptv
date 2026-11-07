import type { Env } from "./env";
import { randomToken, sha256 } from "./devices";

/**
 * Signing a TV in to an Account (ADR 0015). The TV asks a Sign-in code ([startSignIn]) and shows it
 * with a QR to the sign-in page; the phone types the code and an email there ([sendMagicLink]); the
 * email's magic link, tapped, binds the TV's device token to that email's Account ([useMagicLink]),
 * creating the Account when the email is new; the TV, polling [accountOf], sees it. The cloud keeps
 * the address, the codes for their 10 minutes, and only hashes of the magic tokens and of the IPs
 * that typed wrong codes.
 */

/** A Sign-in code's alphabet: digits and capitals without 0, O, 1 and I, which read alike on a TV. */
export const CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
export const CODE_LENGTH = 6;
export const CODE_LIFETIME_MS = 10 * 60_000;
export const LINK_LIFETIME_MS = 15 * 60_000;
/** Wrong codes one IP may type in [CODE_LIFETIME_MS] before the next try is refused, right or not. */
export const WRONG_TRIES = 5;
/** Magic links one code may send, so a code can't be used to fill someone's inbox. */
export const SENDS_PER_CODE = 5;

/** A fresh Sign-in code for the device [deviceHash], replacing the one it had. */
export async function startSignIn(db: D1Database, deviceHash: string): Promise<{ code: string; expiresIn: number }> {
  const now = Date.now();
  await db.batch([
    db.prepare("DELETE FROM sign_in_code WHERE device_hash = ?1 OR expires_at <= ?2").bind(deviceHash, now),
    db.prepare("DELETE FROM sign_in_miss WHERE at <= ?1").bind(now - CODE_LIFETIME_MS),
    db.prepare("DELETE FROM magic_link WHERE expires_at <= ?1").bind(now - 24 * 3600_000),
  ]);
  // 32^6 is about a billion codes, so a clash with a live one is rare; try again if it happens.
  for (;;) {
    const code = randomCode();
    const inserted = await db
      .prepare("INSERT OR IGNORE INTO sign_in_code (code, device_hash, expires_at) VALUES (?1, ?2, ?3)")
      .bind(code, deviceHash, now + CODE_LIFETIME_MS)
      .run();
    if (inserted.meta.changes === 1) return { code, expiresIn: CODE_LIFETIME_MS / 1000 };
  }
}

/** The email of the Account the device [deviceHash] is signed in to, or null. */
export async function accountOf(db: D1Database, deviceHash: string): Promise<string | null> {
  const row = await db
    .prepare("SELECT account.email FROM device JOIN account ON account.id = device.account_id WHERE device.token_hash = ?1")
    .bind(deviceHash)
    .first<{ email: string }>();
  return row?.email ?? null;
}

/** What the page tells the phone after it typed a code and an address. */
export type SendOutcome =
  | { kind: "sent"; email: string }
  | { kind: "bad-email" }
  | { kind: "wrong-code" }
  | { kind: "too-many-tries" }
  | { kind: "too-many-sends" }
  | { kind: "not-sent" };

/**
 * Emails a magic link to [typedEmail] for the TV showing [typedCode], typed on the page by the phone
 * at [ip]. A code that isn't live counts as a wrong try for that IP; past [WRONG_TRIES] the IP is
 * refused even the right code until the oldest try is [CODE_LIFETIME_MS] old.
 */
export async function sendMagicLink(env: Env, site: string, typedCode: string, typedEmail: string, ip: string): Promise<SendOutcome> {
  const now = Date.now();
  const ipHash = await sha256(`sign-in-ip:${ip}`);
  const misses = await env.DB.prepare("SELECT COUNT(*) AS n FROM sign_in_miss WHERE ip_hash = ?1 AND at > ?2")
    .bind(ipHash, now - CODE_LIFETIME_MS)
    .first<{ n: number }>();
  if ((misses?.n ?? 0) >= WRONG_TRIES) return { kind: "too-many-tries" };
  const email = typedEmail.trim().toLowerCase();
  if (!isEmail(email)) return { kind: "bad-email" };
  const code = normalizeCode(typedCode);
  const live = code === null ? null : await env.DB.prepare("SELECT device_hash, sends FROM sign_in_code WHERE code = ?1 AND expires_at > ?2")
    .bind(code, now)
    .first<{ device_hash: string; sends: number }>();
  if (code === null || live === null) {
    await env.DB.prepare("INSERT INTO sign_in_miss (ip_hash, at) VALUES (?1, ?2)").bind(ipHash, now).run();
    return { kind: "wrong-code" };
  }
  if (live.sends >= SENDS_PER_CODE) return { kind: "too-many-sends" };
  const token = randomToken();
  const tokenHash = await sha256(token);
  await env.DB.batch([
    env.DB.prepare("INSERT INTO magic_link (token_hash, email, code, device_hash, expires_at) VALUES (?1, ?2, ?3, ?4, ?5)")
      .bind(tokenHash, email, code, live.device_hash, now + LINK_LIFETIME_MS),
    env.DB.prepare("UPDATE sign_in_code SET sends = sends + 1 WHERE code = ?1").bind(code),
  ]);
  const link = `${site}/sign-in/link?token=${token}`;
  if (!(await sendEmail(env, site, email, link))) {
    // Nothing went out: the try is given back, and the link can never be used.
    await env.DB.batch([
      env.DB.prepare("DELETE FROM magic_link WHERE token_hash = ?1").bind(tokenHash),
      env.DB.prepare("UPDATE sign_in_code SET sends = sends - 1 WHERE code = ?1").bind(code),
    ]);
    return { kind: "not-sent" };
  }
  return { kind: "sent", email };
}

/** What the page tells the phone after its magic link was tapped. */
export type LinkOutcome = { kind: "signed-in"; email: string } | { kind: "used" } | { kind: "expired" };

/**
 * Signs the TV the link was sent for in to the Account of its email, creating the Account when the
 * email is new. A link works once, for [LINK_LIFETIME_MS], even after its code expired.
 */
export async function useMagicLink(db: D1Database, token: string): Promise<LinkOutcome> {
  const now = Date.now();
  const tokenHash = await sha256(token);
  const link = await db.prepare("SELECT email, device_hash, expires_at, used_at FROM magic_link WHERE token_hash = ?1")
    .bind(tokenHash)
    .first<{ email: string; device_hash: string; expires_at: number; used_at: number | null }>();
  if (link === null || link.used_at !== null) return { kind: "used" };
  if (link.expires_at <= now) return { kind: "expired" };
  // Claimed in one statement, so two taps at once sign in once.
  const claimed = await db.prepare("UPDATE magic_link SET used_at = ?2 WHERE token_hash = ?1 AND used_at IS NULL").bind(tokenHash, now).run();
  if (claimed.meta.changes !== 1) return { kind: "used" };
  await db.batch([
    db.prepare("INSERT OR IGNORE INTO account (email, created_at) VALUES (?1, ?2)").bind(link.email, now),
    db.prepare("UPDATE device SET account_id = (SELECT id FROM account WHERE email = ?1), signed_in_at = ?2 WHERE token_hash = ?3")
      .bind(link.email, now, link.device_hash),
    db.prepare("DELETE FROM sign_in_code WHERE device_hash = ?1").bind(link.device_hash),
  ]);
  return { kind: "signed-in", email: link.email };
}

/** Whether [token] has a magic token's shape. */
export const isMagicToken = (token: unknown): token is string => typeof token === "string" && /^[A-Za-z0-9_-]{43}$/.test(token);

/** [typed] as a Sign-in code, ignoring case and spaces, or null when it can't be one. */
export function normalizeCode(typed: string): string | null {
  const code = typed.replace(/[\s-]/g, "").toUpperCase();
  return code.length === CODE_LENGTH && [...code].every((c) => CODE_ALPHABET.includes(c)) ? code : null;
}

function randomCode(): string {
  // 256 is a multiple of 32, so taking each byte modulo 32 favours no character.
  return [...crypto.getRandomValues(new Uint8Array(CODE_LENGTH))].map((b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
}

function isEmail(email: string): boolean {
  return email.length <= 254 && /^[^\s@<>"]+@[^\s@<>"]+\.[^\s@<>"]+$/.test(email);
}

/** Sends the magic link through the transactional sender (Resend). False when it didn't go out. */
async function sendEmail(env: Env, site: string, to: string, link: string): Promise<boolean> {
  if (!env.RESEND_API_KEY) return false;
  const from = env.EMAIL_FROM ?? `Zugig <sign-in@${new URL(site).hostname}>`;
  const text =
    `Tap this link to sign in your TV to Zugig:\n\n${link}\n\n` +
    "It works once, for the next 15 minutes. If you didn't ask for it, ignore this email: nothing happens without the link.\n";
  const html =
    `<p>Tap this link to sign in your TV to Zugig:</p><p><a href="${link}">Sign in the TV</a></p>` +
    "<p>It works once, for the next 15 minutes. If you didn't ask for it, ignore this email: nothing happens without the link.</p>";
  try {
    const response = await fetch(`${env.RESEND_API}/emails`, {
      method: "POST",
      headers: { Authorization: `Bearer ${env.RESEND_API_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({ from, to: [to], subject: "Sign in to Zugig", text, html }),
    });
    return response.ok;
  } catch {
    return false;
  }
}
