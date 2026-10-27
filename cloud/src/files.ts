/**
 * File reads: what a movie Listing's file holds, as one device Header read it or played it, kept
 * for every Viewer of the same Provider so a Version is never read twice. A Provider is known only
 * by a SHA-256 of its host, which the device sends; the cloud never learns the host itself.
 */

/** At most this many Listings per request. */
export const MAX_FILE_BATCH = 50;

/** The largest file read kept, as JSON: a file with dozens of Tracks stays well under it. */
const MAX_BODY = 8 * 1024;

export interface FileRead {
  listingId: number;
  file: Record<string, unknown>;
}

/** The kept file reads of [provider]'s [listingIds]; Listings nobody read are left out. */
export async function fileReads(db: D1Database, provider: string, listingIds: number[]): Promise<FileRead[]> {
  if (listingIds.length === 0) return [];
  const marks = listingIds.map((_, i) => `?${i + 2}`).join(",");
  const { results } = await db
    .prepare(`SELECT listing_id, body FROM file_read WHERE provider = ?1 AND listing_id IN (${marks})`)
    .bind(provider, ...listingIds)
    .all<{ listing_id: number; body: string }>();
  return results.map((row) => ({ listingId: row.listing_id, file: JSON.parse(row.body) }));
}

/** Keeps [files] read on [provider], replacing what was kept: a later read has seen more. */
export async function saveFileReads(db: D1Database, provider: string, files: FileRead[]): Promise<void> {
  if (files.length === 0) return;
  const now = Date.now();
  await db.batch(
    files.map((file) =>
      db.prepare("INSERT OR REPLACE INTO file_read (provider, listing_id, body, read_at) VALUES (?1, ?2, ?3, ?4)")
        .bind(provider, file.listingId, JSON.stringify(file.file), now),
    ),
  );
}

/** Whether [provider] is a Provider key: a SHA-256 as lowercase hex. */
export const isProviderKey = (provider: unknown): provider is string => typeof provider === "string" && /^[0-9a-f]{64}$/.test(provider);

export const isListingId = (id: unknown): id is number => Number.isSafeInteger(id) && (id as number) > 0;

/** Whether [file] is a file read the cloud keeps: a JSON object of at most [MAX_BODY] bytes. */
export const isFile = (file: unknown): file is Record<string, unknown> =>
  typeof file === "object" && file !== null && !Array.isArray(file) && JSON.stringify(file).length <= MAX_BODY;
