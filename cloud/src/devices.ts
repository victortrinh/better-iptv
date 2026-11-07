/**
 * Per-device tokens. A device registers once and sends its token as `Authorization: Bearer …` on
 * every request. The token is random and stands for the device alone: no Provider login ever
 * reaches the cloud. Only its SHA-256 is stored, so a leaked table signs nobody in. Signing in to an
 * Account binds the token to it (ADR 0015); the token stays the device's only credential.
 */

/** Registers a new device and returns its token. */
export async function register(db: D1Database): Promise<string> {
  const token = randomToken();
  await db.prepare("INSERT INTO device (token_hash, registered_at) VALUES (?1, ?2)").bind(await sha256(token), Date.now()).run();
  return token;
}

/** The hash of the registered device's token [request] carries, or null when it carries none a device was given. */
export async function deviceOf(db: D1Database, request: Request): Promise<string | null> {
  const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("Authorization") ?? "")?.[1];
  if (token === undefined) return null;
  const hash = await sha256(token);
  return (await db.prepare("SELECT 1 FROM device WHERE token_hash = ?1").bind(hash).first()) !== null ? hash : null;
}

/** 32 random bytes, in base64url without padding: 43 characters. */
export function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** The SHA-256 of [text], in lowercase hex. */
export async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
