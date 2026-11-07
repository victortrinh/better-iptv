import type { D1Migration } from "cloudflare:test";
import type { Env as WorkerEnv } from "../src/env";

declare global {
  namespace Cloudflare {
    interface Env extends WorkerEnv {
      TEST_MIGRATIONS: D1Migration[];
      TEST_PROVIDER_TABLES: { files: Record<string, string>; statements: string[] };
      TEST_BASE_TABLE: string;
    }
  }
}
