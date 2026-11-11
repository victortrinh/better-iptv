import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
// @ts-expect-error: a plain Node script, with no types.
import { weeklyIssue } from "../scripts/weekly-issue.mjs";

/** The weekly issue's body (#439), built from rows shaped as the workflow's D1 queries give them. */
const a = "a".repeat(64);
const b = "b".repeat(64);
const week = Date.parse("2026-11-02T13:00:00Z");

const base = `
[prefixes]
EN          | language=en
QC          | language=fr-CA
4K-EN       | language=en; quality=4K
SC          # known, says nothing
NF          | source=Netflix

[tier tags]
VIP
GOLD
`;

const unknown = (provider: string, prefix: string, listings: number) => ({ provider, prefix, listings, example: `${prefix} - A Film | Part 2 (2020)`, first_seen: week });
const split = (provider: string, prefix: string, shares: Record<string, number>, attribute = "language") => ({ provider, prefix, attribute, reads: 0, shares: JSON.stringify(shares) });
const learnt = (provider: string, prefix: string, value: string, agreeing: number, reads: number, attribute = "language") => ({ provider, prefix, attribute, value, reads, agreeing });
const tierTag = (provider: string, word: string, listings: number) => ({ provider, word, listings, example: `${word}: TNT SPORT 1 HD`, first_seen: week, last_seen: week + 7 * 86_400_000 });

