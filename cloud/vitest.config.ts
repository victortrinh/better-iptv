import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest(async () => ({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          TMDB_TOKEN: "test-tmdb-token",
          OPENSUBTITLES_API_KEY: "test-os-key",
          OPENSUBTITLES_USERNAME: "test-os-user",
          OPENSUBTITLES_PASSWORD: "test-os-password",
          SUBDL_API_KEY: "test-subdl-key",
          TEST_MIGRATIONS: await readD1Migrations("./migrations"),
        },
      },
    })),
  ],
  test: { setupFiles: ["./test/apply-migrations.ts"] },
});
