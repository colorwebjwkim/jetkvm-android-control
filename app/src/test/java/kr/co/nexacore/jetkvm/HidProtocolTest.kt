package kr.co.nexacore.jetkvm

import org.junit.Assert.*
import org.junit.Test
class HidProtocolTest {
    @Test fun absolutePointerMatchesWireFormatAndBoundaries() {
        assertArrayEquals(byteArrayOf(3,0,0,0x7f,0xff.toByte(),0,0,0,0,1),HidProtocol.pointer(32767,0,1))
        assertTrue(runCatching { HidProtocol.pointer(-1,0,0) }.isFailure)
        assertTrue(runCatching { HidProtocol.pointer(0,32768,0) }.isFailure)
    }
    @Test fun keyboardChordAndReleaseMatchWireFormat() {
        assertArrayEquals(byteArrayOf(2,5,76),HidProtocol.keyboard(5,76))
        assertArrayEquals(byteArrayOf(2,0),HidProtocol.keyboard(0))
    }
    @Test fun asciiMappingRejectsUnicodeAndSupportsShift() {
        assertEquals(2 to 4,HidProtocol.ascii('A'))
        assertEquals(0 to 39,HidProtocol.ascii('0'))
        assertEquals(2 to 39,HidProtocol.ascii(')'))
        assertEquals(2 to 52,HidProtocol.ascii('"'))
        assertNull(HidProtocol.ascii('한'))
    }
    @Test fun powerRequiresKnownExtensionStateAndFreshTimestamp() {
        assertFalse(PowerSnapshot().usable(50000))
        assertFalse(PowerSnapshot("atx-power",null,50000).usable(50001))
        assertFalse(PowerSnapshot("unknown",true,50000).usable(50001))
        assertTrue(PowerSnapshot("atx-power",false,50000).usable(62000))
        assertFalse(PowerSnapshot("atx-power",false,50000).usable(62001))
        assertFalse(PowerSnapshot("dc-power",true,50000).usable(49999))
        assertFalse(PowerSnapshot("dc-power",true,0).usable(5000))
    }
}
