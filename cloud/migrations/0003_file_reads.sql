-- What a Provider's movie Listing's file holds, as a device Header read it or played it, shared with every
-- Viewer of that Provider. provider is a SHA-256 of the Provider's host, so the cloud never learns it.
-- body is the device's JSON: audio and subtitle Tracks, picture size, HDR format and bitrate.
CREATE TABLE file_read (
  provider TEXT NOT NULL,
  listing_id INTEGER NOT NULL,
  body TEXT NOT NULL,
  read_at INTEGER NOT NULL,
  PRIMARY KEY (provider, listing_id)
);
