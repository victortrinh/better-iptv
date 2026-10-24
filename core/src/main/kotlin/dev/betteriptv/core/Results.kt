package dev.betteriptv.core

import java.time.Duration
import java.time.Instant

/** One movie Listing, as the Provider names and files it. */
data class MovieListing(val id: Long, val name: String, val category: String)

sealed interface LoginResult

data class LoggedIn(val expiresOn: Instant?) : LoginResult

/** Why the Provider couldn't be used. */
sealed interface ProviderProblem : LoginResult

data object WrongLogin : ProviderProblem

data class Expired(val expiredOn: Instant?) : ProviderProblem

/** The Provider couldn't be reached or sent something unreadable. [detail] never contains the login. */
data class Unreachable(val detail: String) : ProviderProblem

data class CompletedSync(val finishedAt: Instant, val duration: Duration, val movieCount: Int)

sealed interface SyncStatus {
    /** The last Sync that finished, whose catalog the app shows. */
    val last: CompletedSync?

    data object NeverSynced : SyncStatus {
        override val last: CompletedSync? = null
    }

    data class Running(override val last: CompletedSync?) : SyncStatus

    data class Synced(override val last: CompletedSync) : SyncStatus

    /** The Sync failed; the catalog from [last] is still shown. */
    data class Failed(val problem: ProviderProblem, override val last: CompletedSync?) : SyncStatus
}
