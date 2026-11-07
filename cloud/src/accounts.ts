import type { Env } from "./env";
import { randomToken, sha256 } from "./devices";
import { resetHouseholdPin } from "./sync";

/**
 * Signing a TV in to an Account (ADR 0015). The TV asks a Sign-in code ([startSignIn]) and shows it
 * with a QR to the sign-in page; the phone types the code and an email there ([sendMagicLink]); the
 * email's magic link, tapped, binds the TV's device token to that email's Account ([useMagicLink]),
 * creating the Account when the email is new; the TV, polling [accountOf], sees it. The Account lists
 * its devices ([devicesOf]), any of which signs any out ([signOut]), and is deleted behind a magic
 * link of its own ([requestDeletion], [useDeleteLink]); its Household PIN is reset behind another
 * ([requestDeletion] with "reset-pin", [useResetPinLink], #440). The cloud keeps
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

/** A fresh Sign-in code for the device [deviceHash], a [model] (its name in the device list), replacing the one it had. */
export async function startSignIn(db: D1Database, deviceHash: string, model: string | null = null): Promise<{ code: string; expiresIn: number }> {
  const now = Date.now();
  await db.batch([
    db.prepare("DELETE FROM sign_in_code WHERE device_hash = ?1 OR expires_at <= ?2").bind(deviceHash, now),
    db.prepare("DELETE FROM sign_in_miss WHERE at <= ?1").bind(now - CODE_LIFETIME_MS),
    db.prepare("DELETE FROM magic_link WHERE expires_at <= ?1").bind(now - 24 * 3600_000),
    db.prepare("DELETE FROM delete_link WHERE expires_at <= ?1").bind(now - 24 * 3600_000),
    db.prepare("DELETE FROM delete_request WHERE at <= ?1").bind(now - LINK_LIFETIME_MS),
  ]);
  // 32^6 is about a billion codes, so a clash with a live one is rare; try again if it happens.
  for (;;) {
    const code = randomCode();
    const inserted = await db
      .prepare("INSERT OR IGNORE INTO sign_in_code (code, device_hash, expires_at, model) VALUES (?1, ?2, ?3, ?4)")
      .bind(code, deviceHash, now + CODE_LIFETIME_MS, model)
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
  const live = code === null ? null : await env.DB.prepare("SELECT device_hash, sends, model FROM sign_in_code WHERE code = ?1 AND expires_at > ?2")
    .bind(code, now)
    .first<{ device_hash: string; sends: number; model: string | null }>();
  if (code === null || live === null) {
    await env.DB.prepare("INSERT INTO sign_in_miss (ip_hash, at) VALUES (?1, ?2)").bind(ipHash, now).run();
    return { kind: "wrong-code" };
  }
  if (live.sends >= SENDS_PER_CODE) return { kind: "too-many-sends" };
  const token = randomToken();
  const tokenHash = await sha256(token);
  await env.DB.batch([
    env.DB.prepare("INSERT INTO magic_link (token_hash, email, code, device_hash, expires_at, model) VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
      .bind(tokenHash, email, code, live.device_hash, now + LINK_LIFETIME_MS, live.model),
    env.DB.prepare("UPDATE sign_in_code SET sends = sends + 1 WHERE code = ?1").bind(code),
  ]);
  const link = `${site}/sign-in/link?token=${token}`;
  if (!(await sendEmail(env, site, email, "Sign in to Zugig", signInWords(link)))) {
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
  const link = await db.prepare("SELECT email, device_hash, expires_at, used_at, model FROM magic_link WHERE token_hash = ?1")
    .bind(tokenHash)
    .first<{ email: string; device_hash: string; expires_at: number; used_at: number | null; model: string | null }>();
  if (link === null || link.used_at !== null) return { kind: "used" };
  if (link.expires_at <= now) return { kind: "expired" };
  // Claimed in one statement, so two taps at once sign in once.
  const claimed = await db.prepare("UPDATE magic_link SET used_at = ?2 WHERE token_hash = ?1 AND used_at IS NULL").bind(tokenHash, now).run();
  if (claimed.meta.changes !== 1) return { kind: "used" };
  await db.batch([
    db.prepare("INSERT OR IGNORE INTO account (email, created_at) VALUES (?1, ?2)").bind(link.email, now),
    db.prepare("UPDATE device SET account_id = (SELECT id FROM account WHERE email = ?1), signed_in_at = ?2, model = ?4 WHERE token_hash = ?3")
      .bind(link.email, now, link.device_hash, link.model),
    db.prepare("DELETE FROM sign_in_code WHERE device_hash = ?1").bind(link.device_hash),
  ]);
  return { kind: "signed-in", email: link.email };
}

/** One device in the Account's device list (#434), named by an [id] that is neither its token nor the token's hash. */
export interface ListedDevice {
  id: string;
  model: string | null;
  signedInAt: string;
  thisDevice: boolean;
}

/** The longest model name kept. */
export const MAX_MODEL = 60;

/** [model] as the device list keeps it: one line, at most [MAX_MODEL] characters; null when there's nothing. */
export function modelName(model: unknown): string | null {
  if (typeof model !== "string") return null;
  const line = model.replace(/[\p{Cc}\s]+/gu, " ").trim().slice(0, MAX_MODEL).trim();
  return line === "" ? null : line;
}

/** The devices on the Account of the device `?1`. */
export const DEVICES_QUERY =
  "SELECT token_hash, model, signed_in_at FROM device WHERE account_id = (SELECT account_id FROM device WHERE token_hash = ?1) ORDER BY signed_in_at, token_hash";

/** The devices signed in to the Account of the device [deviceHash], by when they signed in; none when it is signed in to none. */
export async function devicesOf(db: D1Database, deviceHash: string): Promise<ListedDevice[]> {
  const rows = await db
    .prepare(DEVICES_QUERY)
    .bind(deviceHash)
    .all<{ token_hash: string; model: string | null; signed_in_at: number }>();
  return Promise.all(
    rows.results.map(async (row) => ({
      id: await deviceId(row.token_hash),
      model: row.model,
      signedInAt: new Date(row.signed_in_at).toISOString(),
      thisDevice: row.token_hash === deviceHash,
    })),
  );
}

/**
 * Signs out the device listed as [id] on the Account of the device [deviceHash], or that device
 * itself without an [id]: its link to the Account is cleared, its token stays registered. Which it
 * was, or null when no such device is on the Account.
 */
export async function signOut(db: D1Database, deviceHash: string, id?: string): Promise<"this" | "other" | null> {
  const target = id === undefined ? deviceHash : await hashOf(db, deviceHash, id);
  if (target === undefined) return null;
  const out = await db
    .prepare(
      "UPDATE device SET account_id = NULL, signed_in_at = NULL, model = NULL WHERE token_hash = ?1 AND account_id IS NOT NULL AND account_id = (SELECT account_id FROM device WHERE token_hash = ?2)",
    )
    .bind(target, deviceHash)
    .run();
  if (out.meta.changes !== 1) return null;
  return target === deviceHash ? "this" : "other";
}

/** The token hash of the device listed as [id] on the Account of the device [deviceHash]. */
async function hashOf(db: D1Database, deviceHash: string, id: string): Promise<string | undefined> {
  const rows = await db
    .prepare("SELECT token_hash FROM device WHERE account_id = (SELECT account_id FROM device WHERE token_hash = ?1)")
    .bind(deviceHash)
    .all<{ token_hash: string }>();
  for (const row of rows.results) if ((await deviceId(row.token_hash)) === id) return row.token_hash;
  return undefined;
}

/** A device's id in the device list: derived from its token's hash, from which it can't be turned back. */
const deviceId = async (tokenHash: string) => (await sha256(`device-id:${tokenHash}`)).slice(0, 16);

/** Whether [id] has a listed device id's shape. */
export const isDeviceId = (id: unknown): id is string => typeof id === "string" && /^[0-9a-f]{16}$/.test(id);

/** What the page tells the phone after it asked to delete the Account of an address. */
export type DeletionOutcome = { kind: "sent"; email: string } | { kind: "bad-email" } | { kind: "too-many" } | { kind: "not-sent" };

/** Delete links one phone may ask for in [LINK_LIFETIME_MS], and one address may be sent. */
export const DELETE_ASKS_PER_IP = 5;
export const DELETE_SENDS_PER_EMAIL = 3;

/** What an Account's own magic link does: delete the Account, or reset its Household PIN. */
export type AccountLinkPurpose = "delete" | "reset-pin";

/**
 * Emails a "Delete my account" link (or, for [purpose] "reset-pin", a "Reset the Household PIN" one)
 * to [typedEmail], asked for on the page by the phone at [ip], when an Account uses it. The page
 * answers the same whether one does, so it can't be used to learn who has an Account; an address
 * with none is emailed nothing. Both purposes count towards the same limits.
 */
export async function requestDeletion(env: Env, site: string, typedEmail: string, ip: string, purpose: AccountLinkPurpose = "delete"): Promise<DeletionOutcome> {
  const now = Date.now();
  const email = typedEmail.trim().toLowerCase();
  if (!isEmail(email)) return { kind: "bad-email" };
  const ipHash = await sha256(`delete-ip:${ip}`);
  const emailHash = await sha256(`delete-email:${email}`);
  const since = now - LINK_LIFETIME_MS;
  const [byIp, byEmail] = await env.DB.batch<{ n: number }>([
    env.DB.prepare("SELECT COUNT(*) AS n FROM delete_request WHERE ip_hash = ?1 AND at > ?2").bind(ipHash, since),
    env.DB.prepare("SELECT COUNT(*) AS n FROM delete_request WHERE email_hash = ?1 AND at > ?2").bind(emailHash, since),
  ]);
  if (byIp.results[0].n >= DELETE_ASKS_PER_IP || byEmail.results[0].n >= DELETE_SENDS_PER_EMAIL) return { kind: "too-many" };
  await env.DB.prepare("INSERT INTO delete_request (ip_hash, email_hash, at) VALUES (?1, ?2, ?3)").bind(ipHash, emailHash, now).run();
  const owner = await env.DB.prepare("SELECT id FROM account WHERE email = ?1").bind(email).first<{ id: number }>();
  if (owner === null) return { kind: "sent", email };
  const token = randomToken();
  const tokenHash = await sha256(token);
  await env.DB.prepare("INSERT INTO delete_link (token_hash, account_id, expires_at, purpose) VALUES (?1, ?2, ?3, ?4)")
    .bind(tokenHash, owner.id, now + LINK_LIFETIME_MS, purpose)
    .run();
  const sent = purpose === "delete"
    ? await sendEmail(env, site, email, "Delete your Zugig account", deleteWords(`${site}/delete-account/link?token=${token}`))
    : await sendEmail(env, site, email, "Reset your Household PIN", resetPinWords(`${site}/reset-pin/link?token=${token}`));
  if (!sent) {
    await env.DB.prepare("DELETE FROM delete_link WHERE token_hash = ?1").bind(tokenHash).run();
    return { kind: "not-sent" };
  }
  return { kind: "sent", email };
}

/** What the page tells the phone after its delete link was tapped. */
export type DeleteOutcome = { kind: "deleted" } | { kind: "used" } | { kind: "expired" };

/**
 * Deletes the Account the link was sent for and everything it owns, and unbinds every device signed
 * in to it, each of which goes anonymous at its next request. A link works once, for [LINK_LIFETIME_MS].
 */
export async function useDeleteLink(db: D1Database, token: string): Promise<DeleteOutcome> {
  const tokenHash = await sha256(token);
  const claim = await claimLink(db, tokenHash, "delete");
  if (typeof claim !== "number") return claim;
  const id = claim;
  // Devices first: they reference the Account. Every table the Account owns is emptied here
  // (history and profiles join this list when they move to the cloud).
  await db.batch([
    db.prepare("UPDATE device SET account_id = NULL, signed_in_at = NULL, model = NULL WHERE account_id = ?1").bind(id),
    db.prepare("DELETE FROM magic_link WHERE email = (SELECT email FROM account WHERE id = ?1)").bind(id),
    db.prepare("DELETE FROM delete_link WHERE account_id = ?1 AND token_hash != ?2").bind(id, tokenHash),
    db.prepare("DELETE FROM account WHERE id = ?1").bind(id),
  ]);
  return { kind: "deleted" };
}

/** Claims the unused, unexpired link of [purpose] whose token hashes to [tokenHash], and says whose Account it is for. */
async function claimLink(db: D1Database, tokenHash: string, purpose: AccountLinkPurpose): Promise<number | { kind: "used" } | { kind: "expired" }> {
  const now = Date.now();
  const link = await db.prepare("SELECT account_id, expires_at, used_at FROM delete_link WHERE token_hash = ?1 AND purpose = ?2")
    .bind(tokenHash, purpose)
    .first<{ account_id: number; expires_at: number; used_at: number | null }>();
  if (link === null || link.used_at !== null) return { kind: "used" };
  if (link.expires_at <= now) return { kind: "expired" };
  const claimed = await db.prepare("UPDATE delete_link SET used_at = ?2 WHERE token_hash = ?1 AND used_at IS NULL").bind(tokenHash, now).run();
  if (claimed.meta.changes !== 1) return { kind: "used" };
  return link.account_id;
}

/** What the page tells the phone after its reset link was tapped. */
export type ResetPinOutcome = { kind: "reset" } | { kind: "used" } | { kind: "expired" };

/**
 * Clears the Household PIN of the Account the link was sent for (#440): each TV forgets it at its
 * next pull, and asks for a new one the next time a Kids profile is left. Works once, for [LINK_LIFETIME_MS].
 */
export async function useResetPinLink(db: D1Database, token: string): Promise<ResetPinOutcome> {
  const claim = await claimLink(db, await sha256(token), "reset-pin");
  if (typeof claim !== "number") return claim;
  await resetHouseholdPin(db, claim);
  return { kind: "reset" };
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

const signInWords = (link: string) => ({
  text:
    `Tap this link to sign in your TV to Zugig:\n\n${link}\n\n` +
    "It works once, for the next 15 minutes. If you didn't ask for it, ignore this email: nothing happens without the link.\n",
  html:
    `<p>Tap this link to sign in your TV to Zugig:</p><p><a href="${link}">Sign in the TV</a></p>` +
    "<p>It works once, for the next 15 minutes. If you didn't ask for it, ignore this email: nothing happens without the link.</p>",
});

const resetPinWords = (link: string) => ({
  text:
    `Tap this link to reset your Household PIN, the one that lets you leave a Kids profile:\n\n${link}\n\n` +
    "It works once, for the next 15 minutes. Each TV then asks for a new PIN the next time someone leaves a Kids profile. If you didn't ask for it, ignore this email: nothing changes without the link.\n",
  html:
    `<p>Tap this link to reset your Household PIN, the one that lets you leave a Kids profile:</p><p><a href="${link}">Reset the Household PIN</a></p>` +
    "<p>It works once, for the next 15 minutes. Each TV then asks for a new PIN the next time someone leaves a Kids profile. If you didn't ask for it, ignore this email: nothing changes without the link.</p>",
});

const deleteWords = (link: string) => ({
  text:
    `Tap this link to delete your Zugig account, with the history it keeps and its list of TVs:\n\n${link}\n\n` +
    "It works once, for the next 15 minutes. If you didn't ask for it, ignore this email: nothing is deleted without the link.\n",
  html:
    `<p>Tap this link to delete your Zugig account, with the history it keeps and its list of TVs:</p><p><a href="${link}">Delete my account</a></p>` +
    "<p>It works once, for the next 15 minutes. If you didn't ask for it, ignore this email: nothing is deleted without the link.</p>",
});

/** Sends an email through the transactional sender (Resend). False when it didn't go out. */
async function sendEmail(env: Env, site: string, to: string, subject: string, { text, html }: { text: string; html: string }): Promise<boolean> {
  if (!env.RESEND_API_KEY) return false;
  const from = env.EMAIL_FROM ?? `Zugig <sign-in@${new URL(site).hostname}>`;
  try {
    const response = await fetch(`${env.RESEND_API}/emails`, {
      method: "POST",
      headers: { Authorization: `Bearer ${env.RESEND_API_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({ from, to: [to], subject, text, html }),
    });
    return response.ok;
  } catch {
    return false;
  }
}
