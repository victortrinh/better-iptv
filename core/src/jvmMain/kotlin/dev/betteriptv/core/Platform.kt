package dev.betteriptv.core

/**
 * The four things the Core requires of a platform (ADR 0020), supplied when it opens
 * ([Library.open]): where the Provider login is kept secret ([credentials]), where the device's
 * settings are kept ([settings]), what keeps a Sync running when the app isn't in front
 * ([background]) and where the database lives ([database]). The Player engine and the app's own
 * updates stay the platform's, and aren't here.
 */
class Platform(
    val credentials: CredentialStore,
    val settings: SettingsStore,
    val background: BackgroundWork,
    val database: DatabasePlace,
)
