package dev.betteriptv.app

import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import dev.betteriptv.core.CatalogNotSaved
import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.MatchingStatus
import dev.betteriptv.core.ProviderProblem
import dev.betteriptv.core.SyncProblem
import dev.betteriptv.core.SyncStatus
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** The Sync status line: how the Sync is going, or how long ago the last one finished, and how [matching] is going after it. */
internal fun Context.describe(status: SyncStatus, matching: MatchingStatus? = null): String = when (status) {
    is SyncStatus.NeverSynced -> getString(R.string.sync_never)
    is SyncStatus.Running -> running(status)
    is SyncStatus.Ready -> synced(status.last)
    is SyncStatus.Synced -> synced(status.last) + (matching?.let { " " + describe(it) }.orEmpty())
    is SyncStatus.Failed -> status.last?.let { getString(R.string.sync_failed, describe(status.problem), ago(it)) }
        ?: getString(R.string.sync_failed_empty, describe(status.problem))
}

/** How Matching is going: finding the names to check, or how many are checked. */
internal fun Context.describe(matching: MatchingStatus): String =
    matching.of?.let { resources.getQuantityString(R.plurals.sync_matching, it, count(matching.asked), count(it)) }
        ?: getString(R.string.sync_matching_start)

/** A first Sync says so; a later one tells how far it is by the last one's counts. */
private fun Context.running(status: SyncStatus.Running): String {
    val last = status.last ?: return getString(R.string.sync_first)
    val fraction = status.fraction ?: return getString(R.string.sync_running, ago(last))
    return getString(R.string.sync_running_progress, NumberFormat.getPercentInstance().format(fraction), ago(last))
}

private fun Context.synced(last: CompletedSync): String =
    getString(R.string.sync_done, ago(last), String.format(Locale.getDefault(), "%.1f", last.duration.toMillis() / 1000.0))

private fun Context.count(n: Int): String = NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(n)

private fun Context.describe(problem: SyncProblem): String = when (problem) {
    is ProviderProblem -> describe(problem)
    is CatalogNotSaved -> getString(R.string.catalog_not_saved, problem.detail)
}

private fun Context.ago(sync: CompletedSync): String = ago(sync.finishedAt, Instant.now())

/**
 * How long ago a Sync finished, worded to sit mid-sentence: "il y a 5 minutes", not the
 * capitalised "Il y a 5 minutes" DateUtils gives for the start of one.
 */
internal fun Context.ago(then: Instant, now: Instant): String {
    val elapsed = Duration.between(then, now)
    val (amount, unit) = when {
        elapsed < Duration.ofMinutes(1) -> return getString(R.string.sync_just_now)
        elapsed < Duration.ofHours(1) -> elapsed.toMinutes() to RelativeUnit.MINUTES
        elapsed < Duration.ofDays(1) -> elapsed.toHours() to RelativeUnit.HOURS
        elapsed < Duration.ofDays(7) -> elapsed.toDays() to RelativeUnit.DAYS
        elapsed < Duration.ofDays(30) -> elapsed.toDays() / 7 to RelativeUnit.WEEKS
        elapsed < Duration.ofDays(365) -> elapsed.toDays() / 30 to RelativeUnit.MONTHS
        else -> elapsed.toDays() / 365 to RelativeUnit.YEARS
    }
    val formatter = RelativeDateTimeFormatter.getInstance(
        ULocale.forLocale(resources.configuration.locales[0]), null,
        RelativeDateTimeFormatter.Style.LONG, DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
    )
    return formatter.format(amount.toDouble(), RelativeDateTimeFormatter.Direction.LAST, unit)
}

/** The catalog's Title counts: "12,345 movies · 3,210 shows". */
internal fun Context.catalogCounts(movies: Int, shows: Int): String =
    getString(
        R.string.catalog_counts,
        resources.getQuantityString(R.plurals.catalog_movies, movies, count(movies)),
        resources.getQuantityString(R.plurals.catalog_shows, shows, count(shows)),
    )
