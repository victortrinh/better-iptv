/**
 * Learned lines (ADR 0014): what a Provider's Header reads and Playback taught of its Prefixes. Each
 * file-read report carries the Listing's Prefix (a code, never a name) and the Category hints that
 * matched; on every report the cloud tallies the reads of each Prefix it touched and learns an
 * attribute once enough of them agree. Only what a file states can be learned: the main audio's
 * language (or Multi-audio), the quality, HDR and Dolby audio. Sources, Clutter, Tier tags, the Event
 * time zone and country aliases are hand lines' alone.
 */

/** Reads that must agree before a line is learned; fewer when the Prefix has fewer Listings. */
export const MIN_READS = 10;

/** The share of reads that must agree on a value. */
export const AGREEMENT = 0.9;

/** The most recent reads of a Prefix that are tallied, so a busy Prefix costs the same as a quiet one. */
const READS_TALLIED = 200;

/** The attributes learned, in the order a learned line lists them. */
const ATTRIBUTES = ["language", "quality", "hdr", "dolby-audio"] as const;
type Attribute = (typeof ATTRIBUTES)[number];

/** The value a Language tally gives a Prefix whose files mostly carry several audio languages. */
const MULTI_AUDIO = "multi-audio";

/** The kept reads of the given Prefixes ([2], a JSON list) on a Provider ([1]), the most recent first, at most [READS_TALLIED] each. */
export const READS_QUERY = `
  SELECT prefix, body FROM (
    SELECT prefix, body, row_number() OVER (PARTITION BY prefix ORDER BY read_at DESC) AS n
    FROM file_read WHERE provider = ?1 AND prefix IN (SELECT value FROM json_each(?2))
  ) WHERE n <= ${READS_TALLIED}`;

/** One attribute's tally over a Prefix's reads. */
interface Tally {
  attribute: Attribute;
  /** The most common value, null when no read tells it. */
  value: string | null;
  reads: number;
  agreeing: number;
  shares: Record<string, number>;
  status: "learned" | "split" | "gathering";
}

/** Tallies again every Prefix in [prefixes] on [provider] from its kept reads, learning and withdrawing lines. */
export async function relearn(db: D1Database, provider: string, prefixes: Set<string>): Promise<void> {
  if (prefixes.size === 0) return;
  const asked = JSON.stringify([...prefixes]);
  const [reads, counts] = await db.batch<{ prefix: string; body?: string; listing_count?: number }>([
    db.prepare(READS_QUERY).bind(provider, asked),
    db.prepare("SELECT prefix, listing_count FROM unknown_prefix WHERE provider = ?1 AND prefix IN (SELECT value FROM json_each(?2))").bind(provider, asked),
  ]);
  const files = new Map<string, Record<string, unknown>[]>([...prefixes].map((p) => [p, []]));
  for (const row of reads.results) files.get(row.prefix)?.push(JSON.parse(row.body!));
  const listings = new Map(counts.results.map((row) => [row.prefix, row.listing_count!]));
  const now = Date.now();
  const statements = [...files].flatMap(([prefix, read]) => {
    const needed = Math.max(1, Math.min(MIN_READS, listings.get(prefix) ?? MIN_READS));
    return ATTRIBUTES.map((attribute) => tally(attribute, read, needed)).map((t) =>
      db.prepare(
        `INSERT INTO learned_line (provider, prefix, attribute, value, reads, agreeing, shares, status, learned_at, withdrawn_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, CASE WHEN ?8 = 'learned' THEN ?9 END, NULL, ?9)
         ON CONFLICT (provider, prefix, attribute) DO UPDATE SET
           value = excluded.value, reads = excluded.reads, agreeing = excluded.agreeing, shares = excluded.shares, status = excluded.status,
           learned_at = CASE WHEN excluded.status = 'learned' AND (learned_line.status <> 'learned' OR learned_line.value IS NOT excluded.value) THEN excluded.updated_at ELSE learned_line.learned_at END,
           withdrawn_at = CASE WHEN learned_line.status = 'learned' AND excluded.status <> 'learned' THEN excluded.updated_at ELSE learned_line.withdrawn_at END,
           updated_at = excluded.updated_at`,
      ).bind(provider, prefix, t.attribute, t.value, t.reads, t.agreeing, JSON.stringify(t.shares), t.status, now),
    );
  });
  await db.batch(statements);
}

