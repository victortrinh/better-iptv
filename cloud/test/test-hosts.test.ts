import { describe, expect, it } from "vitest";
// @ts-expect-error: a plain Node script, with no types.
import { TEST_HOST_KEYS as issueKeys } from "../scripts/weekly-issue.mjs";
import { TEST_HOST_KEYS } from "../src/test-hosts";

const sha256 = async (text: string) =>
  [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)))].map((b) => b.toString(16).padStart(2, "0")).join("");

describe("hosts only tests serve (#545)", () => {
  it("are loopback and the emulator's address, hashed as the app hashes a Provider's host", async () => {
    const hosts = ["localhost", "127.0.0.1", "::1", "10.0.2.2"];
    const keys = await Promise.all(hosts.map(sha256));
    expect([...TEST_HOST_KEYS].sort()).toEqual([...keys].sort());
    // The two that reached the weekly issue of 2026-11-09.
    expect(keys).toContain("f9292b96ff7d66abe0820bbe9bd022cb0abd37bd673d7b462e1bba84245a9f66");
    expect(keys).toContain("12ca17b49af2289436f303e0166030a21e525d266e209267433801a8fd4071a0");
  });

  it("are the same in the weekly issue's script as in the cloud", () => {
    expect([...issueKeys].sort()).toEqual([...TEST_HOST_KEYS].sort());
  });
});
