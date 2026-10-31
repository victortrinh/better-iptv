-- The Cron Trigger fills the Charts and /v1/charts only reads them (#214). New `chart` sources:
-- 'netflix-week', a Region's newest week of Netflix's Top 10 as names (key '*': the worldwide one);
-- 'netflix-scan', when Netflix's country file was last read whole. Source 'netflix' with key '*' is
-- the worldwide Top 10, found on TMDB.

-- The Regions (part 'region') and App languages (part 'language') /v1/charts was asked for, and
-- when it last was: the Cron Trigger keeps their Charts fresh.
CREATE TABLE chart_wanted (
  part TEXT NOT NULL,
  key TEXT NOT NULL,
  asked_at INTEGER NOT NULL,
  PRIMARY KEY (part, key)
);

-- When the Cron Trigger last started each part it fills (`genres/fr`, `discover/movie/28`…), so a
-- part that stopped its run, or whose source was down, steps aside a while.
CREATE TABLE chart_try (
  part TEXT PRIMARY KEY,
  tried_at INTEGER NOT NULL
);
