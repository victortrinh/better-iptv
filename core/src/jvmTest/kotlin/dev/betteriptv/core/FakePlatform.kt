package dev.betteriptv.core

/** Device settings kept in memory, standing in for the platform's store. */
class InMemorySettings : SettingsStore {
    val kept = HashMap<String, String>()
    override fun read(key: String) = kept[key]
    override fun write(changed: Map<String, String?>) {
        changed.forEach { (key, value) -> if (value == null) kept.remove(key) else kept[key] = value }
    }
}

/** Runs the Core's work at once, as a platform does, and notes when it was keeping the app alive. */
class FakeBackgroundWork : BackgroundWork {
    /** How many pieces of work are running now. */
    @Volatile var running = 0
        private set

    /** How many it has run to their end. */
    @Volatile var finished = 0
        private set

    override suspend fun <T> keepAlive(work: suspend () -> T): T {
        synchronized(this) { running++ }
        try {
            return work()
        } finally {
            synchronized(this) { running--; finished++ }
        }
    }
}

/** A platform of fakes whose database is the file at [databasePath], and which keeps the login in [credentials]. */
fun fakePlatform(
    databasePath: String,
    credentials: CredentialStore = InMemoryCredentials(),
    settings: SettingsStore = InMemorySettings(),
    background: BackgroundWork = FakeBackgroundWork(),
) = Platform(credentials, settings, background) { databasePath }

/**
 * Opens a Library on a platform of fakes ([fakePlatform]) whose database is at [databasePath], as the
 * tests written before the Core named its platform (#583) open theirs. It reaches the fake Provider and
 * Cloud inside its HTTP client ([InProcess]) unless given the [network] of another [FakeTransport].
 */
internal fun Library.Companion.open(
    databasePath: String,
    credentials: CredentialStore,
    cloudAddress: String? = null,
    providerTables: ((providerKey: String) -> String?)? = null,
    device: CloudDevice? = null,
    network: CoreNetwork = InProcess.network(),
): Library {
    val platform = fakePlatform(databasePath, credentials)
    return if (providerTables == null) open(platform, cloudAddress, network, device) else open(platform, cloudAddress, providerTables, network, device)
}
