-- The teams kept before #305 carry ESPN's default logos, which vanish on the cards' dark glass when
-- black (the Raiders'). Dropping them has the Cron Trigger's next run read them again, with the
-- logos ESPN marks for dark backgrounds, instead of within the week.
DELETE FROM team;
