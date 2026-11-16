// An invented catalog the size and shape of a real one: 144,000 movie Listings, 36,000 show
// Listings and 46,000 live Listings. Every name, Category, image address and plot is made from
// made-up syllables by a seeded generator, so each run loads the same rows and none of it is a
// Provider's data. What it keeps of the real thing is what costs time and room: how long names
// and image addresses are, how many Listings share a Title number, how many Categories there
// are, and that only show Listings carry a plot, cast and backdrops.
var Catalog = (function () {
  var MOVIES = 144000, SHOWS = 36000, CHANNELS = 46000;
  var MOVIE_CATEGORIES = 320, SHOW_CATEGORIES = 140, LIVE_CATEGORIES = 450;

  function random(seed) {
    return function () {
      seed = (seed + 0x6D2B79F5) | 0;
      var t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  function pick(r, list) { return list[Math.floor(r() * list.length)]; }
  function between(r, low, high) { return low + Math.floor(r() * (high - low + 1)); }

  var ONSETS = ["b", "br", "c", "ch", "d", "dr", "f", "fl", "g", "gr", "h", "j", "k", "l", "m", "n", "p", "pr", "r", "s", "sh", "st", "t", "tr", "v", "w", "z", ""];
  var VOWELS = ["a", "e", "i", "o", "u", "ai", "ou", "ea", "io", "é"];
  var CODAS = ["", "", "", "n", "r", "s", "l", "m", "x", "nd", "st", "th"];
  var WORDS = (function () {
    var r = random(7), words = [], seen = {};
    while (words.length < 4000) {
      var word = "";
      for (var s = between(r, 1, 3); s > 0; s--) word += pick(r, ONSETS) + pick(r, VOWELS) + pick(r, CODAS);
      if (word.length > 2 && !seen[word]) { seen[word] = true; words.push(word); }
    }
    return words;
  })();
  function capital(word) { return word.charAt(0).toUpperCase() + word.slice(1); }
  function words(r, low, high) {
    var out = [];
    for (var n = between(r, low, high); n > 0; n--) out.push(capital(pick(r, WORDS)));
    return out.join(" ");
  }
  function sentence(r, length) {
    var out = capital(pick(r, WORDS));
    while (out.length < length) out += " " + pick(r, WORDS);
    return out + ".";
  }
  var HEX = "0123456789abcdef";
  function hex(r, n) { var out = ""; while (n-- > 0) out += HEX.charAt(Math.floor(r() * 16)); return out; }
  function image(r, folder) { return "https://images.example.invalid/" + folder + "/w600_and_h900/" + hex(r, 28) + ".jpg"; }

  // Two-letter tags as a Provider prefixes names with, some with a quality in front.
  var TAGS = (function () {
    var r = random(11), tags = [], letters = "ABCDEFGHIJKLMNOPRSTUVZ";
    while (tags.length < 40) {
      var tag = letters.charAt(Math.floor(r() * letters.length)) + letters.charAt(Math.floor(r() * letters.length));
      if (tags.indexOf(tag) < 0) tags.push(tag);
    }
    return tags;
  })();
  function tag(r) { var t = pick(r, TAGS); return r() < 0.12 ? "4K-" + t : t; }

  function categories(kind, count, seed) {
    var r = random(seed), out = [];
    for (var i = 1; i <= count; i++) out.push({ kind: kind, id: String(i), name: pick(r, TAGS) + " - " + words(r, 1, 3).toUpperCase() });
    return out;
  }

  // How many Listings a Title has: most have one or two, a few have many language versions.
  function listingsOf(r) {
    var x = r();
    return x < 0.45 ? 1 : x < 0.70 ? 2 : x < 0.82 ? 3 : x < 0.90 ? 4 : x < 0.95 ? 6 : 8;
  }
  // Between 2019 and November 2026, more of them recent.
  function added(r) { return 1546300800 + Math.floor(Math.sqrt(r()) * 247000000); }

  /** Hands out [total] Listings of [kind] in batches; each call to next(n) returns up to n rows, [] when done. */
  function listings(kind, total) {
    var r = random(kind === "movie" ? 101 : 202);
    var movie = kind === "movie";
    var categoryCount = movie ? MOVIE_CATEGORIES : SHOW_CATEGORIES;
    var made = 0, titleIndex = 0, left = 0, title = null, replayPosition = 0;
    function nextTitle() {
      titleIndex++;
      left = listingsOf(r);
      title = {
        name: words(r, 1, 5),
        year: between(r, 1950, 2026),
        number: r() < 0.88 ? 1000 + titleIndex * 7 : null,
      };
      if (!movie) {
        title.genre = words(r, 1, 1) + " / " + words(r, 1, 1);
        title.actors = words(r, 2, 2) + ", " + words(r, 2, 2) + ", " + words(r, 2, 2) + ", " + words(r, 2, 2);
        title.director = words(r, 2, 2);
        title.release = title.year + "-" + ("0" + between(r, 1, 12)).slice(-2) + "-" + ("0" + between(r, 1, 28)).slice(-2);
        title.trailer = hex(r, 11);
      }
    }
    return {
      total: total,
      next: function (count) {
        var rows = [];
        while (rows.length < count && made < total) {
          if (left === 0) nextTitle();
          left--;
          made++;
          var id = (movie ? 100000 : 600000) + made;
          var row = {
            kind: kind, id: id, categoryId: String(between(r, 1, categoryCount)), added: added(r), image: r() < 0.93 ? image(r, "poster") : null,
            hidden: false, number: title.number, year: null, container: null, rating: null, replay: null,
            plot: null, genre: null, actors: null, director: null, release: null, backdrops: null, trailer: null,
          };
          // One movie Listing in fifty is a Sports replay: kept out of the lists, and stored as a Replay.
          if (movie && r() < 0.02) {
            var away = words(r, 1, 2), home = words(r, 1, 2);
            var date = "2026-" + ("0" + between(r, 1, 11)).slice(-2) + "-" + ("0" + between(r, 1, 28)).slice(-2);
            row.name = pick(r, TAGS) + " - " + away + " vs " + home + " " + date;
            row.hidden = true;
            row.number = null;
            row.container = "mkv";
            row.replay = { position: ++replayPosition, away: away, home: home, date: date };
            rows.push(row);
            continue;
          }
          var dated = r() < 0.8;
          // One dated Listing in fifty is filed under the wrong Title number: a Mismatch.
          row.year = !dated ? null : r() < 0.02 ? title.year - between(r, 3, 30) : title.year;
          row.name = tag(r) + " - " + title.name + (dated ? " (" + row.year + ")" : "");
          row.hidden = r() < 0.03;
          if (movie) {
            row.container = r() < 0.7 ? "mkv" : "mp4";
            row.rating = r() < 0.7 ? Math.round(r() * 100) / 10 : null;
          } else {
            row.rating = r() < 0.8 ? Math.round(r() * 100) / 10 : null;
            if (r() < 0.85) {
              row.plot = sentence(r, between(r, 120, 520));
              row.genre = title.genre;
              row.actors = title.actors;
              row.director = title.director;
              row.release = title.release;
              row.backdrops = JSON.stringify([image(r, "backdrop")]);
              row.trailer = r() < 0.5 ? title.trailer : null;
            }
          }
          rows.push(row);
        }
        return rows;
      },
    };
  }

  var QUALITIES = [null, "SD", "HD", "FullHD", "UHD"];
  function liveCategories() {
    var r = random(303), out = [];
    for (var i = 1; i <= LIVE_CATEGORIES; i++) {
      var country = r() < 0.85 ? pick(r, TAGS) : null;
      var name = words(r, 1, 3).toUpperCase();
      var x = r();
      out.push({
        id: String(i), name: (country ? country + " | " : "") + name, normalisedName: name.toLowerCase(), country: country,
        kind: x < 0.9 ? "regular" : x < 0.96 ? "event" : "clutter",
      });
    }
    return out;
  }
  /** Hands out the Channel variants in batches, like [listings]. */
  function channels(total) {
    var r = random(404), made = 0, left = 0, channel = null;
    return {
      total: total,
      next: function (count) {
        var rows = [];
        while (rows.length < count && made < total) {
          if (left === 0) {
            left = between(r, 1, 4);
            var country = r() < 0.9 ? pick(r, TAGS) : null;
            var name = words(r, 1, 3) + (r() < 0.3 ? " " + between(r, 1, 9) : "");
            // One Channel in thirty is an Event channel, some already over.
            var event = r() < 0.033 ? 1794700000 + between(r, -86400, 3 * 86400) : null;
            channel = {
              country: country, name: name, key: (country ? country.toLowerCase() + "/" : "") + name.toLowerCase().replace(/ /g, ""),
              categoryId: String(between(r, 1, LIVE_CATEGORIES)), event: event, image: r() < 0.8 ? image(r, "logo") : null,
              guideId: r() < 0.4 ? name.replace(/ /g, "") + "." + (country || "xx").toLowerCase() : "",
            };
          }
          left--;
          made++;
          var quality = pick(r, QUALITIES);
          rows.push({
            id: 1000000 + made, name: (channel.country ? channel.country + ": " : "") + channel.name + (quality ? " " + quality : ""),
            image: channel.image, event: channel.event, categoryId: channel.categoryId, guideId: channel.guideId, key: channel.key,
            channelName: channel.name, country: channel.country, quality: quality, hevc: r() < 0.15,
          });
        }
        return rows;
      },
    };
  }

  return {
    MOVIES: MOVIES, SHOWS: SHOWS, CHANNELS: CHANNELS,
    movieCategories: function () { return categories("movie", MOVIE_CATEGORIES, 21); },
    showCategories: function () { return categories("show", SHOW_CATEGORIES, 22); },
    liveCategories: liveCategories,
    movies: function () { return listings("movie", MOVIES); },
    shows: function () { return listings("show", SHOWS); },
    channels: function () { return channels(CHANNELS); },
    // A word that names many rows, for the search reads.
    commonWord: function () { return WORDS[0]; },
  };
})();
