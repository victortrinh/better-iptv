package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** What checking a Household PIN found. */
sealed interface PinCheck {
    /** The household's PIN: the tries start again. */
    data object Right : PinCheck

    /** Not it; [triesLeft] more before the PIN is [Locked]. */
    data class Wrong(val triesLeft: Int) : PinCheck

    /** Too many wrong tries: nothing is checked, not even the right PIN, before [until]. */
    data class Locked(val until: Instant) : PinCheck
}

/**
 * The Household PIN (#437): the four digits that let a Viewer leave a Kids profile, the app's only
 * lock. One per household, set when its first Kids profile is made. The device keeps a salted
 * PBKDF2 hash of it, never the digits, and never logs or sends them; [TRIES] wrong tries in a row
 * lock it for [LOCK], counted on the device so closing the app doesn't start them again.
 */
interface HouseholdPin {
    /** Whether the household has a PIN: a Kids profile asks for one when there isn't. */
    val isSet: StateFlow<Boolean>

    /** Makes [pin], four digits, the household's PIN, in place of any before it. */
    suspend fun set(pin: String)

    /**
     * Checks [pin] against the household's: a wrong one counts towards the lock, the [TRIES]th
     * locking it for [LOCK]; while locked nothing is checked.
     */
    suspend fun check(pin: String): PinCheck

    /** How long the PIN stays locked by wrong tries; null when it isn't. */
    fun lockedFor(): Duration?

    companion object {
        /** Wrong tries in a row before the PIN locks. */
        const val TRIES = 5

        /** How long it stays locked. */
        val LOCK: Duration = Duration.ofSeconds(30)

        /** How many digits a PIN has. */
        const val LENGTH = 4

        fun isPin(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }
    }
}

/** The [HouseholdPin] kept in the device's database. */
internal class KeptHouseholdPin(private val store: PinStore) : HouseholdPin {
    /** Tests move it. */
    @Volatile var clock: Clock = Clock.systemUTC()

    private val _isSet = MutableStateFlow(store.householdPin() != null)

    override val isSet: StateFlow<Boolean> = _isSet.asStateFlow()

    override suspend fun set(pin: String) = withContext(Dispatchers.Default) {
        require(HouseholdPin.isPin(pin)) { "A Household PIN is four digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        synchronized(this@KeptHouseholdPin) {
            store.saveHouseholdPin(StoredPin("$SCHEME:$ITERATIONS:${encode(salt)}:${encode(hash(pin, salt, ITERATIONS))}", failures = 0, lockedUntil = null))
            _isSet.value = true
        }
    }

    override suspend fun check(pin: String): PinCheck = withContext(Dispatchers.Default) {
        synchronized(this@KeptHouseholdPin) {
            val now = clock.instant()
            val kept = store.householdPin() ?: return@withContext PinCheck.Wrong(HouseholdPin.TRIES)
            kept.lockedUntil?.takeIf { now.isBefore(it) }?.let { return@withContext PinCheck.Locked(it) }
            // A lock that ran out leaves the tries at none.
            val failures = if (kept.lockedUntil != null) 0 else kept.failures
            when {
                matches(pin, kept.hash) -> {
                    if (failures != 0 || kept.lockedUntil != null) store.saveHouseholdPin(kept.copy(failures = 0, lockedUntil = null))
                    PinCheck.Right
                }
                failures + 1 >= HouseholdPin.TRIES -> {
                    val until = now + HouseholdPin.LOCK
                    store.saveHouseholdPin(kept.copy(failures = 0, lockedUntil = until))
                    PinCheck.Locked(until)
                }
                else -> {
                    store.saveHouseholdPin(kept.copy(failures = failures + 1, lockedUntil = null))
                    PinCheck.Wrong(HouseholdPin.TRIES - failures - 1)
                }
            }
        }
    }

    override fun lockedFor(): Duration? = synchronized(this) {
        val now = clock.instant()
        store.householdPin()?.lockedUntil?.takeIf { now.isBefore(it) }?.let { Duration.between(now, it) }
    }

    private fun matches(pin: String, kept: String): Boolean {
        val parts = kept.split(':')
        if (parts.size != 4 || parts[0] != SCHEME || !HouseholdPin.isPin(pin)) return false
        return MessageDigest.isEqual(hash(pin, decode(parts[2]), parts[1].toInt()), decode(parts[3]))
    }

    private companion object {
        const val SCHEME = "pbkdf2-sha256"
        // Under 0.1 s on the stick: a four-digit PIN can't be made hard to guess from its hash, only not readable.
        const val ITERATIONS = 10_000
        const val SALT_BYTES = 16
        val random = SecureRandom()

        fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

        fun encode(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)

        fun decode(text: String) = Base64.getDecoder().decode(text)
    }
}

/** The Household PIN as kept: its [hash], the wrong tries in a row and, once they lock it, until when. */
internal data class StoredPin(val hash: String, val failures: Int, val lockedUntil: Instant?)

internal interface PinStore {
    fun householdPin(): StoredPin?
    fun saveHouseholdPin(pin: StoredPin)
}
