// The timing run: a first Sync of an invented catalog through the Core's own SQL, the reads Home
// and search make, the file's trip to private storage and back, each step timed and shown.
(function () {
  var BATCH = 4000;
  var NOW = 1794700000; // 15 November 2026, the "now" the Event channel reads are asked about.

  var state = { status: "Starting…", error: null, device: "", current: null, runs: [], launch: null, busy: true, focus: 0, held: false };
  var M = null;
  var peaks = { js: 0, lowestFree: null };

  function now() { return performance.now(); }
  function pause() { return new Promise(function (resolve) { setTimeout(resolve, 4); }); }

  function sample() {
    if (performance.memory && performance.memory.usedJSHeapSize > peaks.js) peaks.js = performance.memory.usedJSHeapSize;
    try {
      if (window.tizen && tizen.systeminfo && tizen.systeminfo.getAvailableMemory) {
        var free = tizen.systeminfo.getAvailableMemory();
        if (peaks.lowestFree === null || free < peaks.lowestFree) peaks.lowestFree = free;
      }
    } catch (e) { /* not on a TV */ }
  }

  function describeDevice() {
    var chrome = /Chrom(?:e|ium)\/([0-9.]+)/.exec(navigator.userAgent);
    var tizenVersion = /Tizen ([0-9.]+)/.exec(navigator.userAgent);
    var parts = [chrome ? "Chromium " + chrome[1] : navigator.userAgent];
    if (tizenVersion) parts.push("Tizen " + tizenVersion[1]);
    try {
      if (window.webapis && webapis.productinfo) parts.push("model " + webapis.productinfo.getRealModel() + " (" + webapis.productinfo.getModelCode() + ")");
    } catch (e) { /* the model stays unknown */ }
    try {
      if (window.tizen && tizen.systeminfo && tizen.systeminfo.getTotalMemory) parts.push("TV memory " + megabytes(tizen.systeminfo.getTotalMemory()));
    } catch (e) { /* unknown */ }
    if (performance.memory) parts.push("JavaScript heap limit " + megabytes(performance.memory.jsHeapSizeLimit));
    return parts.join(" · ");
  }

  // --- A run's record ---

  function newRecord(number) { return { number: number, at: Date.now(), device: state.device, groups: [], memory: [] }; }
  function group(record, name) {
    var g = { name: name, steps: [] };
    record.groups.push(g);
    return g;
  }
  function add(g, name, ms, note) {
    g.steps.push({ name: name, ms: ms, note: note || "" });
    sample();
    render();
  }
  /** Times [work], which doesn't wait on anything, as a step of [g]. */
  async function timed(g, name, work) {
    state.status = name + "…";
    render();
    await pause();
    var started = now();
    var note = work();
    add(g, name, now() - started, note);
    await pause();
  }
  function total(g, name, steps) {
    var ms = 0;
    g.steps.forEach(function (s) { if (!steps || steps.indexOf(s.name) >= 0) ms += s.ms; });
    g.steps.push({ name: name, ms: ms, note: "", total: true });
  }

  // --- The Sync, as the Core's CatalogStore runs it ---

  async function channelsStage(db, g) {
    db.exec("DROP TABLE IF EXISTS staging_channel");
    db.exec("DROP TABLE IF EXISTS staging_live_category");
    db.exec(Schema.named(Schema.CHANNEL_TABLE, "staging_channel"));
    db.exec(Schema.named(Schema.LIVE_CATEGORY_TABLE, "staging_live_category"));
    var source = Catalog.channels(), made = 0, making = 0, inserting = 0, position = 0;
    db.exec("BEGIN");
    var category = db.prepare("INSERT OR REPLACE INTO staging_live_category VALUES (?, ?, ?, ?, ?, ?)");
    Catalog.liveCategories().forEach(function (c, i) {
      category.text(1, c.id); category.int(2, i + 1); category.text(3, c.name); category.text(4, c.normalisedName); category.text(5, c.country); category.text(6, c.kind);
      category.run();
    });
    category.close();
    var insert = db.prepare("INSERT OR REPLACE INTO staging_channel VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)");
    for (;;) {
      var started = now();
      var rows = source.next(BATCH);
      var generated = now();
      for (var i = 0; i < rows.length; i++) {
        var c = rows[i];
        insert.int(1, c.id); insert.int(2, ++position); insert.text(3, c.name); insert.text(4, c.image); insert.int(5, c.event); insert.text(6, c.categoryId);
        insert.text(7, c.guideId); insert.text(8, c.key); insert.text(9, c.channelName); insert.text(10, c.country); insert.text(11, c.quality); insert.int(12, c.hevc ? 1 : 0);
        insert.run();
      }
      making += generated - started;
      inserting += now() - generated;
      made += rows.length;
      if (!rows.length) break;
      state.status = "Channels: " + made.toLocaleString() + " of " + source.total.toLocaleString() + " variants stored";
      sample();
      render();
      await pause();
    }
    insert.close();
    var committing = now();
    db.exec("COMMIT");
    inserting += now() - committing;
    add(g, "Channels: store " + made.toLocaleString() + " variants", inserting, "+ " + duration(making) + " making rows");

    await timed(g, "Channels: swap in, indexes, leads", function () {
      db.exec("BEGIN");
      db.exec("DROP TABLE channel");
      db.exec("ALTER TABLE staging_channel RENAME TO channel");
      db.exec("DROP TABLE live_category");
      db.exec("ALTER TABLE staging_live_category RENAME TO live_category");
      Schema.CHANNEL_INDEXES.forEach(db.exec);
      db.exec("UPDATE channel SET lead = 1 WHERE position IN (SELECT min(position) FROM channel GROUP BY channel_key)");
      db.exec("INSERT OR REPLACE INTO meta VALUES ('channels_synced_at', '" + NOW + "')");
      db.exec("COMMIT");
    });
  }

  async function titlesStage(db, g, record) {
    ["staging_category", "staging_listing", "staging_kept_listing", "staging_title", "staging_replay", "staging_unknown_prefix", "staging_tier_tag"].forEach(function (table) {
      db.exec("DROP TABLE IF EXISTS " + table);
    });
    db.exec(Schema.named(Schema.REPLAY_TABLE, "staging_replay"));
    db.exec(Schema.STAGING_CATEGORY_TABLE);
    db.exec(Schema.STAGING_LISTING_TABLE);
    db.exec(Schema.named(Schema.LISTING_TABLE, "staging_kept_listing"));
    db.exec(Schema.named(Schema.TITLE_TABLE, "staging_title"));
    db.exec(Schema.named(Schema.UNKNOWN_PREFIX_TABLE, "staging_unknown_prefix"));
    db.exec(Schema.named(Schema.TIER_TAG_TABLE, "staging_tier_tag"));

    db.exec("BEGIN");
    var category = db.prepare("INSERT OR REPLACE INTO staging_category VALUES (?, ?, ?)");
    var categoryNames = {};
    Catalog.movieCategories().concat(Catalog.showCategories()).forEach(function (c) {
      category.text(1, c.kind); category.text(2, c.id); category.text(3, c.name);
      category.run();
      categoryNames[c.kind + c.id] = c.name;
    });
    category.close();
    var insert = db.prepare("INSERT INTO staging_listing VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    var replay = db.prepare("INSERT OR REPLACE INTO staging_replay VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    var sources = [Catalog.movies(), Catalog.shows()], made = 0, making = 0, inserting = 0;
    var all = sources[0].total + sources[1].total;
    for (var s = 0; s < sources.length; s++) {
      for (;;) {
        var started = now();
        var rows = sources[s].next(BATCH);
        var generated = now();
        for (var i = 0; i < rows.length; i++) {
          var l = rows[i];
          insert.text(1, l.kind); insert.int(2, l.id); insert.text(3, l.name); insert.text(4, l.categoryId); insert.int(5, l.added); insert.int(6, l.hidden ? 1 : 0);
          insert.text(7, l.image); insert.text(8, l.number === null ? l.kind + "/listing/" + l.id : l.kind + "/" + l.number); insert.int(9, l.number); insert.int(10, l.year);
          insert.text(11, l.container); insert.real(12, l.rating); insert.text(13, l.plot); insert.text(14, l.genre); insert.text(15, l.actors); insert.text(16, l.director);
          insert.text(17, l.release); insert.text(18, l.backdrops); insert.text(19, l.trailer);
          insert.run();
          if (l.replay) {
            replay.int(1, l.id); replay.int(2, l.replay.position); replay.text(3, l.name); replay.text(4, categoryNames[l.kind + l.categoryId]); replay.int(5, l.added);
            replay.text(6, l.container); replay.text(7, l.replay.away); replay.text(8, l.replay.home); replay.text(9, l.replay.date); replay.int(10, 2026);
            replay.run();
          }
        }
        making += generated - started;
        inserting += now() - generated;
        made += rows.length;
        if (!rows.length) break;
        state.status = "Titles: " + made.toLocaleString() + " of " + all.toLocaleString() + " Listings stored";
        sample();
        render();
        await pause();
      }
    }
    insert.close();
    replay.close();
    add(g, "Titles: store " + made.toLocaleString() + " Listings", inserting, "+ " + duration(making) + " making rows");

    await timed(g, "Titles: number-year build", function () {
      db.exec("CREATE TABLE staging_number_year (kind TEXT, title_number INTEGER, year INTEGER, PRIMARY KEY (kind, title_number))");
      db.exec(
        "INSERT INTO staging_number_year SELECT kind, title_number, year FROM (" +
        "SELECT kind, title_number, year, row_number() OVER (PARTITION BY kind, title_number ORDER BY count(*) DESC, year) AS rank " +
        "FROM staging_listing WHERE hidden = 0 AND title_number IS NOT NULL AND year IS NOT NULL GROUP BY kind, title_number, year" +
        ") WHERE rank = 1"
      );
    });
    await timed(g, "Titles: Mismatch update", function () {
      db.exec(
        "UPDATE staging_listing SET title_key = " + Schema.LISTING_KEY + " WHERE hidden = 0 AND year IS NOT NULL AND abs(year - (" +
        "SELECT n.year FROM staging_number_year n WHERE n.kind = staging_listing.kind AND n.title_number = staging_listing.title_number" +
        ")) > 1"
      );
      db.exec("DROP TABLE staging_number_year");
    });
    await timed(g, "Titles: Title insert", function () {
      db.exec("INSERT INTO staging_title (kind, position, key) " + Schema.named(Schema.TITLES_BY_NEWEST_LISTING, "staging_listing WHERE hidden = 0"));
    });
    await timed(g, "Titles: Title key index", function () {
      db.exec("CREATE INDEX staging_title_key ON staging_title (key)");
    });
    await timed(g, "Titles: kept-Listing insert", function () {
      db.exec(
        "INSERT INTO staging_kept_listing (kind, position, id, name, category, added, image, title, title_key, title_number, container, " + Schema.DETAILS_NAMES + ") " +
        "SELECT l.kind, row_number() OVER (PARTITION BY l.kind ORDER BY l.added DESC, l.name, l.id), l.id, l.name, coalesce(c.name, ''), l.added, l.image, " +
        "t.position, l.title_key, l.title_number, l.container, " + Schema.DETAILS.map(function (c) { return "l." + c[0]; }).join(", ") + " " +
        "FROM staging_listing l LEFT JOIN staging_category c ON c.kind = l.kind AND c.id = l.category_id JOIN staging_title t ON t.key = l.title_key WHERE l.hidden = 0"
      );
    });
    // The file is at its largest here: the Listings as the Provider sent them and as kept, side by side.
    record.largestFile = Db.fileSize(M);
    await timed(g, "Titles: staging drop and commit", function () {
      db.exec("DROP INDEX staging_title_key");
      db.exec("DROP TABLE staging_listing");
      db.exec("DROP TABLE staging_category");
      db.exec("COMMIT");
      db.exec("BEGIN");
      var prefix = db.prepare("INSERT INTO staging_unknown_prefix VALUES (?, ?, ?)");
      var tierTag = db.prepare("INSERT INTO staging_tier_tag VALUES (?, ?, ?)");
      for (var i = 0; i < 40; i++) {
        prefix.text(1, "P" + i); prefix.int(2, 40 - i); prefix.text(3, "P" + i + " - Example");
        prefix.run();
        tierTag.text(1, "T" + i); tierTag.int(2, 40 - i); tierTag.text(3, "Example T" + i);
        tierTag.run();
      }
      prefix.close();
      tierTag.close();
      db.exec("INSERT OR REPLACE INTO meta VALUES ('pending_sync_at', '" + NOW + "')");
      db.exec("COMMIT");
    });
    await timed(g, "Titles: swap in, indexes", function () {
      db.exec("BEGIN IMMEDIATE");
      db.exec("DROP TABLE listing");
      db.exec("DROP TABLE title");
      db.exec("ALTER TABLE staging_kept_listing RENAME TO listing");
      db.exec("ALTER TABLE staging_title RENAME TO title");
      db.exec("DROP TABLE replay");
      db.exec("ALTER TABLE staging_replay RENAME TO replay");
      Schema.TITLE_INDEXES.forEach(db.exec);
      db.exec("DELETE FROM show_details WHERE listing_id NOT IN (SELECT id FROM listing WHERE kind = 'show')");
      db.exec("DELETE FROM episode WHERE listing_id NOT IN (SELECT listing_id FROM show_details)");
      db.exec("DELETE FROM unknown_prefix");
      db.exec("INSERT INTO unknown_prefix SELECT * FROM staging_unknown_prefix");
      db.exec("DROP TABLE staging_unknown_prefix");
      db.exec("DELETE FROM tier_tag");
      db.exec("INSERT INTO tier_tag SELECT * FROM staging_tier_tag");
      db.exec("DROP TABLE staging_tier_tag");
      db.exec("INSERT OR REPLACE INTO meta VALUES ('last_sync_at', '" + NOW + "')");
      db.exec("DELETE FROM meta WHERE key LIKE 'pending_sync_%'");
      db.exec("COMMIT");
    });
  }

  // ADR 0020 moves search to SQLite's full-text index, built during a Sync. The Core has no such
  // table yet, so this one is a guess at its shape: every kept Listing's name and every Channel's.
  async function searchIndex(db, g) {
    await timed(g, "Search index (FTS5) build", function () {
      db.exec("DROP TABLE IF EXISTS search");
      db.exec("CREATE VIRTUAL TABLE search USING fts5(name, kind UNINDEXED, key UNINDEXED, prefix = '2 3')");
      db.exec("BEGIN");
      db.exec("INSERT INTO search (name, kind, key) SELECT name, kind, title_key FROM listing");
      db.exec("INSERT INTO search (name, kind, key) SELECT channel_name, 'channel', channel_key FROM channel WHERE lead = 1");
      db.exec("COMMIT");
      return "a guess at ADR 0020's table";
    });
  }

  function seedViewer(db) {
    // What a Viewer leaves behind, so the reads below have something to join: 20 Titles in progress, 12 Favourites.
    db.exec("BEGIN");
    db.exec("DELETE FROM progress");
    db.exec("DELETE FROM favourite");
    db.exec("INSERT INTO progress SELECT 1, key, 600000, 5400000, 0, " + NOW + " - position FROM title WHERE position % 3000 = 7 LIMIT 20");
    db.exec("INSERT OR IGNORE INTO favourite SELECT 1, channel_key, " + NOW + " FROM channel WHERE lead = 1 AND position % 2500 = 3 LIMIT 12");
    db.exec("COMMIT");
  }

  /** The reads Home and search make, each timed once, straight after the Sync. */
  async function reads(db, g) {
    function titlesPage(kind, offset, limit) {
      return db.read(
        "SELECT t.key, " + Schema.LISTING_COLUMNS + " FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position " +
        "WHERE t.kind = ? AND t.position > ? AND t.position <= ? ORDER BY t.position", [kind, offset, offset + limit]);
    }
    await timed(g, "Home: newest 60 Movies and 60 Shows", function () {
      return (titlesPage("movie", 0, 60) + titlesPage("show", 0, 60)) + " Listings";
    });
    await timed(g, "Home: Continue watching", function () {
      var played = db.read(
        "SELECT title_key, updated_at FROM progress WHERE profile = 1 AND watched = 0 " +
        "UNION ALL SELECT title_key, MAX(updated_at) FROM episode_progress WHERE profile = 1 GROUP BY title_key ORDER BY 2 DESC LIMIT ?", [20]);
      var keys = [], statement = db.prepare("SELECT title_key FROM progress WHERE profile = 1");
      while (statement.step()) keys.push(statement.getText(0));
      statement.close();
      var marks = keys.map(function () { return "?"; }).join(",");
      db.read("SELECT key, kind, position FROM title WHERE key IN (" + marks + ")", keys);
      var listings = db.read(
        "SELECT t.key, " + Schema.LISTING_COLUMNS + " FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position WHERE t.key IN (" + marks + ")", keys);
      return played + " Titles, " + listings + " Listings";
    });
    await timed(g, "Kids: Categories, Titles under 8", function () {
      var names = [], statement = db.prepare("SELECT DISTINCT category FROM listing");
      while (statement.step()) names.push(statement.getText(0));
      statement.close();
      var eight = names.slice(0, 8);
      var titles = db.read(
        "SELECT DISTINCT t.key, t.kind, t.position FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position " +
        "WHERE l.category IN (" + eight.map(function () { return "?"; }).join(",") + ")", eight);
      return names.length + " Categories, " + titles + " Titles";
    });
    await timed(g, "Live: Event channels on, countries", function () {
      var events = db.read(
        "SELECT " + Schema.CHANNEL_COLUMNS + ", c.event_start FROM channel c LEFT JOIN favourite f ON f.profile = 1 AND f.key = c.channel_key " +
        "WHERE c.channel_key IN (SELECT channel_key FROM channel WHERE (event_start >= ? AND event_start < ?) OR name LIKE 'Live%|%' OR name LIKE 'Next%|%') " +
        "ORDER BY c.position", [NOW - 4 * 3600, NOW + 24 * 3600]);
      var countries = db.read(
        "SELECT DISTINCT lc.country, c.channel_key FROM live_category lc JOIN channel c ON c.category_id = lc.id " +
        "WHERE lc.kind = ? AND lc.country IS NOT NULL AND (c.event_start IS NULL OR c.event_start >= ?)", ["regular", NOW]);
      return events + " variants, " + countries + " country rows";
    });
    await timed(g, "Live: Favourites, 3 Categories", function () {
      var favourites = db.read(
        "SELECT " + Schema.CHANNEL_COLUMNS + " FROM favourite f JOIN channel c ON c.channel_key = f.key " +
        "WHERE f.profile = 1 AND (c.event_start IS NULL OR c.event_start >= ?) ORDER BY f.added_at, f.key, c.position", [NOW]);
      var filed = db.read(
        "SELECT " + Schema.CHANNEL_COLUMNS + ", c.position FROM channel c LEFT JOIN favourite f ON f.profile = 1 AND f.key = c.channel_key " +
        "WHERE c.channel_key IN (SELECT channel_key FROM channel WHERE category_id IN (?, ?, ?)) AND (c.event_start IS NULL OR c.event_start >= ?) ORDER BY c.position",
        ["1", "2", "3", NOW]);
      return (favourites + filed) + " variants";
    });
    await timed(g, "Search today: read every name", function () {
      // What the Core's in-memory search index reads on every launch, a page of 2,000 Titles at a time.
      var rows = 0;
      ["movie", "show"].forEach(function (kind) {
        var last = Number(db.value("SELECT coalesce(max(position), 0) FROM title WHERE kind = '" + kind + "'"));
        for (var offset = 0; offset < last; offset += 2000) {
          rows += db.read(
            "SELECT t.key, l.name, l.category, CASE WHEN l.kind = 'movie' THEN d.genre ELSE l.genre END, CASE WHEN l.kind = 'movie' THEN d.actors ELSE l.actors END " +
            "FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position LEFT JOIN movie_details d ON l.kind = 'movie' AND d.listing_id = l.id " +
            "WHERE t.kind = ? AND t.position > ? AND t.position <= ? ORDER BY t.position", [kind, offset, offset + 2000]);
        }
      });
      for (var position = 0; ;) {
        var statement = db.prepare("SELECT position, id, channel_name, country FROM channel WHERE lead = 1 AND position > ? ORDER BY position LIMIT 2000");
        statement.int(1, position);
        var page = 0;
        while (statement.step()) { page++; position = statement.getInt(0); statement.getText(2); statement.getText(3); }
        statement.close();
        rows += page;
        if (page < 2000) break;
      }
      return rows.toLocaleString() + " rows";
    });
    var word = Catalog.commonWord();
    await timed(g, "Search (FTS5): three searches", function () {
      var found = 0;
      ['"' + word.slice(0, 2) + '"*', '"' + word + '"*', '"' + word + '" "' + word.slice(0, 2) + '"*'].forEach(function (match) {
        found += db.read("SELECT key, kind, name FROM search WHERE search MATCH ? ORDER BY rank LIMIT 30", [match]);
      });
      return found + " results";
    });
  }

  function counts(db) {
    return {
      listings: Number(db.value("SELECT count(*) FROM listing")),
      titles: Number(db.value("SELECT count(*) FROM title")),
      channels: Number(db.value("SELECT count(*) FROM channel")),
    };
  }
  function sameCounts(a, b) { return a.listings === b.listings && a.titles === b.titles && a.channels === b.channels; }

  function configure(db) {
    // The Core keeps a write-ahead log beside a real file. Here the file is in memory and there is
    // nothing to sync to, so the journal stays in memory too; durability is the copy made by Db.save.
    db.exec("PRAGMA journal_mode = MEMORY");
    db.exec("PRAGMA synchronous = OFF");
  }

  function memory(record) {
    sample();
    var rows = [];
    // WebAssembly memory only grows, so its size now is the most the run ever used. The database
    // file is not in it: the in-memory file system keeps a file's bytes in JavaScript's memory.
    rows.push(["SQLite's own memory (WebAssembly), peak", megabytes(M.HEAPU8.length)]);
    rows.push(["Database file in memory, largest", megabytes(record.largestFile)]);
    rows.push(["Those two together", megabytes(M.HEAPU8.length + record.largestFile)]);
    rows.push(["Database file, as kept", megabytes(record.size)]);
    rows.push(["of it, free pages the staging left", megabytes(record.free)]);
    if (performance.memory) rows.push(["JavaScript heap (Chromium's figure), peak", megabytes(peaks.js)]);
    if (peaks.lowestFree !== null) rows.push(["TV memory free, lowest seen", megabytes(peaks.lowestFree)]);
    record.memory = rows;
  }

  async function fullRun() {
    var kept = (await Db.record("runs")) || [];
    var record = newRecord(kept.length ? kept[kept.length - 1].number + 1 : 1);
    state.current = record;
    var sync = group(record, "First Sync");
    add(sync, "Load SQLite (WebAssembly)", state.loadMs, "SQLite " + state.sqliteVersion);

    Db.removeFile(M);
    var db = Db.open(M);
    configure(db);
    await timed(sync, "Create the catalog tables", function () { Schema.create().forEach(db.exec); });
    await channelsStage(db, sync);
    await titlesStage(db, sync, record);
    await searchIndex(db, sync);
    total(sync, "First Sync, all of the above");

    seedViewer(db);
    var reading = group(record, "Reads, once each, straight after the Sync");
    await reads(db, reading);

    var storage = group(record, "Private storage");
    record.counts = counts(db);
    // The Core never vacuums, so the pages the staging tables filled stay in the file, free, and are saved with it.
    record.free = Number(db.value("PRAGMA freelist_count")) * Number(db.value("PRAGMA page_size"));
    db.close();
    state.status = "Saving the database to private storage…";
    render();
    await pause();
    var started = now();
    record.size = await Db.save(M, { counts: record.counts, run: record.number });
    add(storage, "Save the database (IndexedDB)", now() - started, megabytes(record.size));

    Db.removeFile(M);
    started = now();
    await Db.restore(M);
    add(storage, "Read it back from storage", now() - started);
    var reopened;
    await timed(storage, "Open, count rows, quick_check", function () {
      reopened = Db.open(M);
      configure(reopened);
      var found = counts(reopened);
      if (!sameCounts(found, record.counts)) throw new Error("The reopened database holds different rows: " + JSON.stringify(found));
      var check = reopened.value("PRAGMA quick_check");
      if (check !== "ok") throw new Error("quick_check says: " + check);
      return found.listings.toLocaleString() + " Listings, " + found.titles.toLocaleString() + " Titles";
    });
    await timed(storage, "Home again: newest 60 and 60", function () {
      return reopened.read(
        "SELECT t.key, " + Schema.LISTING_COLUMNS + " FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position " +
        "WHERE t.position > 0 AND t.position <= 60 ORDER BY t.kind, t.position") + " Listings";
    });
    reopened.close();

    memory(record);
    state.current = null;
    state.runs = kept.concat([record]).slice(-2);
    await Db.setRecord("runs", state.runs);
    state.status = "Run " + record.number + " is done. To check the database survives: close the app (press Back) and open it again.";
  }

  /** On a launch that finds a kept database: read it back and prove it is the one the last run saved. */
  async function relaunch(kept) {
    var record = newRecord(0);
    state.current = record;
    var g = group(record, "This launch");
    add(g, "Load SQLite (WebAssembly)", state.loadMs, "");
    var started = now();
    await Db.restore(M);
    add(g, "Read the kept database from storage", now() - started, megabytes(kept.size));
    var db;
    await timed(g, "Open, count rows, quick_check", function () {
      db = Db.open(M);
      configure(db);
      var found = counts(db);
      if (!sameCounts(found, kept.counts)) throw new Error("The kept database holds different rows: " + JSON.stringify(found));
      var check = db.value("PRAGMA quick_check");
      if (check !== "ok") throw new Error("quick_check says: " + check);
      return found.listings.toLocaleString() + " Listings, " + found.titles.toLocaleString() + " Titles";
    });
    await timed(g, "Home: newest 60 Movies and 60 Shows", function () {
      return db.read(
        "SELECT t.key, " + Schema.LISTING_COLUMNS + " FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position " +
        "WHERE t.position > 0 AND t.position <= 60 ORDER BY t.kind, t.position") + " Listings";
    });
    await timed(g, "Search (FTS5): a word", function () {
      return db.read("SELECT key, kind, name FROM search WHERE search MATCH ? ORDER BY rank LIMIT 30", ['"' + Catalog.commonWord() + '"*']) + " results";
    });
    db.close();
    total(g, "From launch to Home read");
    sample();
    record.memory = [["SQLite's own memory (WebAssembly)", megabytes(M.HEAPU8.length)], ["Database file in memory", megabytes(kept.size)]];
    if (performance.memory) record.memory.push(["JavaScript heap (Chromium's figure), peak", megabytes(peaks.js)]);
    if (peaks.lowestFree !== null) record.memory.push(["TV memory free, lowest seen", megabytes(peaks.lowestFree)]);
    record.survived = "The database survived closing the app: run " + kept.run + "'s " + kept.counts.listings.toLocaleString() +
      " Listings, saved " + new Date(kept.at).toLocaleString() + ", are all here.";
    state.current = null;
    state.launch = record;
    state.status = "Opened the kept database. Photograph this screen, then choose below.";
  }

  // --- The screen ---

  function megabytes(bytes) { return (bytes / 1048576).toFixed(bytes < 104857600 ? 1 : 0) + " MB"; }
  function duration(ms) { return ms >= 10000 ? (ms / 1000).toFixed(1) + " s" : ms >= 1000 ? (ms / 1000).toFixed(2) + " s" : ms >= 10 ? Math.round(ms) + " ms" : ms.toFixed(1) + " ms"; }
  function escape(text) { return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }

  function table(record, title) {
    var html = '<section><h2>' + escape(title) + "</h2><table>";
    record.groups.forEach(function (g) {
      html += '<tr class="group"><td colspan="3">' + escape(g.name) + "</td></tr>";
      g.steps.forEach(function (s) {
        html += "<tr" + (s.total ? ' class="total"' : "") + "><td>" + escape(s.name) + '</td><td class="time">' + duration(s.ms) + '</td><td class="note">' + escape(s.note) + "</td></tr>";
      });
    });
    if (record.memory.length) {
      html += '<tr class="group"><td colspan="3">Memory</td></tr>';
      record.memory.forEach(function (m) { html += "<tr><td>" + escape(m[0]) + '</td><td class="time">' + escape(m[1]) + '</td><td class="note"></td></tr>'; });
    }
    return html + "</table></section>";
  }

  var BUTTONS = [
    ["Run again from scratch", function () { return Db.forget().then(function () { location.reload(); }); }],
    ["Delete what is saved", function () {
      return Db.forget().then(function () { return Db.setRecord("runs", undefined); }).then(function () { return Db.setRecord("held", true); }).then(function () { location.reload(); });
    }],
  ];

  function render() {
    var html = "<header><h1>SQLite as WebAssembly: a catalog-sized first Sync</h1><p>" + escape(state.device) + "</p></header>";
    html += '<p class="status' + (state.error ? " error" : "") + '">' + escape(state.error || state.status) + "</p>";
    if (state.launch && state.launch.survived) html += '<p class="survived">' + escape(state.launch.survived) + "</p>";
    html += '<div class="columns">';
    if (state.current) html += table(state.current, state.current.number ? "Run " + state.current.number + ", running" : "This launch, running");
    if (state.launch) html += table(state.launch, "This launch: the kept database");
    state.runs.slice().reverse().forEach(function (run) {
      html += table(run, "Run " + run.number + ", " + new Date(run.at).toLocaleString());
    });
    html += "</div>";
    if (!state.busy) {
      html += "<footer>";
      BUTTONS.forEach(function (button, i) {
        if (state.held && i === 1) return;
        html += '<span class="button' + (i === state.focus ? " focused" : "") + '">' + escape(state.held && i === 0 ? "Run" : button[0]) + "</span>";
      });
      html += "<span>Left and right to choose, OK to do it.</span></footer>";
    }
    document.getElementById("screen").innerHTML = html;
  }

  function fail(e) {
    state.error = "Stopped: " + (e && e.message ? e.message : e) + " (while: " + state.status + ")";
    state.busy = false;
    render();
    report();
  }

  // For a run nobody is watching (a desktop check): ?report sends what the screen shows to the server the page came from.
  function report() {
    if (location.search.indexOf("report") < 0) return;
    var request = new XMLHttpRequest();
    request.open("POST", "/report");
    request.send(JSON.stringify({ error: state.error, device: state.device, launch: state.launch, runs: state.runs }, null, 1));
  }

  document.addEventListener("keydown", function (event) {
    if (event.keyCode === 10009) {
      try { tizen.application.getCurrentApplication().exit(); } catch (e) { /* not on a TV */ }
      return;
    }
    if (state.busy) return;
    if (event.keyCode === 37 || event.keyCode === 39) {
      if (!state.held) state.focus = (state.focus + (event.keyCode === 39 ? 1 : BUTTONS.length - 1)) % BUTTONS.length;
      render();
    } else if (event.keyCode === 13) {
      state.busy = true;
      state.status = BUTTONS[state.focus][0] + "…";
      render();
      Db.setRecord("held", undefined).then(BUTTONS[state.focus][1]).catch(fail);
    }
  });
  window.addEventListener("error", function (event) { fail(event.error || event.message); });
  window.addEventListener("unhandledrejection", function (event) { fail(event.reason); });

  async function main() {
    state.device = describeDevice();
    render();
    await pause();
    var started = now();
    M = await Db.load();
    state.loadMs = now() - started;
    state.sqliteVersion = M.UTF8ToString(M._sqlite3_libversion());
    state.runs = (await Db.record("runs")) || [];
    var kept = await Db.saved();
    state.held = !kept && !!(await Db.record("held"));
    if (kept) await relaunch(kept);
    else if (!state.held) await fullRun();
    else state.status = "Nothing is saved. Press OK to run.";
    state.busy = false;
    render();
    report();
  }

  main().catch(fail);
})();