describe("the weekly issue", () => {
  it("shows per Provider the unknown Prefixes, the splits, the contradictions of Base and the candidate Tier tags", () => {
    const body: string = weeklyIssue(
      {
        unknowns: [unknown(a, "XB", 2), unknown(a, "BR", 260)],
        splits: [split(a, "SC", { sv: 11, da: 9 }), split(a, "UHD", { "4K": 6, HD: 4 }, "quality")],
        learned: [learnt(a, "EN", "fr", 19, 20), learnt(a, "4K-EN", "HD", 15, 16, "quality"), learnt(a, "NF", "multi-audio", 12, 13)],
        tierTags: [tierTag(a, "MAX", 12), tierTag(a, "PLUS", 40)],
      },
      base,
    );

    expect(body).toContain(`### \`${a}\``);
    expect(body).toContain("2 Prefixes, 262 Listings.");
    expect(body).toContain("| `BR` | 260 | BR - A Film \\| Part 2 (2020) | 2026-11-02 |\n| `XB` | 2 |");
    expect(body).toContain("- `SC`: Swedish 55%, Danish 45%, 20 reads");
    expect(body).toContain("- `UHD` quality: 4K 60%, HD 40%, 10 reads");
    expect(body).toContain("- `EN`: learned French 95%, Base says English (20 reads)");
    expect(body).toContain("- `4K-EN`: learned quality HD 94%, Base says 4K (16 reads)");
    // The Base table names no language for NF: learning Multi-audio there contradicts nothing.
    expect(body).not.toContain("`NF`");
    expect(body).toContain("| `PLUS` | 40 | PLUS: TNT SPORT 1 HD | 2026-11-02 | 2026-11-09 |\n| `MAX` | 12 |");
    expect(body.indexOf("#### Unknown Prefixes")).toBeLessThan(body.indexOf("#### Split reads"));
    expect(body.indexOf("#### Split reads")).toBeLessThan(body.indexOf("#### Learned lines that contradict the Base table"));
    expect(body.indexOf("#### Learned lines that contradict the Base table")).toBeLessThan(body.indexOf("#### Candidate Tier tags"));
  });

  it("leaves out hosts only tests serve, whatever they reported before the cloud stopped keeping it (#545)", () => {
    const emulator = "f9292b96ff7d66abe0820bbe9bd022cb0abd37bd673d7b462e1bba84245a9f66"; // 10.0.2.2
    const loopback = "12ca17b49af2289436f303e0166030a21e525d266e209267433801a8fd4071a0"; // 127.0.0.1
    const body: string = weeklyIssue(
      {
        unknowns: [unknown(emulator, "AR-DSC-S", 4), unknown(loopback, "SC", 2), unknown(a, "BR", 260)],
        splits: [split(emulator, "SC", { sv: 11, da: 9 })],
        learned: [learnt(loopback, "EN", "fr", 19, 20)],
        tierTags: [tierTag(emulator, "PLUS", 40), tierTag(loopback, "MAX", 12)],
      },
      base,
    );
    expect(body).toContain(`### \`${a}\``);
    expect(body).not.toContain(emulator);
    expect(body).not.toContain(loopback);
    expect(body).not.toContain("PLUS");
    expect(weeklyIssue({ unknowns: [unknown(emulator, "XB", 1)], tierTags: [tierTag(loopback, "MAX", 12)] }, base)).toBe("");
  });

  it("shows each part only when there is something to show, and nothing at all when no Provider has anything", () => {
    const body: string = weeklyIssue({ splits: [split(b, "SC", { sv: 11, da: 9 })] }, base);
    expect(body).toContain(`### \`${b}\``);
    expect(body).toContain("#### Split reads");
    expect(body).not.toContain("#### Unknown Prefixes");
    expect(body).not.toContain("#### Learned lines");
    expect(body).not.toContain("#### Candidate Tier tags");

    // A learned line that agrees with Base, a regional variant of Base's language, a known Tier tag: nothing to show.
    expect(weeklyIssue({ learned: [learnt(a, "EN", "en", 20, 20), learnt(a, "QC", "fr", 20, 20)], tierTags: [tierTag(a, "VIP", 30), tierTag(a, "gold", 3)] }, base)).toBe("");
    expect(weeklyIssue({}, base)).toBe("");
  });

  it("drops a Prefix once the Provider's hand file has a line for it, and a candidate once it is a Tier tag there", () => {
    const rows = {
      unknowns: [unknown(a, "XB", 2), unknown(b, "XB", 5)],
      splits: [split(a, "SC", { sv: 11, da: 9 })],
      learned: [learnt(a, "EN", "fr", 19, 20)],
      tierTags: [tierTag(a, "PLUS", 40)],
    };
    const before: string = weeklyIssue(rows, base);
    expect(before).toContain("`XB`");
    expect(before).toContain("`SC`");

    const after: string = weeklyIssue(rows, base, { [a]: "[prefixes]\nXB | language=pt-BR\nSC | language=sv\nEN\n\n[tier tags]\nPLUS\n" });
    expect(after).not.toContain(`### \`${a}\``);
    // Another Provider's hand file settles nothing here.
    expect(after).toContain(`### \`${b}\``);
    expect(after).toContain("| `XB` | 5 |");

    // An unknown Prefix since added to the Base table drops off too.
    expect(weeklyIssue({ unknowns: [unknown(a, "XB", 2)] }, `${base}\n[prefixes]\nXB | language=en\n`)).toBe("");
  });

  it("drops a split named by its listed language alone, a contradiction kept as learned, and a word that isn't a Tier tag", () => {
    const rows = {
      splits: [split(a, "IR", { en: 16, "multi-audio": 7, ja: 1 }), split(a, "DE", { HD: 16, SD: 2, "4K": 1 }, "quality")],
      learned: [learnt(a, "FR", "multi-audio", 18, 19), learnt(a, "DE", "multi-audio", 12, 18)],
      tierTags: [tierTag(a, "NOW", 36), tierTag(a, "BAN", 8), tierTag(a, "NBA", 2)],
    };
    const baseWith = `${base}\n[prefixes]\nIR | language=fa\nFR | language=fr\nDE | language=de\n`;
    const before: string = weeklyIssue(rows, baseWith);
    expect(before).toContain("- `IR`: English 67%");
    expect(before).toContain("- `FR`: learned Multi-audio 95%, Base says French");

    const hand = "[prefixes]\nIR | listed=fa\nFR | language=fr; keep-learned\nDE | language=de; keep-learned\n\n[not tier tags]\nNOW\nban:\n";
    const after: string = weeklyIssue(rows, baseWith, { [a]: hand });
    expect(after).not.toContain("#### Split reads");
    expect(after).not.toContain("#### Learned lines");
    expect(after).not.toContain("`NOW`");
    expect(after).not.toContain("`BAN`");
    // A word left out of both lists is still the curator's to decide.
    expect(after).toContain("| `NBA` | 2 |");
    // The Base table can say a word isn't a Tier tag for every Provider.
    expect(weeklyIssue({ tierTags: [tierTag(a, "NBA", 2)] }, `${base}\n[not tier tags]\nNBA\n`)).toBe("");
  });

  it("orders Providers by what there is to settle", () => {
    const body: string = weeklyIssue({ unknowns: [unknown(a, "XB", 2), unknown(b, "BR", 260)] }, base);
    expect(body.indexOf(b)).toBeLessThan(body.indexOf(a));
  });

  it("reads the repo's Base table and hand files", () => {
    // The surveyed Provider's hand file knows CD: reported unknown before #431, it is settled.
    const surveyed = "6e096c1c4c0968f6b2ec3cc773d16782f91ba0a5648642b1f4aff5f9df503e77";
    const body: string = weeklyIssue({ unknowns: [unknown(surveyed, "CD", 3), unknown(surveyed, "ZZQ", 1)], tierTags: [tierTag(surveyed, "VIP", 9)] }, env.TEST_BASE_TABLE, env.TEST_PROVIDER_TABLES.files);
    expect(body).toContain("`ZZQ`");
    expect(body).not.toContain("`CD`");
    expect(body).not.toContain("Candidate Tier tags");
  });
});
