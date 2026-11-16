# SQLite as WebAssembly, timed with a catalog-sized load

A throwaway page for issue #572. ADR 0020 keeps one SQLite design on every platform, and on a
Samsung TV that means SQLite compiled to WebAssembly inside the set's Chromium 76. Nobody has
measured that. This page does a first Sync's worth of SQLite work on an invented catalog of the
real size and prints how long each step took and how much memory it needed. Issue #573 is the
owner running it on a real set.

It is not product code. Nothing in `core/`, `cloud/` or `app/` uses it, and it can be deleted
once #573 has its numbers.

## What it does

1. Loads SQLite 3.54.1 (WebAssembly).
2. Creates the Core's catalog tables, copied from `CatalogStore.kt` at schema version 7 (`app/js/schema.js`).
3. Runs a first Sync with the Core's own SQL: stores 46,000 Channel variants and swaps them in,
   stores 180,000 movie and show Listings (144,000 and 36,000), builds each Title number's year,
   splits off Mismatches, builds the Titles, builds the kept Listings, swaps them in.
4. Builds a full-text search index (FTS5), which ADR 0020 plans. The Core has no such table yet, so its shape here is a guess.
5. Makes the reads Home and search make: the newest Titles, Continue watching, the Kids catalog,
   the Live tab's Event channels and countries, a Category's Channels, today's whole-catalog name
   scan for the search index, and three full-text searches.
6. Saves the database to the app's private storage, reads it back, opens it and checks it.
7. When the app is opened again, finds the saved database, opens it and says so on screen.

The catalog is made up by a seeded generator (`app/js/catalog.js`): invented names, Categories,
image addresses and plots, the same rows every run. No Provider is contacted and no Provider data
is in it. The page asks the network for nothing at all.

## What Chromium 76 rules out, and what was chosen

| Wanted | Chromium 76 | So |
|---|---|---|
| A real file SQLite can read and write in place (the Origin Private File System) | `getDirectory` came in 86, synchronous handles in 102 | The database is a file in memory, copied to IndexedDB in 4 MB pieces after a Sync and copied back at launch |
| The sqlite.org WebAssembly build, or `sql.js` 1.16 as shipped | Their JavaScript needs newer syntax (`sql.js` 1.16 only parses as ECMAScript 2022), `sql.js` has no FTS5, and passing 64-bit integers to WebAssembly as BigInt came in 85 | SQLite's own amalgamation, built here with Emscripten 5.0.7 for `MIN_CHROME_VERSION=76`, FTS5 on, 64-bit integers kept off the boundary |
| The current Emscripten | 6.0.0 raised the oldest Chrome it can target from 74 to 85 | Pinned to 5.0.7, the last release that can target 76 (`build/build.sh`) |
| Newer WebAssembly (multi-value, SIMD, reference types, exceptions) | 85, 91, 96, 95 | Not used. The build is first-version WebAssembly only; even the three additions 76 does have are left off, which cost about 7% on a desktop |
| Optional chaining, `??`, `replaceAll`, `Array.at`, top-level await | 80, 80, 85, 92, 89 | The page's own scripts are written to ECMAScript 2017 |

Two things this found that the Samsung work proper will meet again:

- Emscripten 5.0.7 transpiles its output for Chromium 76 but then wraps it in one line that uses
  optional chaining, a syntax error on 76 that stops the whole file loading. `build.sh` rewrites that line.
- With the database in memory, the file's whole size sits in the page's memory beside SQLite's
  own, and the Core's staging tables double the file for a moment during a Sync. If memory is what
  fails on the set, the next thing to try is a file SQLite reads in place through Tizen's own
  file API (`tizen.filesystem.openFile`, synchronous since Tizen 5.0). That is not built here.

Where the page differs from the Core on purpose: one connection instead of a reader and a writer,
and an in-memory journal instead of the write-ahead log, since there is no real file to sync to.

## Checked against Chromium 76 how

By a syntax and feature check, not by running a Chromium 76. `check/check.js` verifies that:

- every script the page loads parses as ECMAScript 2019, which Chromium 76 fully has;
- no script names an API newer than 76 (a short list: `replaceAll`, `.at(`, `Object.hasOwn`, `structuredClone`, `WeakRef`, the Origin Private File System, BigInt arrays);
- the WebAssembly validates with every feature off except the four 76 has (and reports that it uses none of the four);
- no function between JavaScript and WebAssembly takes or returns a 64-bit integer.

