/**
 * Provider tables (ADR 0014): what one Provider's names mean beyond the app's Base table, in the Base
 * table's format. A device asks for its Provider's at the start of every Sync and describes its
 * Listings with Base plus this. Hand lines come from the repo's cloud/provider-tables/<hash>.txt,
 * loaded into D1 on deploy (scripts/provider-tables.mjs); learned lines (src/learned-lines.ts) follow
 * them under `[learned prefixes]`, which a device reads below the hand lines and over Base.
 */
import { learnedLines } from "./learned-lines";

/** The Provider table for [provider] (a SHA-256 of its host) as text: "" for a Provider the cloud knows nothing of. */
export async function providerTable(db: D1Database, provider: string): Promise<string> {
  const row = await db.prepare("SELECT hand FROM provider_table WHERE provider = ?1").bind(provider).first<{ hand: string }>();
  const hand = row?.hand ?? "";
  const learned = await learnedLines(db, provider);
  if (learned.length === 0) return hand;
  const above = hand === "" ? "" : `${hand.endsWith("\n") ? hand : `${hand}\n`}\n`;
  return `${above}# Learned from this Provider's file reads: below the hand lines, over the Base table.\n[learned prefixes]\n${learned.join("\n")}\n`;
}
