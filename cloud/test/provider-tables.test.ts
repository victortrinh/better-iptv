import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";

/** Seam 2: the Provider tables, loaded into D1 from cloud/provider-tables/ as a deploy loads them, and served. */
const call = (path: string, body: unknown, token?: string) =>
  worker.fetch(
    new Request(`https://cloud.example${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    }),
    env,
  );

async function registerDevice(): Promise<string> {
  return ((await (await call("/v1/devices", {})).json()) as { token: string }).token;
}

/** Runs the statements a deploy runs (scripts/provider-tables.mjs), for the files in the repo. */
const deploy = (statements: string[]) => env.DB.batch(statements.map((s) => env.DB.prepare(s)));

const surveyed = "6e096c1c4c0968f6b2ec3cc773d16782f91ba0a5648642b1f4aff5f9df503e77";

describe("Provider tables", () => {
  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM provider_table").run();
  });

  it("serves a hand file in the repo after a deploy, as the file's text", async () => {
    await deploy(env.TEST_PROVIDER_TABLES.statements);
    const token = await registerDevice();
    const response = await call("/v1/provider-table", { provider: surveyed }, token);
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("text/plain; charset=utf-8");
    const text = await response.text();
    expect(text).toBe(env.TEST_PROVIDER_TABLES.files[surveyed]);
    expect(text).toContain("CD          | language=de");
    expect(text).toContain("4M-AMZ      | source=Prime Video; quality=4K");
  });

  it("answers an empty table for a Provider it has never seen", async () => {
    await deploy(env.TEST_PROVIDER_TABLES.statements);
    const token = await registerDevice();
    const response = await call("/v1/provider-table", { provider: "b".repeat(64) }, token);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe("");
  });

  it("replaces a changed hand file on the next deploy and drops a removed one, never emptying the others in between", async () => {
    const other = "c".repeat(64);
    await env.DB.prepare("INSERT INTO provider_table (provider, hand, loaded_at) VALUES (?1, '[prefixes]\nXX | language=de\n', 1)").bind(other).run();
    await env.DB.prepare("INSERT INTO provider_table (provider, hand, loaded_at) VALUES (?1, 'old', 1)").bind(surveyed).run();
    await deploy(env.TEST_PROVIDER_TABLES.statements);
    const token = await registerDevice();
    expect(await (await call("/v1/provider-table", { provider: surveyed }, token)).text()).toBe(env.TEST_PROVIDER_TABLES.files[surveyed]);
    expect(await (await call("/v1/provider-table", { provider: other }, token)).text()).toBe("");
    // No statement deletes the table wholesale: a device asking mid-deploy never gets an empty answer for a kept file.
    expect(env.TEST_PROVIDER_TABLES.statements.some((s) => /^DELETE FROM provider_table\s*;?$/i.test(s.trim()))).toBe(false);
  });

  it("needs a device token and a hash of the Provider's host", async () => {
    expect((await call("/v1/provider-table", { provider: surveyed })).status).toBe(401);
    const token = await registerDevice();
    expect((await call("/v1/provider-table", { provider: "provider.example:8080" }, token)).status).toBe(400);
    expect((await call("/v1/provider-table", {}, token)).status).toBe(400);
  });
});