```bash
cd check && npm install && npm run check
```

A parser can't see everything: a missing method that isn't on the list, or a behaviour that
differs on Samsung's own build of Chromium, would only show on the set. The page shows any error
on screen, with the step it stopped in, because a TV has no console to read.

## Run it on a computer

```bash
node serve.js
```

Then open http://localhost:8572/ in Chrome or any Chromium. The run starts by itself. Reload the
page to see it find the saved database. Keep the tab in front: a hidden tab runs several times slower.

## Install it on the TV

Written from Samsung's developer documentation in November 2026. It has not been rehearsed on a
set, so where a screen differs from what is written here, trust the screen. Allow an hour the
first time; most of it is installing Samsung's tools and making a certificate.

You need: the Samsung TV and its remote, this Mac on the same home network as the TV, and a free
Samsung account (the certificate step signs in with it).

### 1. Note the TV's model and its IP address

1. On the TV: **Settings → Support → About this TV**. Write down the model code (for #573).
2. **Settings → General → Network → Network Status → IP Settings** (the menu names vary a little by year). Write down the IP address, something like `192.168.4.50`.

### 2. Find this Mac's IP address

```bash
ipconfig getifaddr en0
```

If that prints nothing, the Mac is on a wired connection: use `en1` instead of `en0`.

### 3. Turn on Developer mode on the TV

1. Press **Home**, go to **Apps**.
2. With the Apps screen open, type **1 2 3 4 5** on the remote. A remote without number keys has a **123** button that brings up an on-screen keypad. A "Developer mode" box appears.
3. Switch **Developer mode** to **On**.
4. In **Host PC IP**, enter the Mac's address from step 2. Choose **OK**.
5. Restart the TV: hold the remote's power button until the Samsung logo shows, or unplug the set for ten seconds. Turning it off and on with a short press is not enough.
6. Open **Apps** again. "Develop mode" is now written at the top.

### 4. Install Samsung's tools on the Mac

1. Download **Tizen Studio** (the version "with IDE installer", for macOS) and install it. Samsung's page with the current link is https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/installing-tv-sdk.html. Leave the install folder as `~/tizen-studio`. On an Apple Silicon Mac it runs through Rosetta; if macOS offers to install Rosetta, accept.
2. Open **Package Manager** (it opens at the end of the install; otherwise it is in `~/tizen-studio/package-manager`).
3. On the **Extension SDK** tab, click **install** beside **Samsung Certificate Extension** and beside **TV Extensions** (the newest one listed). On the **Main SDK** tab, make sure **Web CLI** under Tizen SDK tools is installed.

Samsung is moving these tools into a Visual Studio Code extension ("Tizen TV Extension"). If
Tizen Studio will not run on your Mac, that extension can connect to the TV, make the same
certificate and install a `.wgt` too; the steps below then map onto its Connect Device, Create
Certificate and Run commands.

### 5. Connect the Mac to the TV

1. Open **Tizen Studio**, then **Tools → Device Manager**.
2. Click **Remote Device Manager**, then **+**. Give it any name, the TV's IP address from step 1, and port **26101**. Click **Add**.
3. Switch its **Connection** to **On**. The TV appears in the device list.
4. Right-click the TV in the list and choose **Permit to install applications**.

If it will not connect: the Host PC IP on the TV must be this Mac's address exactly, the TV must
have been restarted after step 3, and both must be on the same network (not a guest Wi-Fi).

### 6. Make a certificate

A TV only installs a package signed for that one TV.

1. In Tizen Studio: **Tools → Certificate Manager**. If it asks about migrating profiles, **Cancel**. Click **+**.
2. Choose **Samsung**, then **TV**, then **Next**. Name the profile `zugig` and click **Next**.
3. **Create a new author certificate → Next**. Fill in a name and a password, sign in with the Samsung account when asked, and click **Next**.
4. **Create a new distributor certificate → Next**. Leave the privilege level at **Public**. The TV's ID (DUID) should already be listed because the TV is connected; if the list is empty, click **+** beside the connected device. Click **Next**, then **Finish**.

### 7. Install and start the page

From this folder:

```bash
./install-on-tv.sh 192.168.4.50 zugig
```

Put the TV's own address in place of `192.168.4.50`. The script connects, packages `app/` with
the certificate, installs it and starts it. The same by hand, if a step fails and you want to see which:

```bash
~/tizen-studio/tools/sdb connect 192.168.4.50:26101
```

```bash
~/tizen-studio/tools/sdb devices
```

```bash
~/tizen-studio/tools/ide/bin/tizen package -t wgt -s zugig -- app
```

```bash
~/tizen-studio/tools/ide/bin/tizen install -n "SQLite timing.wgt" -s 192.168.4.50:26101 -- app
```

```bash
~/tizen-studio/tools/ide/bin/tizen run -p ZugigSqlT1.SqliteTiming -s 192.168.4.50:26101
```

(The package's file name is whatever `tizen package` says it wrote into `app/`.)

If the install fails:

- **"install failed [118, -12]"** or **error 1010**: the certificate. Make it again (step 6) with the TV connected so its DUID is in it, and use a Samsung profile, not a Tizen one.
- **"Author certificate not match"**: an earlier copy signed differently is on the TV. Remove it (step 9) and install again.
- **"failed to connect to remote target"**: see the end of step 5.

### 8. Run it twice and record the numbers (issue #573)

1. The run starts by itself when the app opens. The blue line at the top says what it is doing. Leave the TV alone until it says **"Run 1 is done"**. On a desktop the whole run is a few seconds; on the set nobody knows yet, which is the point. If nothing has changed on screen for ten minutes, photograph it as it is.
2. Photograph the whole screen.
3. Close the app: press **Back** once; the page exits. Open it again from **Apps** (it is at the end of the list, named "SQLite timing").
4. It now reads the saved database. A green line should say **"The database survived closing the app"**. Photograph the screen: the left table is this launch, the right one is run 1.
5. With **Run again from scratch** highlighted, press **OK**. Wait for **"Run 2 is done"** and photograph the screen again, then close and reopen once more for run 2's green line.
6. Post the photographs, or the numbers typed out, on #573, with the model code from step 1. The lines that matter most are **First Sync, all of the above**, **Save the database**, **From launch to Home read**, and the **Memory** rows.

If a red line appears instead, photograph that: it names the step that failed and why, and that is a result too.

### 9. Remove it afterwards

1. On the TV: **Apps**, hold **OK** on "SQLite timing", choose **Delete**. Or from the Mac: `~/tizen-studio/tools/ide/bin/tizen uninstall -p ZugigSqlT1.SqliteTiming -s 192.168.4.50:26101`.
2. To leave Developer mode: **Apps**, type **1 2 3 4 5** again, switch it **Off**, restart the TV.

### A first look without a certificate

The TV's own Internet browser can open the page from the Mac, with no tools and no certificate:

```bash
HOST=0.0.0.0 node serve.js
```

Then, in the TV's browser, go to `http://<the Mac's address>:8572/`. This is a quick look, not
the measurement: the page's first line shows which Chromium the browser is, which may not be the
one installed apps get, and the model and the TV's free memory are not shown there.

## Reading the numbers

- **First Sync, all of the above** is SQLite's work for a whole first Sync. It leaves out what a real Sync also does: downloading the Provider's lists and parsing their JSON.
- **+ … making rows** beside the two store steps is the page inventing the catalog. It is not part of the step's time.
- **SQLite's own memory** is WebAssembly memory, which only grows, so the figure is the true peak. **Database file in memory** is the file itself, held by the page beside it. Their sum is the memory this design needs at the worst moment of a Sync.
- **JavaScript heap (Chromium's figure)** is `performance.memory`, which Chromium rounds and which may or may not count the file. Treat it as a cross-check.
- **TV memory free, lowest seen** (on a set only) is the whole TV's free memory at its lowest during the run.

## Files

- `app/`: what is installed on the TV. `index.html`, `style.css`, `config.xml` (the Tizen manifest), `js/app.js` (the run), `js/catalog.js` (the invented catalog), `js/schema.js` (the Core's tables), `js/db.js` (SQLite and storage), `js/sqlite3.js` and `js/sqlite3-wasm.js` (SQLite, built).
- `build/build.sh`: rebuilds SQLite. Not needed to run anything.
- `check/`: the Chromium 76 check.
- `serve.js`: serves `app/` to a browser on this computer.
- `install-on-tv.sh`: step 7 above.
