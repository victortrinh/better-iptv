-- Everything a Profile owns syncs with the Account, and the Profiles themselves (#440).

-- Progress becomes per Profile. A Profile is named by the id its device made for it ([profile], 16 hex
-- digits), the same on every TV once it syncs. #435's rows name no Profile: they are dropped, and each
-- signed-in device pushes all of its history again at its first sync with #440 (it merges anew).
DROP TABLE progress;
CREATE TABLE progress (
  account_id INTEGER NOT NULL REFERENCES account (id) ON DELETE CASCADE,
  profile TEXT NOT NULL,
  provider TEXT NOT NULL,
  title TEXT NOT NULL,
  season INTEGER NOT NULL,
  episode INTEGER NOT NULL,
  position INTEGER NOT NULL,
  duration INTEGER,
  watched INTEGER NOT NULL,
  removed INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  PRIMARY KEY (account_id, profile, provider, title, season, episode)
);
CREATE INDEX progress_by_seq ON progress (account_id, seq);
-- No reset is answered for the rows dropped: a #435 app landing one would forget its own history. Its
-- pushes name no Profile, so they are turned down (400) until it updates.

-- The rest of what the Account keeps, one row per thing, the latest change winning (as progress):
--   kind 'profile': a Profile ([profile]), its value {"name": …, "kids": …}; null once deleted, which takes
--     the Profile's other rows with it and turns down any pushed after;
--   'favourite' (key: a Channel key) and 'not_this_game' (key: Game key, '|', Channel key), per Provider;
--   'setting' (key: a setting's name; value as the device writes it) and 'title_choices' (key: a Title
--     key; per Provider when it is a Listing's), per Profile;
--   'household' (no Profile; key 'region' or 'pin', the Household PIN's salted hash, never its digits).
-- [value] is null for a removal (a Favourite taken off, the PIN reset). Removals are kept: they are few.
CREATE TABLE synced (
  account_id INTEGER NOT NULL REFERENCES account (id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  profile TEXT NOT NULL,
  provider TEXT NOT NULL,
  key TEXT NOT NULL,
  value TEXT,
  updated_at INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  PRIMARY KEY (account_id, kind, profile, provider, key)
);
CREATE INDEX synced_by_seq ON synced (account_id, seq);

-- The Profile each device is on, as it last said.
ALTER TABLE device ADD COLUMN profile TEXT;

-- "Reset the Household PIN" sends a link as "Delete my account" does: one table for both, by purpose.
ALTER TABLE delete_link ADD COLUMN purpose TEXT NOT NULL DEFAULT 'delete';
