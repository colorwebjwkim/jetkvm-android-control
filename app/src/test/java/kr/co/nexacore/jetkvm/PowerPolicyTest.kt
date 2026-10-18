package kr.co.nexacore.jetkvm

import org.junit.Assert.*
import org.junit.Test

class PowerPolicyTest {
    @Test fun atxDesiredStateDoesNotToggleTheOppositeState() {
        val off = PowerSnapshot("atx-power",false,100)
        val on = off.copy(on = true)
        assertEquals("power-short",PowerPolicy.command("power-on",off,101))
        assertNull(PowerPolicy.command("power-off",off,101))
        assertNull(PowerPolicy.command("reboot",off,101))
        assertNull(PowerPolicy.command("power-on",on,101))
        assertEquals("power-short",PowerPolicy.command("power-off",on,101))
        assertEquals("reset",PowerPolicy.command("reboot",on,101))
    }
    @Test fun dcHasNoBlindPowerCycle() {
        val off = PowerSnapshot("dc-power",false,100)
        assertEquals("dc-on",PowerPolicy.command("power-on",off,101))
        assertEquals("dc-off",PowerPolicy.command("power-off",off.copy(on = true),101))
        assertNull(PowerPolicy.command("reboot",off,101))
        assertNull(PowerPolicy.command("reboot",off.copy(on = true),101))
    }
    @Test fun unknownStaleAndFutureStatesNeverProduceCommands() {
        listOf(PowerSnapshot(),PowerSnapshot("unknown",true,100),PowerSnapshot("atx-power",null,100),PowerSnapshot("atx-power",true,0),PowerSnapshot("atx-power",true,102)).forEach { state ->
            listOf("power-on","power-off","reboot").forEach { assertNull(PowerPolicy.command(it,state,101)) }
        }
        assertNull(PowerPolicy.command("power-off",PowerSnapshot("atx-power",true,100),12101))
    }
}
