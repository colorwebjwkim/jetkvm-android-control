package kr.co.nexacore.jetkvm

import java.nio.ByteBuffer

object HidProtocol {
    fun keyboard(modifier: Int, vararg keys: Int): ByteArray {
        require(modifier in 0..255 && keys.size <= 6 && keys.all { it in 0..255 })
        return byteArrayOf(2, modifier.toByte()) + keys.map { it.toByte() }.toByteArray()
    }
    fun pointer(x: Int, y: Int, buttons: Int): ByteArray {
        require(x in 0..32767 && y in 0..32767 && buttons in 0..7)
        return ByteBuffer.allocate(10).put(3).putInt(x).putInt(y).put(buttons.toByte()).array()
    }
    fun ascii(char: Char): Pair<Int,Int>? = when (char) {
        in 'a'..'z' -> 0 to (4 + (char - 'a'))
        in 'A'..'Z' -> 2 to (4 + (char - 'A'))
        in '1'..'9' -> 0 to (30 + (char - '1'))
        '0' -> 0 to 39
        '\n' -> 0 to 40
        '\t' -> 0 to 43
        ' ' -> 0 to 44
        else -> {
            val plain = "-=[]\\;'`,./"
            val shifted = "_+{}|:\"~<>?"
            val codes = listOf(45,46,47,48,49,51,52,53,54,55,56)
            val p = plain.indexOf(char); val s = shifted.indexOf(char)
            if (p >= 0) 0 to codes[p] else if (s >= 0) 2 to codes[s]
            else "!@#$%^&*()".indexOf(char).takeIf { it >= 0 }?.let { 2 to (if (it == 9) 39 else 30 + it) }
        }
    }
}

data class PowerSnapshot(val extension: String = "", val on: Boolean? = null, val checkedAt: Long = 0) {
    fun usable(now: Long) = checkedAt > 0 && extension in setOf("atx-power", "dc-power") && on != null && now - checkedAt in 0..12000
}
