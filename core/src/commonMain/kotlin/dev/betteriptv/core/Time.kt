package dev.betteriptv.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** The day [this] falls on in [zone]. */
internal fun Instant.dayIn(zone: TimeZone): LocalDate = toLocalDateTime(zone).date

/** The day it is on [this] clock in [zone]. */
internal fun Clock.today(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = now().dayIn(zone)

/** The year it is on the device. */
internal fun thisYear(): Int = Clock.System.today().year
