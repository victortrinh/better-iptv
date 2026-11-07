-- An Account (ADR 0015): an email address, signed in to by magic link. No password, ever.
CREATE TABLE account (
  id INTEGER PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL
);

-- The Account a device is signed in to, and since when. Null: a device that never signed in, as before.
-- The device token stays the credential: signing in binds it, it is never replaced.
ALTER TABLE device ADD COLUMN account_id INTEGER REFERENCES account (id);
ALTER TABLE device ADD COLUMN signed_in_at INTEGER;
CREATE INDEX device_by_account ON device (account_id) WHERE account_id IS NOT NULL;

-- The Sign-in code a TV shows, one per device at a time, for 10 minutes. [sends] counts the magic links sent with it.
CREATE TABLE sign_in_code (
  code TEXT PRIMARY KEY,
  device_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  sends INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX sign_in_code_by_device ON sign_in_code (device_hash);

-- A wrong code typed on the sign-in page, by a hash of the phone's IP, kept 10 minutes: a sixth is refused.
CREATE TABLE sign_in_miss (
  ip_hash TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX sign_in_miss_by_ip ON sign_in_miss (ip_hash, at);

-- A magic link sent, by a hash of its token, for 15 minutes and one use. It signs [device_hash] in to [email]'s Account.
CREATE TABLE magic_link (
  token_hash TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  code TEXT NOT NULL,
  device_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at INTEGER
);
