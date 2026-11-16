// SQLite through its C interface, and the database file's trip to the app's private storage and back.
//
// The database is a file in the WebAssembly module's in-memory file system. Chromium 76 has no
// synchronous file storage a page can reach (the Origin Private File System came in 86, its
// synchronous handles in 102), so "kept in private storage" means: after a Sync the file is
// copied, 4 MB at a time, into IndexedDB, and on launch it is copied back. An installed Tizen
// app's IndexedDB lives in that app's own data folder.
var Db = (function () {
  var ROW = 100, DONE = 101, TRANSIENT = -1, NULL_TYPE = 5;
  var CHUNK = 4 * 1024 * 1024;
  var PATH = "/catalog.db";

  /** Loads SQLite from the base64 WebAssembly in sqlite3-wasm.js. */
  function load() {
    var text = atob(SQLITE_WASM_BASE64);
    var bytes = new Uint8Array(text.length);
    for (var i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
    return createSqlite({ wasmBinary: bytes.buffer });
  }

  function open(M) {
    var scratchSize = 8192, scratch = M._malloc(scratchSize);
    function utf8(text) {
      var most = text.length * 4 + 1;
      if (most > scratchSize) {
        M._free(scratch);
        scratchSize = most * 2;
        scratch = M._malloc(scratchSize);
      }
      return M.stringToUTF8(text, scratch, scratchSize);
    }

    utf8(PATH);
    var out = M._malloc(4);
    var opened = M._sqlite3_open(scratch, out);
    var db = M.HEAPU8[out] | (M.HEAPU8[out + 1] << 8) | (M.HEAPU8[out + 2] << 16) | (M.HEAPU8[out + 3] << 24);
    if (opened !== 0) throw new Error("SQLite could not open the database: " + opened);

    function failure(sql) { return new Error(M.UTF8ToString(M._sqlite3_errmsg(db)) + " in: " + sql.slice(0, 200)); }

    function exec(sql) {
      var length = M.lengthBytesUTF8(sql) + 1, at = M._malloc(length);
      M.stringToUTF8(sql, at, length);
      var result = M._sqlite3_exec(db, at, 0, 0, 0);
      M._free(at);
      if (result !== 0) throw failure(sql);
    }

    function prepare(sql) {
      var length = M.lengthBytesUTF8(sql) + 1, at = M._malloc(length);
      M.stringToUTF8(sql, at, length);
      var result = M._sqlite3_prepare_v2(db, at, -1, out, 0);
      M._free(at);
      if (result !== 0) throw failure(sql);
      var statement = M.HEAPU8[out] | (M.HEAPU8[out + 1] << 8) | (M.HEAPU8[out + 2] << 16) | (M.HEAPU8[out + 3] << 24);
      return {
        text: function (index, value) {
          if (value === null) M._sqlite3_bind_null(statement, index);
          else M._sqlite3_bind_text(statement, index, scratch, utf8(value), TRANSIENT);
        },
        // Every whole number the catalog stores fits 32 bits, so no 64-bit integer has to cross into
        // WebAssembly (Chromium only does that from 85).
        int: function (index, value) {
          if (value === null) M._sqlite3_bind_null(statement, index);
          else M._sqlite3_bind_int(statement, index, value);
        },
        real: function (index, value) {
          if (value === null) M._sqlite3_bind_null(statement, index);
          else M._sqlite3_bind_double(statement, index, value);
        },
        /** Steps to the next row; false once there is none. */
        step: function () {
          var stepped = M._sqlite3_step(statement);
          if (stepped === ROW) return true;
          if (stepped === DONE) return false;
          throw failure(sql);
        },
        run: function () {
          if (M._sqlite3_step(statement) !== DONE) throw failure(sql);
          M._sqlite3_reset(statement);
        },
        reset: function () { M._sqlite3_reset(statement); },
        columns: function () { return M._sqlite3_column_count(statement); },
        getText: function (column) { return M._sqlite3_column_type(statement, column) === NULL_TYPE ? null : M.UTF8ToString(M._sqlite3_column_text(statement, column)); },
        getInt: function (column) { return M._sqlite3_column_int(statement, column); },
        getReal: function (column) { return M._sqlite3_column_double(statement, column); },
        close: function () { M._sqlite3_finalize(statement); },
      };
    }

    /** Runs [sql] with [binds] (text or whole numbers), reading every column of every row as the Core would; returns how many rows. */
    function read(sql, binds) {
      var statement = prepare(sql), rows = 0;
      (binds || []).forEach(function (value, i) {
        if (typeof value === "number") statement.int(i + 1, value); else statement.text(i + 1, value);
      });
      var columns = statement.columns();
      while (statement.step()) {
        rows++;
        for (var c = 0; c < columns; c++) statement.getText(c);
      }
      statement.close();
      return rows;
    }

    function value(sql) {
      var statement = prepare(sql);
      var found = statement.step() ? statement.getText(0) : null;
      statement.close();
      return found;
    }

    return {
      exec: exec, prepare: prepare, read: read, value: value,
      close: function () {
        var closed = M._sqlite3_close(db);
        M._free(scratch);
        M._free(out);
        if (closed !== 0) throw new Error("SQLite could not close the database: " + closed);
      },
    };
  }

  function fileSize(M) {
    try { return M.FS.stat(PATH).size; } catch (e) { return 0; }
  }
  function removeFile(M) {
    try { M.FS.unlink(PATH); } catch (e) { /* there was none */ }
  }

  // --- IndexedDB: one store of the file's chunks, one of small records. ---

  function request(r) {
    return new Promise(function (resolve, reject) {
      r.onsuccess = function () { resolve(r.result); };
      r.onerror = function () { reject(r.error || new Error("IndexedDB request failed")); };
    });
  }
  function done(transaction) {
    return new Promise(function (resolve, reject) {
      transaction.oncomplete = function () { resolve(); };
      transaction.onerror = transaction.onabort = function () { reject(transaction.error || new Error("IndexedDB transaction failed")); };
    });
  }
  var storePromise = null;
  function store() {
    if (!storePromise) {
      var opening = indexedDB.open("sqlite-timing", 1);
      opening.onupgradeneeded = function () {
        opening.result.createObjectStore("chunk");
        opening.result.createObjectStore("record");
      };
      storePromise = request(opening);
    }
    return storePromise;
  }
  async function record(key) {
    var idb = await store();
    return request(idb.transaction("record").objectStore("record").get(key));
  }
  async function setRecord(key, value) {
    var idb = await store();
    var transaction = idb.transaction("record", "readwrite");
    if (value === undefined) transaction.objectStore("record").delete(key); else transaction.objectStore("record").put(value, key);
    return done(transaction);
  }

  /** Copies the database file into IndexedDB, a chunk per transaction so no more than one is held at once. Returns its size. */
  async function save(M, facts) {
    var idb = await store();
    var size = fileSize(M), chunks = Math.ceil(size / CHUNK);
    var clearing = idb.transaction(["chunk", "record"], "readwrite");
    clearing.objectStore("chunk").clear();
    clearing.objectStore("record").delete("saved");
    await done(clearing);
    var stream = M.FS.open(PATH, "r");
    try {
      for (var i = 0; i < chunks; i++) {
        var length = Math.min(CHUNK, size - i * CHUNK);
        var bytes = new Uint8Array(length);
        M.FS.read(stream, bytes, 0, length, i * CHUNK);
        var transaction = idb.transaction("chunk", "readwrite");
        transaction.objectStore("chunk").put(bytes.buffer, i);
        await done(transaction);
      }
    } finally {
      M.FS.close(stream);
    }
    // Written last: a save the app was closed during leaves no "saved" record, so it is never taken for a whole file.
    await setRecord("saved", Object.assign({ size: size, chunks: chunks, at: Date.now() }, facts));
    return size;
  }

  /** What [save] recorded, or undefined when nothing whole is kept. */
  function saved() { return record("saved"); }

  /** Copies the kept database back into the in-memory file system. Returns its size. */
  async function restore(M) {
    var idb = await store();
    var kept = await saved();
    if (!kept) throw new Error("No database is kept");
    removeFile(M);
    var stream = M.FS.open(PATH, "w");
    try {
      for (var i = 0; i < kept.chunks; i++) {
        var buffer = await request(idb.transaction("chunk").objectStore("chunk").get(i));
        if (!buffer) throw new Error("Chunk " + i + " of the kept database is missing");
        var bytes = new Uint8Array(buffer);
        M.FS.write(stream, bytes, 0, bytes.length, i * CHUNK);
      }
    } finally {
      M.FS.close(stream);
    }
    if (fileSize(M) !== kept.size) throw new Error("The kept database came back " + fileSize(M) + " bytes, not " + kept.size);
    return kept.size;
  }

  async function forget() {
    var idb = await store();
    var transaction = idb.transaction(["chunk", "record"], "readwrite");
    transaction.objectStore("chunk").clear();
    transaction.objectStore("record").delete("saved");
    return done(transaction);
  }

  return {
    load: load, open: open, fileSize: fileSize, removeFile: removeFile,
    save: save, saved: saved, restore: restore, forget: forget, record: record, setRecord: setRecord,
  };
})();
