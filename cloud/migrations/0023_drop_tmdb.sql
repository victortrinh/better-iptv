-- M6 (#481, ADR 0014): the cloud keeps none of TMDB's data, as TMDB's terms ask when use ends. The metadata, match,
-- search, upcoming, season and charts endpoints are gone; these are the tables they kept TMDB's answers in. Title facts
-- (Wikidata), Trending's days, Games, file reads, Prefixes, Provider tables, subtitles and Accounts are untouched.
DROP TABLE title_metadata;
DROP TABLE title_lookup;
DROP TABLE title_search;
DROP TABLE person_credits;
DROP TABLE upcoming_episodes;
DROP TABLE season_episodes;
DROP TABLE chart;
DROP TABLE chart_wanted;
DROP TABLE chart_try;
