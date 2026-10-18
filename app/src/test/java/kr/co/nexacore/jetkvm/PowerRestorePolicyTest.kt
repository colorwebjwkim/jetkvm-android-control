package kr.co.nexacore.jetkvm

import org.junit.Assert.*
import org.junit.Test

class PowerRestorePolicyTest {
    @Test fun restoresLastVerifiedDevice() { assertEquals("b",PowerRestorePolicy.select(listOf("a","b"),"b")) }
    @Test fun neverSubstitutesAnotherDeviceForMissingLastDevice() { assertNull(PowerRestorePolicy.select(listOf("a"),"b")) }
    @Test fun adoptsOnlyUnambiguousLegacyHistory() {
        assertEquals("a",PowerRestorePolicy.select(listOf("a"),null))
        assertNull(PowerRestorePolicy.select(listOf("a","b"),null))
        assertNull(PowerRestorePolicy.select(emptyList(),null))
    }
}
