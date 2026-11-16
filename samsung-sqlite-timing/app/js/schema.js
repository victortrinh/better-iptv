// The Core's catalog tables, copied by hand from core/.../CatalogStore.kt at SCHEMA_VERSION 7
// (November 2026). The timings only mean something while these match the Core: if the Core's
// tables have changed since, copy them again before trusting a new run.
var Schema = (function () {
  var DETAILS = [
    ["rating", "REAL"], ["plot", "TEXT"], ["genre", "TEXT"], ["actors", "TEXT"], ["director", "TEXT"],
    ["release_date", "TEXT"], ["backdrops", "TEXT"], ["trailer", "TEXT"],
  ];
  var DETAILS_NAMES = DETAILS.map(function (c) { return c[0]; }).join(", ");
  var DETAILS_TYPED = DETAILS.map(function (c) { return c[0] + " " + c[1]; }).join(", ");

  function named(shape, name) { return shape.replace("%s", name); }

  var LISTING_TABLE =
    "CREATE TABLE IF NOT EXISTS %s (kind TEXT NOT NULL, position INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, " +
    "added INTEGER, image TEXT, title INTEGER NOT NULL, title_key TEXT NOT NULL, title_number INTEGER, " +
    "container TEXT, " + DETAILS_TYPED + ", PRIMARY KEY (kind, position))";
  var CHANNEL_TABLE =
    "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, position INTEGER NOT NULL, name TEXT NOT NULL, image TEXT, event_start INTEGER, " +
    "category_id TEXT, guide_id TEXT NOT NULL, channel_key TEXT NOT NULL, channel_name TEXT NOT NULL, country TEXT, quality TEXT, hevc INTEGER NOT NULL, " +
    "lead INTEGER NOT NULL)";
  var LIVE_CATEGORY_TABLE =
    "CREATE TABLE IF NOT EXISTS %s (id TEXT PRIMARY KEY, position INTEGER NOT NULL, name TEXT NOT NULL, normalised_name TEXT NOT NULL, country TEXT, kind TEXT NOT NULL)";
  var REPLAY_TABLE =
    "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, position INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, added INTEGER, " +
    "container TEXT, away TEXT, home TEXT, date TEXT, year INTEGER)";
  var TITLE_TABLE = "CREATE TABLE IF NOT EXISTS %s (kind TEXT NOT NULL, position INTEGER NOT NULL, key TEXT NOT NULL, PRIMARY KEY (kind, position))";
  var TITLE_FACTS_TABLE =
    "CREATE TABLE IF NOT EXISTS %s (title_key TEXT PRIMARY KEY, found INTEGER NOT NULL, original_language TEXT, imdb_id TEXT, " +
    "enwiki TEXT, frwiki TEXT, fetched_at INTEGER NOT NULL)";
  var UNKNOWN_PREFIX_TABLE = "CREATE TABLE IF NOT EXISTS %s (prefix TEXT PRIMARY KEY, listings INTEGER NOT NULL, example TEXT NOT NULL)";
  var TIER_TAG_TABLE = "CREATE TABLE IF NOT EXISTS %s (word TEXT PRIMARY KEY, listings INTEGER NOT NULL, example TEXT NOT NULL)";
  var HISTORY_CHANGE_TABLE =
    "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, " +
    "position INTEGER NOT NULL, duration INTEGER, watched INTEGER NOT NULL, removed INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
    "PRIMARY KEY (profile, title_key, season, number))";

  var PROFILE_TABLES = [
    ["favourite", "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, key TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (profile, key))"],
    ["not_this_game", "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, game_key TEXT NOT NULL, channel_key TEXT NOT NULL, dropped_at INTEGER NOT NULL, PRIMARY KEY (profile, game_key, channel_key))"],
    ["progress", "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, position INTEGER NOT NULL, duration INTEGER, watched INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (profile, title_key))"],
    ["episode_progress", "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, position INTEGER NOT NULL, duration INTEGER, watched INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (profile, title_key, season, number))"],
    ["title_choices", "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, body TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (profile, title_key))"],
    ["profile_setting", "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, key TEXT NOT NULL, value TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY (profile, key))"],
    ["remembered_track", "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (profile, kind))"],
    ["history_outbox", HISTORY_CHANGE_TABLE],
    ["history_pulled", HISTORY_CHANGE_TABLE],
    ["history_tombstone", "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, removed_at INTEGER NOT NULL, PRIMARY KEY (profile, title_key, season, number))"],
  ];

  var TITLE_INDEXES = [
    "CREATE INDEX IF NOT EXISTS listing_title ON listing (kind, title)",
    "CREATE UNIQUE INDEX IF NOT EXISTS title_key ON title (key)",
    "CREATE INDEX IF NOT EXISTS replay_position ON replay (position)",
    "CREATE INDEX IF NOT EXISTS replay_date ON replay (date)",
  ];
  var CHANNEL_INDEXES = [
    "CREATE INDEX IF NOT EXISTS channel_position ON channel (position)",
    "CREATE INDEX IF NOT EXISTS channel_key ON channel (channel_key, position)",
    "CREATE INDEX IF NOT EXISTS channel_category ON channel (category_id, channel_key)",
    "CREATE INDEX IF NOT EXISTS channel_lead ON channel (position) WHERE lead = 1",
    "CREATE INDEX IF NOT EXISTS channel_event_start ON channel (event_start)",
  ];

  // What the Core's CatalogStore runs when it opens a new database, less the steps that only move an older one forward.
  function create() {
    var sql = [
      "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
      "PRAGMA user_version = 7",
      named(LISTING_TABLE, "listing"),
      named(TITLE_TABLE, "title"),
      named(CHANNEL_TABLE, "channel"),
      named(LIVE_CATEGORY_TABLE, "live_category"),
      named(REPLAY_TABLE, "replay"),
    ].concat(TITLE_INDEXES, CHANNEL_INDEXES, [
      named(UNKNOWN_PREFIX_TABLE, "unknown_prefix"),
      named(TIER_TAG_TABLE, "tier_tag"),
      "CREATE TABLE IF NOT EXISTS profile (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, kids INTEGER NOT NULL, created_at INTEGER NOT NULL, used_at INTEGER NOT NULL, uid TEXT, changed_at INTEGER NOT NULL DEFAULT 0)",
      "CREATE TABLE IF NOT EXISTS household_pin (id INTEGER PRIMARY KEY CHECK (id = 1), hash TEXT NOT NULL, failures INTEGER NOT NULL, locked_until INTEGER, changed_at INTEGER NOT NULL DEFAULT 1)",
      "CREATE TABLE IF NOT EXISTS household (key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER NOT NULL)",
      "CREATE TABLE IF NOT EXISTS sync_outbox (kind TEXT NOT NULL, profile TEXT NOT NULL, provider TEXT NOT NULL, key TEXT NOT NULL, value TEXT, at INTEGER NOT NULL, PRIMARY KEY (kind, profile, provider, key))",
      "CREATE TABLE IF NOT EXISTS sync_pulled (kind TEXT NOT NULL, profile TEXT NOT NULL, key TEXT NOT NULL, value TEXT, at INTEGER NOT NULL, PRIMARY KEY (kind, profile, key))",
    ]);
    PROFILE_TABLES.forEach(function (table) { sql.push(named(table[1], table[0])); });
    sql.push(
      "INSERT INTO profile (id, name, kids, created_at, used_at) SELECT 1, NULL, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM profile)",
      named(TITLE_FACTS_TABLE, "title_facts"),
      named(TITLE_FACTS_TABLE, "title_facts_pending"),
      "CREATE INDEX IF NOT EXISTS title_facts_by_enwiki ON title_facts (enwiki)",
      "CREATE INDEX IF NOT EXISTS title_facts_by_frwiki ON title_facts (frwiki)",
      "CREATE TABLE IF NOT EXISTS trending (language TEXT PRIMARY KEY, body TEXT NOT NULL, fetched_at INTEGER NOT NULL)",
      "CREATE TABLE IF NOT EXISTS game (league TEXT PRIMARY KEY, body TEXT NOT NULL, fetched_at INTEGER NOT NULL)",
      "CREATE TABLE IF NOT EXISTS show_details (listing_id INTEGER PRIMARY KEY, modified INTEGER, fetched_at INTEGER NOT NULL)",
      "CREATE TABLE IF NOT EXISTS file_read (listing_id INTEGER PRIMARY KEY, body TEXT, read_at INTEGER NOT NULL, shared INTEGER NOT NULL)",
      "CREATE TABLE IF NOT EXISTS movie_details (listing_id INTEGER PRIMARY KEY, bitrate INTEGER, fetched_at INTEGER NOT NULL, " + DETAILS_TYPED + ", runtime INTEGER, added INTEGER)",
      "CREATE TABLE IF NOT EXISTS episode (listing_id INTEGER NOT NULL, id INTEGER NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, " +
        "title TEXT NOT NULL, added INTEGER, width INTEGER, height INTEGER, audio_language TEXT, container TEXT, still TEXT, duration INTEGER, PRIMARY KEY (listing_id, id))"
    );
    return sql;
  }

  return {
    create: create,
    named: named,
    DETAILS: DETAILS,
    DETAILS_NAMES: DETAILS_NAMES,
    LISTING_TABLE: LISTING_TABLE,
    CHANNEL_TABLE: CHANNEL_TABLE,
    LIVE_CATEGORY_TABLE: LIVE_CATEGORY_TABLE,
    REPLAY_TABLE: REPLAY_TABLE,
    TITLE_TABLE: TITLE_TABLE,
    UNKNOWN_PREFIX_TABLE: UNKNOWN_PREFIX_TABLE,
    TIER_TAG_TABLE: TIER_TAG_TABLE,
    TITLE_INDEXES: TITLE_INDEXES,
    CHANNEL_INDEXES: CHANNEL_INDEXES,
    STAGING_CATEGORY_TABLE: "CREATE TABLE staging_category (kind TEXT, id TEXT, name TEXT NOT NULL, PRIMARY KEY (kind, id))",
    STAGING_LISTING_TABLE:
      "CREATE TABLE staging_listing (kind TEXT, id INTEGER, name TEXT, category_id TEXT, added INTEGER, hidden INTEGER, image TEXT, title_key TEXT, " +
      "title_number INTEGER, year INTEGER, container TEXT, " + DETAILS_NAMES + ")",
    TITLES_BY_NEWEST_LISTING:
      "SELECT kind, row_number() OVER (PARTITION BY kind ORDER BY max(added) DESC, min(name), min(id)), title_key FROM %s GROUP BY kind, title_key",
    LISTING_KEY: "kind || '/listing/' || id",
    LISTING_COLUMNS: "l.id, l.name, l.category, l.added, l.image, " + DETAILS.map(function (c) { return "l." + c[0]; }).join(", "),
    CHANNEL_COLUMNS: "c.channel_key, c.channel_name, c.country, f.key, c.id, c.image, c.name, c.quality, c.hevc, c.category_id, c.guide_id",
  };
})();
