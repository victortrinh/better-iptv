package dev.betteriptv.app

import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import dev.betteriptv.core.CatalogNotSaved
import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.ProviderProblem
import dev.betteriptv.core.SyncProblem
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep
import java.text.NumberFormat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock
import kotlin.time.Instant
import java.util.Locale

/** The Sync status line in Settings: how the Sync is going, or how long ago the last one finished. */
internal fun Context.describe(status: SyncStatus): String = when (status) {
    is SyncStatus.NeverSynced -> getString(R.string.sync_never)
    is SyncStatus.Running -> running(status)
    is SyncStatus.Ready -> getString(R.string.sync_ready, ago(status.computedAt, Clock.System.now()))
    is SyncStatus.Synced -> synced(status.last)
    is SyncStatus.Failed -> status.last?.let { getString(R.string.sync_failed, describe(status.problem), ago(it)) }
        ?: getString(R.string.sync_failed_empty, describe(status.problem))
}

/** A first Sync names its step and counts the entries read; a later one tells how far it is by the last one's counts. */
private fun Context.running(status: SyncStatus.Running): String {
    val last = status.last
    if (last == null) {
        val step = getString(status.step.label())
        if (status.entriesRead == 0) return getString(R.string.sync_running_step, step)
        return getString(R.string.sync_running_step_entries, step, resources.getQuantityString(R.plurals.sync_entries, status.entriesRead, count(status.entriesRead)))
    }
    val fraction = status.fraction ?: return getString(R.string.sync_running, ago(last))
    return getString(R.string.sync_running_progress, NumberFormat.getPercentInstance().format(fraction), ago(last))
}

private fun SyncStep.label() = when (this) {
    SyncStep.Channels -> R.string.sync_step_channels
    SyncStep.Movies -> R.string.sync_step_movies
    SyncStep.Shows -> R.string.sync_step_shows
    SyncStep.Saving -> R.string.sync_step_saving
}

private fun Context.synced(last: CompletedSync): String =
    getString(R.string.sync_done, ago(last), String.format(Locale.getDefault(), "%.1f", last.duration.inWholeMilliseconds / 1000.0))

private fun Context.count(n: Int): String = NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(n)

internal fun Context.describe(problem: SyncProblem): String = when (problem) {
    is ProviderProblem -> describe(problem)
    is CatalogNotSaved -> getString(R.string.catalog_not_saved, problem.detail)
}

private fun Context.ago(sync: CompletedSync): String = ago(sync.finishedAt, Clock.System.now())

/**
 * How long ago a Sync finished, worded to sit mid-sentence: "il y a 5 minutes", not the
 * capitalised "Il y a 5 minutes" DateUtils gives for the start of one.
 */
internal fun Context.ago(then: Instant, now: Instant): String {
    val elapsed = (now - then)
    val (amount, unit) = when {
        elapsed < 1.minutes -> return getString(R.string.sync_just_now)
        elapsed < 1.hours -> elapsed.inWholeMinutes to RelativeUnit.MINUTES
        elapsed < 1.days -> elapsed.inWholeHours to RelativeUnit.HOURS
        elapsed < 7.days -> elapsed.inWholeDays to RelativeUnit.DAYS
        elapsed < 30.days -> elapsed.inWholeDays / 7 to RelativeUnit.WEEKS
        elapsed < 365.days -> elapsed.inWholeDays / 30 to RelativeUnit.MONTHS
        else -> elapsed.inWholeDays / 365 to RelativeUnit.YEARS
    }
    val formatter = RelativeDateTimeFormatter.getInstance(
        ULocale.forLocale(resources.configuration.locales[0]), null,
        RelativeDateTimeFormatter.Style.LONG, DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
    )
    return formatter.format(amount.toDouble(), RelativeDateTimeFormatter.Direction.LAST, unit)
}

/**
 * The catalog's Title counts, "12,345 movies · 3,210 shows", then the Unmatched Titles the year rule
 * kept apart from the Title their number names when there are any (ADR 0014): "· 181 kept apart by year".
 */
internal fun Context.catalogCounts(movies: Int, shows: Int, mismatches: Int = 0): String {
    val counts = getString(
        R.string.catalog_counts,
        resources.getQuantityString(R.plurals.catalog_movies, movies, count(movies)),
        resources.getQuantityString(R.plurals.catalog_shows, shows, count(shows)),
    )
    if (mismatches == 0) return counts
    return getString(R.string.catalog_counts, counts, resources.getQuantityString(R.plurals.catalog_mismatches, mismatches, count(mismatches)))
}
