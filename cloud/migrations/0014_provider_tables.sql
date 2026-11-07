-- Provider tables (ADR 0014): what one Provider's names mean beyond the app's Base table, served to
-- its devices by POST /v1/provider-table. provider is a SHA-256 of the Provider's host, as for file
-- reads. hand is the Provider's hand file from cloud/provider-tables/, loaded on deploy
-- (scripts/provider-tables.mjs); learned lines come in a later ticket.
CREATE TABLE provider_table (
  provider TEXT PRIMARY KEY,
  hand TEXT NOT NULL,
  loaded_at INTEGER NOT NULL
);
