/**
 * Per-device tokens. A device registers once and sends its token as `Authorization: Bearer …` on
 * every request. The token is random and stands for the device alone: no Provider login ever
 * reaches the cloud. Only its SHA-256 is stored, so a leaked table signs nobody in. Signing in to an
 * Account binds the token to it (ADR 0015); the token stays the device's only credential.
 */

/** The platforms a device can say it is when it registers (ADR 0020), as the API names them. */
export const PLATFORMS = ["fire-os", "google-tv", "android", "ios", "macos", "windows", "linux", "tizen"] as const;
export type Platform = (typeof PLATFORMS)[number];

/** What a device says of itself when it registers: its platform and the app version it runs, null when it didn't say. */
export interface Registration {
  platform: Platform;
  appVersion: string | null;
}

/** The longest app version kept. */
const MAX_APP_VERSION = 40;

/**
 * Reads a registration's [body], or says what is wrong with it. A device that names no platform is taken as
 * Android: the builds from before platforms were sent say nothing, and all of them are Android (#570).
 */
export function readRegistration(body: unknown): Registration | string {
  if (typeof body !== "object" || Array.isArray(body)) return "A registration must be a JSON object.";
  const { platform, appVersion } = (body ?? {}) as { platform?: unknown; appVersion?: unknown };
  if (platform !== undefined && platform !== null && !PLATFORMS.includes(platform as Platform)) return `platform must be one of ${PLATFORMS.join(", ")}.`;
  if (appVersion !== undefined && appVersion !== null && (typeof appVersion !== "string" || appVersion === "" || appVersion.length > MAX_APP_VERSION)) {
    return `appVersion must be the app's version, at most ${MAX_APP_VERSION} characters.`;
  }
  return { platform: (platform as Platform | undefined | null) ?? "android", appVersion: (appVersion as string | undefined | null) ?? null };
}

/**
 * Registers the device [request] comes from as [registration] and returns its token. A device that sends the
 * token it already has keeps it, and its platform and app version are updated: `created` is false. Any other
 * is a new device with a new token.
 */
export async function register(db: D1Database, request: Request, registration: Registration): Promise<{ token: string; created: boolean }> {
  const known = bearerToken(request);
  if (known !== undefined) {
    const updated = await db
      .prepare("UPDATE device SET platform = ?1, app_version = ?2 WHERE token_hash = ?3")
      .bind(registration.platform, registration.appVersion, await sha256(known))
      .run();
    if (updated.meta.changes > 0) return { token: known, created: false };
  }
  const token = randomToken();
  await db
    .prepare("INSERT INTO device (token_hash, registered_at, platform, app_version) VALUES (?1, ?2, ?3, ?4)")
    .bind(await sha256(token), Date.now(), registration.platform, registration.appVersion)
    .run();
  return { token, created: true };
}

/** The device token [request] carries, when it has the shape of one. */
function bearerToken(request: Request): string | undefined {
  return /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("Authorization") ?? "")?.[1];
}

/**
 * The registered device whose token [request] carries, by its token's hash, and whether it is signed
 * in to an Account; null when it carries none a device was given.
 */
export async function deviceOf(db: D1Database, request: Request): Promise<{ hash: string; signedIn: boolean } | null> {
  const token = bearerToken(request);
  if (token === undefined) return null;
  const hash = await sha256(token);
  const row = await db.prepare("SELECT account_id FROM device WHERE token_hash = ?1").bind(hash).first<{ account_id: number | null }>();
  return row === null ? null : { hash, signedIn: row.account_id !== null };
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
