package dev.betteriptv.core

/**
 * What the Core requires of a platform besides a place for the Provider login ([CredentialStore]):
 * each platform family supplies these once (ADR 0020), and the Core opens with them ([Platform]).
 * Installing the app's own updates is not among them: a store does it on most platforms.
 */

/**
 * Where the device keeps its own settings (the Quality cap, the subtitle style, Background reading,
 * the Text size), as text by key. What a key means is the Core's ([KeptDeviceSettings]); the platform
 * only keeps it across the app closing and updating. They never leave the device, and the Provider
 * login is never among them.
 */
interface SettingsStore {
    /** What is kept under [key], or null when nothing is. */
    fun read(key: String): String?

    /** Keeps each of [changed] under its key; a null value forgets the key. */
    fun write(changed: Map<String, String?>)
}

/**
 * Keeps the app running while the Core works with the app not in front: a Sync or Matching the Viewer
 * left to switch apps, or Background reading on a platform that reads then.
 */
interface BackgroundWork {
    /**
     * Runs [work] and returns what it returns, keeping the app alive until it ends, however it ends.
     * A platform that can't keep the app alive runs [work] all the same: what was cut short is picked
     * up on the next launch.
     */
    suspend fun <T> keepAlive(work: suspend () -> T): T
}

/** Where the platform keeps the Core's database. */
fun interface DatabasePlace {
    /**
     * The path of the database called [name], the same on every launch and private to the app. Its
     * folder exists; the file is created when first opened.
     */
    fun path(name: String): String
}
