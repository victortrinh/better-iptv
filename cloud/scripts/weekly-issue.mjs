// The weekly "Unknown Prefixes" issue's body (.github/workflows/unknown-prefixes.yml): what the cloud
// couldn't settle of each Provider's Prefix table, a section per Provider hash, for the curator to write
// hand lines from (#439). Each section lists, only when there is something to show:
//   - the unknown Prefixes devices reported in the last 30 days;
//   - the Prefixes whose reads split under the bar, with each value's share ("`SC`: Swedish 55%, Danish 45%, 20 reads");
//   - the learned lines that contradict the Base table ("`EN`: learned French 95%, Base says English");
//   - the candidate Tier tags devices reported in the last 30 days.
// A Prefix with a hand line in the Provider's hand file (or, for an unknown one, now in the Base table)
// is left out, and so is a candidate already a Tier tag there: the hand line settled it. Hosts only tests
// serve (FakeProvider on loopback or the emulator's 10.0.2.2) are left out altogether. Nothing here is
// applied by itself.
//
// Run it as `node scripts/weekly-issue.mjs unknowns.json splits.json learned.json tier-tags.json`, each
// file what `wrangler d1 execute --json` printed for the workflow's queries; it prints the body, or
// nothing when no section has anything to show.

/**
 * The Provider keys of hosts only tests serve (#545), as src/test-hosts.ts lists them: localhost,
 * 127.0.0.1, ::1 and the emulator's 10.0.2.2. The cloud no longer keeps their reports, and their rows
 * already in D1 (learned lines are kept for good) are left out of the issue.
 */
export const TEST_HOST_KEYS = new Set([
  "49960de5880e8c687434170f6476605b8fe4aeb9a28632c7995cf3ba831d9763",
  "12ca17b49af2289436f303e0166030a21e525d266e209267433801a8fd4071a0",
  "eff8e7ca506627fe15dda5e0e512fcaad70b6d520f37cc76597fdb4f2d83a1a3",
  "f9292b96ff7d66abe0820bbe9bd022cb0abd37bd673d7b462e1bba84245a9f66",
]);

/** The attributes the issue shows splits and contradictions of: the ones a hand line is written for. */
const SHOWN = new Set(["language", "quality"]);

/**
 * What a table in the Base table's format says: each Prefix's rule as attributes (language, quality,
 * multi-audio), and its Tier tags, uppercase.
 */
