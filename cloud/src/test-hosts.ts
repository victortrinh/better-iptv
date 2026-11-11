/**
 * The Provider keys of hosts only tests serve (#545): the Mac's own loopback addresses, where JVM tests
 * and local runs reach FakeProvider, and `10.0.2.2`, the Android emulator's address for the Mac. Devices
 * on such a host aren't reading a real Provider's catalog, so what they report of it (unknown Prefixes,
 * candidate Tier tags, file reads) is dropped instead of kept for the curator and for learning. Each key
 * is the SHA-256 of the host as the app hashes it (CloudClient.kt's `providerKey`); the weekly issue's
 * script keeps the same list (scripts/weekly-issue.mjs), and test/test-hosts.test.ts checks both.
 */
export const TEST_HOST_KEYS: ReadonlySet<string> = new Set([
  "49960de5880e8c687434170f6476605b8fe4aeb9a28632c7995cf3ba831d9763", // localhost
  "12ca17b49af2289436f303e0166030a21e525d266e209267433801a8fd4071a0", // 127.0.0.1
  "eff8e7ca506627fe15dda5e0e512fcaad70b6d520f37cc76597fdb4f2d83a1a3", // ::1
  "f9292b96ff7d66abe0820bbe9bd022cb0abd37bd673d7b462e1bba84245a9f66", // 10.0.2.2, the emulator's FakeProvider
]);

/** Whether [provider] is the key of a host only tests serve. */
export const isTestHostKey = (provider: string): boolean => TEST_HOST_KEYS.has(provider);
