-- The device list and "Delete my account" (#434).

-- The model a device named when it signed in ("Fire TV Stick 4K Max"), for the Account's device list.
-- Sent with the Sign-in code and nowhere else; cleared when the device signs out.
ALTER TABLE device ADD COLUMN model TEXT;
ALTER TABLE sign_in_code ADD COLUMN model TEXT;
ALTER TABLE magic_link ADD COLUMN model TEXT;

-- A "Delete my account" link sent, by a hash of its token, for 15 minutes and one use.
CREATE TABLE delete_link (
  token_hash TEXT PRIMARY KEY,
  account_id INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at INTEGER
);
CREATE INDEX delete_link_by_account ON delete_link (account_id);

-- A delete link asked for on the page, by hashes of the phone's IP and of the address, kept 15 minutes:
-- 5 an IP and 3 an address, so the page can't fill an inbox.
CREATE TABLE delete_request (
  ip_hash TEXT NOT NULL,
  email_hash TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX delete_request_by_ip ON delete_request (ip_hash, at);
CREATE INDEX delete_request_by_email ON delete_request (email_hash, at);