export function readTable(text) {
  const prefixes = new Map();
  const tierTags = new Set();
  let section = null;
  for (const raw of text.split("\n")) {
    const line = raw.replace(/#.*$/, "").trim();
    if (line === "") continue;
    const header = line.match(/^\[(.+)\]$/);
    if (header) {
      section = header[1];
      continue;
    }
    if (section === "prefixes") {
      const [key, rule = ""] = line.split("|");
      const attributes = {};
      for (const attribute of rule.split(";").map((a) => a.trim()).filter(Boolean)) {
        const [name, value = ""] = attribute.split("=").map((s) => s.trim());
        attributes[name] = value || true;
      }
      prefixes.set(key.trim(), attributes);
    } else if (section === "tier tags") {
      tierTags.add(line.replace(/:$/, "").toUpperCase());
    }
  }
  return { prefixes, tierTags };
}

const languageNames = new Intl.DisplayNames(["en"], { type: "language" });

/** A language value as the issue names it: `sv` is Swedish, `fr-CA` Canadian French, `multi` several languages. */
function languageName(value) {
  if (value === "multi" || value === "multi-audio") return "Multi-audio";
  try {
    return languageNames.of(value) ?? value;
  } catch {
    return value;
  }
}

/** A value of [attribute] as the issue names it. */
const valueName = (attribute, value) => (attribute === "language" ? languageName(value) : value);

const primary = (tag) => tag.split("-")[0].toLowerCase();

/** What the Base table's rule [base] says of [attribute] that a learned [value] contradicts, or null when nothing. */
function contradicted(attribute, value, base) {
  if (!base) return null;
  if (attribute === "language") {
    if (base["multi-audio"] === true) return value === "multi-audio" ? null : "Multi-audio";
    if (typeof base.language !== "string") return null;
    if (value === "multi-audio") return languageName(base.language);
    return primary(value) === primary(base.language) ? null : languageName(base.language);
  }
  if (attribute === "quality") return typeof base.quality === "string" && base.quality !== value ? base.quality : null;
  return null;
}

const percent = (count, total) => `${Math.round((count / total) * 100)}%`;
const day = (millis) => new Date(millis).toISOString().slice(0, 10);
const cell = (text) => String(text).replace(/\|/g, "\\|");
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/**
 * The issue's body from the queries' rows, the Base table's text and the hand files by Provider hash;
 * "" when there is nothing to show. Rows:
 *   unknowns  {provider, prefix, listings, example, first_seen}
 *   splits    {provider, prefix, attribute, reads, shares (JSON of counts by value)}
 *   learned   {provider, prefix, attribute, value, reads, agreeing}
 *   tierTags  {provider, word, listings, example, first_seen, last_seen}
 */
export function weeklyIssue(rows, baseText, handFiles = {}) {
  const real = (list = []) => list.filter((row) => !TEST_HOST_KEYS.has(row.provider));
  const [unknowns, splits, learned, tierTags] = [rows.unknowns, rows.splits, rows.learned, rows.tierTags].map(real);
  const base = readTable(baseText);
  const hands = new Map();
  const hand = (provider) => {
    if (!hands.has(provider)) hands.set(provider, readTable(handFiles[provider] ?? ""));
    return hands.get(provider);
  };
  const settled = (provider, prefix) => hand(provider).prefixes.has(prefix);
  const sections = new Map();
  const section = (provider) => {
    if (!sections.has(provider)) sections.set(provider, { unknowns: [], splits: [], contradictions: [], tierTags: [] });
    return sections.get(provider);
  };

  for (const row of unknowns) {
    if (settled(row.provider, row.prefix) || base.prefixes.has(row.prefix)) continue;
    section(row.provider).unknowns.push(row);
  }
  for (const row of splits) {
    if (!SHOWN.has(row.attribute) || settled(row.provider, row.prefix)) continue;
    const shares = Object.entries(JSON.parse(row.shares)).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    const reads = shares.reduce((sum, [, count]) => sum + count, 0);
    if (reads === 0) continue;
    const named = shares.map(([value, count]) => `${valueName(row.attribute, value)} ${percent(count, reads)}`).join(", ");
    const what = row.attribute === "language" ? "" : ` ${row.attribute}`;
    section(row.provider).splits.push({ prefix: row.prefix, reads, line: `- \`${row.prefix}\`${what}: ${named}, ${plural(reads, "read", "reads")}` });
  }
  for (const row of learned) {
    if (!SHOWN.has(row.attribute) || settled(row.provider, row.prefix)) continue;
    const says = contradicted(row.attribute, row.value, base.prefixes.get(row.prefix));
    if (says === null) continue;
    const what = row.attribute === "language" ? "" : `${row.attribute} `;
    section(row.provider).contradictions.push({
      prefix: row.prefix,
      reads: row.reads,
      line: `- \`${row.prefix}\`: learned ${what}${valueName(row.attribute, row.value)} ${percent(row.agreeing, row.reads)}, Base says ${says} (${plural(row.reads, "read", "reads")})`,
    });
  }
  for (const row of tierTags) {
    const word = row.word.toUpperCase();
    if (base.tierTags.has(word) || hand(row.provider).tierTags.has(word)) continue;
    section(row.provider).tierTags.push(row);
  }

  const shown = [...sections].filter(([, s]) => s.unknowns.length + s.splits.length + s.contradictions.length + s.tierTags.length > 0);
  if (shown.length === 0) return "";
  const weight = (s) => s.unknowns.reduce((sum, r) => sum + r.listings, 0) + s.splits.length + s.contradictions.length + s.tierTags.length;
  shown.sort(([a, x], [b, y]) => weight(y) - weight(x) || a.localeCompare(b));

  const out = [
    "What the cloud couldn't settle of each Provider's Prefix table: the Prefixes devices' Syncs found that no table knows (last 30 days), the Prefixes whose file reads split under the bar, the learned lines that contradict the Base table, and the candidate Tier tags Syncs found (last 30 days).",
    "Write a hand line for each in the Provider's hand file, `cloud/provider-tables/<hash>.txt`, or add an unknown Prefix to the Base table, `core/src/main/resources/dev/betteriptv/core/prefix-table.txt`, when most Providers share it. A Prefix with a hand line drops off this list the next week. Nothing here is applied by itself; the Viewer never sees this list.",
    "Each section is one Provider, by the SHA-256 of its host. Providers with the most to settle come first.",
  ];
  for (const [provider, s] of shown) {
    out.push("", `### \`${provider}\``);
    if (s.unknowns.length > 0) {
      const listings = s.unknowns.reduce((sum, r) => sum + r.listings, 0);
      out.push("", `#### Unknown Prefixes`, "", `${plural(s.unknowns.length, "Prefix", "Prefixes")}, ${plural(listings, "Listing", "Listings")}.`, "");
      out.push("| Prefix | Listings | Example | First seen |", "| --- | ---: | --- | --- |");
      for (const r of [...s.unknowns].sort((a, b) => b.listings - a.listings || a.prefix.localeCompare(b.prefix))) {
        out.push(`| \`${r.prefix}\` | ${r.listings} | ${cell(r.example)} | ${day(r.first_seen)} |`);
      }
    }
    if (s.splits.length > 0) {
      out.push("", "#### Split reads", "", "Enough reads, no value at 90%: nothing is learned until a hand line says what the Prefix means.", "");
      for (const r of [...s.splits].sort((a, b) => b.reads - a.reads || a.prefix.localeCompare(b.prefix))) out.push(r.line);
    }
    if (s.contradictions.length > 0) {
      out.push("", "#### Learned lines that contradict the Base table", "", "The learned line wins over the Base table on this Provider; a hand line wins over both.", "");
      for (const r of [...s.contradictions].sort((a, b) => b.reads - a.reads || a.prefix.localeCompare(b.prefix))) out.push(r.line);
    }
    if (s.tierTags.length > 0) {
      out.push(
        "",
        "#### Candidate Tier tags",
        "",
        "Words before the colon at the start of live Listings' names whose removal makes them the same Channel as another. A wrong Tier tag merges two Channels: add one to `[tier tags]` only when its Listings are the same Channel.",
        "",
        "| Word | Listings merged | Example | First seen | Last seen |",
        "| --- | ---: | --- | --- | --- |",
      );
      for (const r of [...s.tierTags].sort((a, b) => b.listings - a.listings || a.word.localeCompare(b.word))) {
        out.push(`| \`${r.word}\` | ${r.listings} | ${cell(r.example)} | ${day(r.first_seen)} | ${day(r.last_seen)} |`);
      }
    }
  }
  return `${out.join("\n")}\n`;
}

if (typeof process !== "undefined" && process.argv?.[1] && import.meta.url === (await import("node:url")).pathToFileURL(process.argv[1]).href) {
  const { readFileSync } = await import("node:fs");
  const { readBaseTable, readProviderTables } = await import("./provider-tables.mjs");
  const rows = (path) => JSON.parse(readFileSync(path, "utf8"))[0]?.results ?? [];
  const [unknowns, splits, learned, tierTags] = process.argv.slice(2, 6).map(rows);
  process.stdout.write(weeklyIssue({ unknowns, splits, learned, tierTags }, readBaseTable(), readProviderTables()));
}