/** The learned lines of [provider], as the Provider table's `[learned prefixes]` section's lines, by Prefix. */
export async function learnedLines(db: D1Database, provider: string): Promise<string[]> {
  const { results } = await db
    .prepare("SELECT prefix, attribute, value FROM learned_line WHERE provider = ?1 AND status = 'learned' ORDER BY prefix")
    .bind(provider)
    .all<{ prefix: string; attribute: Attribute; value: string }>();
  const byPrefix = new Map<string, Map<Attribute, string>>();
  for (const row of results) {
    if (!byPrefix.has(row.prefix)) byPrefix.set(row.prefix, new Map());
    byPrefix.get(row.prefix)!.set(row.attribute, row.value);
  }
  return [...byPrefix].flatMap(([prefix, values]) => {
    const rule = ATTRIBUTES.flatMap((attribute) => {
      const value = values.get(attribute);
      switch (attribute) {
        case "language": return value === MULTI_AUDIO ? [MULTI_AUDIO] : value ? [`language=${value}`] : [];
        case "quality": return value ? [`quality=${value}`] : [];
        // That most files state no HDR or Dolby audio says nothing a line can: not every file names it.
        case "hdr": return value && value !== "none" ? [`hdr=${value}`] : [];
        case "dolby-audio": return value === "yes" ? ["dolby-audio"] : [];
      }
    });
    return rule.length === 0 ? [] : [`${prefix} | ${rule.join("; ")}`];
  });
}

/** [attribute]'s tally over [files], learned once [needed] reads tell it and [AGREEMENT] of them agree. */
function tally(attribute: Attribute, files: Record<string, unknown>[], needed: number): Tally {
  const votes = files.map((file) => vote(attribute, file)).filter((v): v is string => v !== null);
  const shares: Record<string, number> = {};
  for (const v of votes) shares[v] = (shares[v] ?? 0) + 1;
  const ranked = Object.entries(shares).sort((a, b) => b[1] - a[1]);
  let [value, agreeing] = ranked[0] ?? [null, 0];
  const reads = votes.length;
  const agreed = (count: number) => count >= AGREEMENT * reads;
  let learned = reads >= needed && agreed(agreeing);
  if (attribute === "language" && value === "multi") learned = false;
  if (attribute === "language" && !learned && reads >= needed && (shares.multi ?? 0) * 2 > reads) {
    // Most files carry several audio languages: no language is the Prefix's, whatever the first Track says.
    [value, agreeing, learned] = [MULTI_AUDIO, shares.multi, true];
  }
  if (attribute === "hdr" && !learned && reads >= needed && agreed((shares.DV ?? 0) + (shares.HDR10 ?? 0))) {
    // Dolby Vision files carry HDR10 too, so a Prefix mixing the two is at least HDR10.
    [value, agreeing, learned] = ["HDR10", (shares.DV ?? 0) + (shares.HDR10 ?? 0), true];
  }
  const status = learned ? "learned" : reads >= needed ? "split" : "gathering";
  return { attribute, value, reads, agreeing, shares, status };
}

