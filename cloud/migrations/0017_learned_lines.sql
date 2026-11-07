-- Learned lines (ADR 0014). A file-read report now carries the Listing's Prefix (the code before " - " in
-- its name, never the name) and the Category hints that matched it, as a JSON list of hint words. Reads
-- kept before carry neither, and teach nothing.
ALTER TABLE file_read ADD COLUMN prefix TEXT;
ALTER TABLE file_read ADD COLUMN hints TEXT;
CREATE INDEX file_read_by_prefix ON file_read (provider, prefix, read_at);

-- What a Provider's reads say of each Prefix, one row per attribute (language, quality, hdr, dolby-audio),
-- recomputed on every report that touches the Prefix (src/learned-lines.ts). value is the attribute's most
-- common value among the reads that tell it, agreeing how many of the reads carry it, shares every value's
-- count as JSON. status is 'learned' (served in the Provider table's [learned prefixes] section),
-- 'split' (enough reads, no agreement: kept for the weekly issue) or 'gathering' (too few reads yet).
-- withdrawn_at is when a learned line last dropped under the bar.
CREATE TABLE learned_line (
  provider TEXT NOT NULL,
  prefix TEXT NOT NULL,
  attribute TEXT NOT NULL,
  value TEXT,
  reads INTEGER NOT NULL,
  agreeing INTEGER NOT NULL,
  shares TEXT NOT NULL,
  status TEXT NOT NULL,
  learned_at INTEGER,
  withdrawn_at INTEGER,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (provider, prefix, attribute)
);
