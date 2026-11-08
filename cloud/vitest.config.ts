import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";
// @ts-expect-error: a plain Node script, with no types.
import { providerTableStatements, readBaseTable, readProviderTables } from "./scripts/provider-tables.mjs";

const providerTables = readProviderTables();

export default defineConfig({
  plugins: [
    cloudflareTest(async () => ({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          OPENSUBTITLES_API_KEY: "test-os-key",
          OPENSUBTITLES_USERNAME: "test-os-user",
          OPENSUBTITLES_PASSWORD: "test-os-password",
          SUBDL_API_KEY: "test-subdl-key",
          RESEND_API_KEY: "test-resend-key",
          TEST_MIGRATIONS: await readD1Migrations("./migrations"),
          // The hand files in provider-tables/ and the statements a deploy runs for them.
          TEST_PROVIDER_TABLES: { files: providerTables, statements: providerTableStatements(providerTables) },
          // The app's Base table, which the weekly issue reads beside the hand files (scripts/weekly-issue.mjs).
          TEST_BASE_TABLE: readBaseTable(),
        },
      },
    })),
  ],
  test: { setupFiles: ["./test/apply-migrations.ts"] },
});