/** What one file says of [attribute], or null when it says nothing. */
function vote(attribute: Attribute, file: Record<string, unknown>): string | null {
  const audio = (Array.isArray(file.audio) ? file.audio : []).filter((t): t is Record<string, unknown> => typeof t === "object" && t !== null);
  switch (attribute) {
    case "language": {
      const heard = new Set(audio.map((t) => language(t.language)).filter((l): l is string => l !== null));
      return heard.size === 0 ? null : heard.size === 1 ? [...heard][0] : "multi";
    }
    case "quality": {
      const width = typeof file.width === "number" ? file.width : 0;
      const height = typeof file.height === "number" ? file.height : 0;
      if (width === 0 && height === 0) return null;
      // As the device tells a file's quality (Episodes.kt), so a scope 4K film (3840×1600) counts as 4K.
      return width >= 3200 || height >= 1800 ? "4K" : width >= 1200 || height >= 700 ? "HD" : "SD";
    }
    case "hdr":
      return file.hdr === "DolbyVision" ? "DV" : file.hdr === "HDR10" ? "HDR10" : "none";
    case "dolby-audio":
      return audio.length === 0 ? null : audio.some((t) => DOLBY_AUDIO.has(t.codec as string)) ? "yes" : "no";
  }
}

const DOLBY_AUDIO = new Set(["audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/true-hd"]);

/** A Track's language tag as a primary subtag (`fre`, `fra`, `fr-FR` are `fr`), null for none or a tag that names no language. */
function language(tag: unknown): string | null {
  if (typeof tag !== "string") return null;
  const primary = tag.split(/[-_]/)[0].toLowerCase();
  if (!/^[a-z]{2,3}$/.test(primary) || NO_LANGUAGE.has(primary) || /^q[a-t][a-z]$/.test(primary)) return null;
  return ISO_639_1[primary] ?? primary;
}

const NO_LANGUAGE = new Set(["und", "mis", "mul", "zxx"]);

/** ISO 639-1 codes by ISO 639-2 code, terminology and bibliographic forms, for the languages a Provider's files carry. */
const ISO_639_1: Record<string, string> = Object.fromEntries(
  (`aar aa,abk ab,afr af,aka ak,alb sq,sqi sq,amh am,ara ar,arm hy,hye hy,asm as,aze az,bak ba,baq eu,eus eu,bel be,ben bn,bos bs,
  bre br,bul bg,bur my,mya my,cat ca,ces cs,cze cs,chi zh,zho zh,cos co,cym cy,wel cy,dan da,deu de,ger de,div dv,dut nl,nld nl,dzo dz,
  ell el,gre el,eng en,epo eo,est et,ewe ee,fao fo,fas fa,per fa,fij fj,fil tl,fin fi,fra fr,fre fr,fry fy,ful ff,geo ka,kat ka,gla gd,
  gle ga,glg gl,grn gn,guj gu,hat ht,hau ha,heb he,hin hi,hrv hr,hun hu,ibo ig,ice is,isl is,ind id,ita it,jav jv,jpn ja,kal kl,kan kn,
  kas ks,kaz kk,khm km,kin rw,kir ky,kor ko,kur ku,lao lo,lat la,lav lv,lin ln,lit lt,ltz lb,lug lg,mac mk,mkd mk,mal ml,mao mi,mri mi,
  mar mr,may ms,msa ms,mlg mg,mlt mt,mon mn,nep ne,nno nn,nob nb,nor no,nya ny,oci oc,ori or,orm om,pan pa,pol pl,por pt,pus ps,que qu,
  roh rm,ron ro,rum ro,run rn,rus ru,sag sg,san sa,sin si,slk sk,slo sk,slv sl,sme se,smo sm,sna sn,snd sd,som so,sot st,spa es,srd sc,
  srp sr,ssw ss,sun su,swa sw,swe sv,tah ty,tam ta,tat tt,tel te,tgk tg,tgl tl,tha th,tib bo,bod bo,tir ti,ton to,tsn tn,tso ts,tuk tk,
  tur tr,uig ug,ukr uk,urd ur,uzb uz,ven ve,vie vi,wol wo,xho xh,yid yi,yor yo,zul zu`)
    .split(",")
    .map((pair) => pair.trim().split(" ")),
);
