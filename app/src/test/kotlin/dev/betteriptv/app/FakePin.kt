package dev.betteriptv.app

import dev.betteriptv.core.HouseholdPin
import dev.betteriptv.core.PinCheck
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration
import java.time.Instant

/**
 * A Household PIN kept in memory, as the device keeps one (core's `HouseholdPinTest`): five wrong
 * tries lock it, until a test lifts the lock ([locked] = null) as 30 s passing would.
 */
class FakePin(var pin: String? = null) : HouseholdPin {
    override val isSet = MutableStateFlow(pin != null)
    var locked: Duration? = null
    private var failures = 0

    override suspend fun set(pin: String) {
        require(HouseholdPin.isPin(pin))
        this.pin = pin
        isSet.value = true
    }

    override suspend fun check(pin: String): PinCheck {
        if (locked != null) return PinCheck.Locked(Instant.EPOCH)
        if (pin == this.pin) {
            failures = 0
            return PinCheck.Right
        }
        failures++
        if (failures < HouseholdPin.TRIES) return PinCheck.Wrong(HouseholdPin.TRIES - failures)
        failures = 0
        locked = HouseholdPin.LOCK
        return PinCheck.Locked(Instant.EPOCH)
    }

    override fun lockedFor(): Duration? = locked
}
