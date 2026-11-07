/**
 * Unknown Prefixes: Prefixes a device's Sync found that its Prefix table doesn't know. They're kept
 * for the developer, who adds them to the table (a weekly workflow lists them in an issue), and never
 * shown to the Viewer. A Provider is known only by a SHA-256 of its host, as for file reads.
 */

/** At most this many Prefixes per request: the surveyed Provider has about 100 in all. */
export const MAX_PREFIX_BATCH = 200;

export interface UnknownPrefix {
  prefix: string;
  listingCount: number;
  example: string;
}

/** Keeps what one Sync on [provider] reported, keeping when each Prefix was first seen. */
export async function saveUnknownPrefixes(db: D1Database, provider: string, prefixes: UnknownPrefix[]): Promise<void> {
  if (prefixes.length === 0) return;
  const now = Date.now();
  await db.batch(
    prefixes.map((p) =>
      db.prepare(
        `INSERT INTO unknown_prefix (provider, prefix, listing_count, example, first_seen, last_seen) VALUES (?1, ?2, ?3, ?4, ?5, ?5)
         ON CONFLICT (provider, prefix) DO UPDATE SET listing_count = excluded.listing_count, example = excluded.example, last_seen = excluded.last_seen`,
      ).bind(provider, p.prefix, p.listingCount, p.example, now),
    ),
  );
}

/** At most this many candidate Tier tags per request. */
export const MAX_TIER_TAG_BATCH = 50;

/**
 * A candidate Tier tag (#439): a word before the colon at the start of live Listings' names whose removal
 * made [listingCount] of them the same Channel as another live Listing, such as [example]. Kept for the
 * weekly issue and never applied: the curator writes a hand line for one that is.
 */
export interface CandidateTierTag {
  word: string;
  listingCount: number;
  example: string;
}

/** Keeps the candidate Tier tags one Sync on [provider] reported, keeping when each was first seen. */
export async function saveCandidateTierTags(db: D1Database, provider: string, tags: CandidateTierTag[]): Promise<void> {
  if (tags.length === 0) return;
  const now = Date.now();
  await db.batch(
    tags.map((t) =>
      db.prepare(
        `INSERT INTO tier_tag_candidate (provider, word, listing_count, example, first_seen, last_seen) VALUES (?1, ?2, ?3, ?4, ?5, ?5)
         ON CONFLICT (provider, word) DO UPDATE SET listing_count = excluded.listing_count, example = excluded.example, last_seen = excluded.last_seen`,
      ).bind(provider, t.word, t.listingCount, t.example, now),
    ),
  );
}

/** Whether [t] is a candidate Tier tag as a device reports it: one short word, a count and a Listing's name. */
export const isCandidateTierTag = (t: unknown): t is CandidateTierTag => {
  const { word, listingCount, example } = (t ?? {}) as Record<string, unknown>;
  return typeof word === "string" && /^[A-Za-z0-9+]{1,5}$/.test(word) &&
    Number.isSafeInteger(listingCount) && (listingCount as number) > 0 &&
    typeof example === "string" && example.length >= 1 && example.length <= 300;
};

/** Whether [p] is an unknown Prefix as a device reports it: a short code, a count and a Listing's name. */
export const isUnknownPrefix = (p: unknown): p is UnknownPrefix => {
  const { prefix, listingCount, example } = (p ?? {}) as Record<string, unknown>;
  return typeof prefix === "string" && prefix.length >= 1 && prefix.length <= 32 &&
    Number.isSafeInteger(listingCount) && (listingCount as number) > 0 &&
    typeof example === "string" && example.length >= 1 && example.length <= 300;
};
