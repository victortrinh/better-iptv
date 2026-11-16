-- #570 (ADR 0020, ADR 0021): the platform a device said it is when it registered (fire-os, google-tv, android, ios,
-- macos, windows, linux or tizen) and the app version it ran, updated when it registers again. A device registered
-- before this, or by a build that says neither, is taken as Android, the only platform there was.
ALTER TABLE device ADD COLUMN platform TEXT NOT NULL DEFAULT 'android';
ALTER TABLE device ADD COLUMN app_version TEXT;
