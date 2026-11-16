package dev.betteriptv.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The Viewer's Xtream Codes login. Never logged: [toString] hides the username and password. */
class ProviderLogin(val address: String, val username: String, val password: String) {
    override fun toString() = "ProviderLogin($address, ***)"

    override fun equals(other: Any?) =
        other is ProviderLogin && other.address == address && other.username == username && other.password == password

    override fun hashCode() = listOf(address, username, password).hashCode()

    companion object {
        /**
         * The login an M3U URL carries (`http://host:port/get.php?username=…&password=…&type=m3u_plus`):
         * the address from its scheme, host and port, the username and password from its query. Null
         * for a bare address, or a URL without both, so the Viewer types those themselves.
         */
        fun fromM3uUrl(text: String): ProviderLogin? {
            val trimmed = text.trim()
            val url = (if ("://" in trimmed) trimmed else "http://$trimmed").toHttpUrlOrNull() ?: return null
            val username = url.queryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
            val password = url.queryParameter("password")?.takeIf { it.isNotEmpty() } ?: return null
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).username("").password("").build()
            return ProviderLogin(origin.toString().removeSuffix("/"), username, password)
        }
    }
}

/** Where the device keeps the Provider login. On the stick it is encrypted with a keystore-backed key. */
interface CredentialStore {
    fun load(): ProviderLogin?
    fun save(login: ProviderLogin)
}
