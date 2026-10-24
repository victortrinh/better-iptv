package dev.betteriptv.core

/** The Viewer's Xtream Codes login. Never logged: [toString] hides the username and password. */
class ProviderLogin(val address: String, val username: String, val password: String) {
    override fun toString() = "ProviderLogin($address, ***)"

    override fun equals(other: Any?) =
        other is ProviderLogin && other.address == address && other.username == username && other.password == password

    override fun hashCode() = listOf(address, username, password).hashCode()
}

/** Where the device keeps the Provider login. On the stick it is encrypted with a keystore-backed key. */
interface CredentialStore {
    fun load(): ProviderLogin?
    fun save(login: ProviderLogin)
}
