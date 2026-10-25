/**
 * Per-device tokens. A device registers once and sends its token as `Authorization: Bearer …` on
 * every request. The token is random and stands for the device alone: no Provider login ever
 * reaches the cloud. Only its SHA-256 is stored, so a leaked table signs nobody in.
 */

/** Registers a new device and returns its token. */
export async function register(db: D1Database): Promise<string> {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const token = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  await db.prepare("INSERT INTO device (token_hash, registered_at) VALUES (?1, ?2)").bind(await hash(token), Date.now()).run();
  return token;
}

/** Whether [request] carries the token of a registered device. */
export async function isRegistered(db: D1Database, request: Request): Promise<boolean> {
  const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("Authorization") ?? "")?.[1];
  if (token === undefined) return false;
  return (await db.prepare("SELECT 1 FROM device WHERE token_hash = ?1").bind(await hash(token)).first()) !== null;
}

async function hash(token: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
