// Loads the hand files in provider-tables/ into D1's provider_table, one row per Provider hash, as
// part of a deploy (`npm run deploy`, CI's deploy step). Run it alone with
// `node scripts/provider-tables.mjs --local` (a local Worker's D1) or `--remote`.
//
// A file is named by the SHA-256 of the Provider's host, in lowercase hex. Each is upserted, so a
// device asking mid-deploy never gets an empty table for a Provider whose file stays; then the rows
// of files no longer in the repo are deleted. The text goes in as a hex blob cast to text, so no
// quote, semicolon or line break in a file can upset the SQL.
import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

export const PROVIDER_TABLES_DIR = fileURLToPath(new URL("../provider-tables/", import.meta.url));

/** The app's Base table, the text every Provider table merges into. */
export const readBaseTable = () => readFileSync(new URL("../../core/src/main/resources/dev/betteriptv/core/prefix-table.txt", import.meta.url), "utf8");

/** The hand files in [dir], by Provider hash. Throws on a file not named by a hash. */
export function readProviderTables(dir = PROVIDER_TABLES_DIR) {
  const files = {};
  for (const name of readdirSync(dir).sort()) {
    if (!name.endsWith(".txt")) continue;
    const provider = name.slice(0, -4);
    if (!/^[0-9a-f]{64}$/.test(provider)) throw new Error(`${name}: a Provider table is named <SHA-256 of the host, lowercase hex>.txt`);
    files[provider] = readFileSync(join(dir, name), "utf8");
  }
  return files;
}

/** The SQL statements that make provider_table hold exactly [files]' hand lines, one statement per entry. */
export function providerTableStatements(files, now = Date.now()) {
  const providers = Object.keys(files);
  const upserts = providers.map((provider) =>
    `INSERT INTO provider_table (provider, hand, loaded_at) VALUES ('${provider}', CAST(X'${Buffer.from(files[provider], "utf8").toString("hex")}' AS TEXT), ${now}) ` +
    "ON CONFLICT (provider) DO UPDATE SET hand = excluded.hand, loaded_at = excluded.loaded_at",
  );
  const keep = providers.map((p) => `'${p}'`).join(", ");
  return [...upserts, keep ? `DELETE FROM provider_table WHERE provider NOT IN (${keep})` : "DELETE FROM provider_table WHERE 1"];
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const target = process.argv.includes("--remote") ? "--remote" : "--local";
  const files = readProviderTables();
  const sql = join(mkdtempSync(join(tmpdir(), "provider-tables-")), "provider-tables.sql");
  writeFileSync(sql, providerTableStatements(files).map((s) => `${s};`).join("\n") + "\n");
  console.log(`Loading ${Object.keys(files).length} Provider table(s) into D1 (${target}).`);
  execFileSync("npx", ["wrangler", "d1", "execute", "DB", target, "--yes", `--file=${sql}`], { stdio: "inherit" });
}
