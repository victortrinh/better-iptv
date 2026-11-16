package dev.betteriptv.core

/** The surveyed Provider's key: the SHA-256 of its host, which the repo never names. */
internal const val SURVEYED_PROVIDER = "6e096c1c4c0968f6b2ec3cc773d16782f91ba0a5648642b1f4aff5f9df503e77"

/** The surveyed Provider's hand-written Provider table, as the app ships it. */
internal val surveyedProviderTable: String = PrefixTable.bundledProviderTable(SURVEYED_PROVIDER)!!

/** The Prefix table the surveyed Provider's household has: the Base table with its hand file. */
internal fun surveyedPrefixTable(): PrefixTable = PrefixTable.withProviderTable(surveyedProviderTable)

/**
 * Opens a Library whose Provider, whatever its host, has the surveyed Provider's Provider table: for
 * tests where the fake Provider, whose fixture comes from the survey, stands in for that Provider.
 */
internal fun openSurveyedLibrary(databasePath: String, credentials: CredentialStore, cloudAddress: String? = null): Library =
    Library.open(databasePath, credentials, cloudAddress, { surveyedProviderTable })

/**
 * Makes this cloud serve the surveyed Provider's Provider table to [provider], as the real cloud
 * serves the household's: a Library on [provider] that asks the cloud then keeps the surveyed table.
 */
internal fun FakeCloud.servingSurveyedTable(provider: FakeProvider): FakeCloud = apply {
    providerTables[providerKey(provider.login)] = surveyedProviderTable
}
