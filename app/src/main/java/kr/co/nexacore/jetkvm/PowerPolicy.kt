package kr.co.nexacore.jetkvm

/** Desired state, never a blind ATX toggle. A DC restart sequence is intentionally unsupported. */
object PowerPolicy {
    fun command(intent: String,state: PowerSnapshot,now: Long): String? {
        if (!state.usable(now)) return null
        return when(intent) {
            "power-on" -> if (state.on == false) if (state.extension == "atx-power") "power-short" else "dc-on" else null
            "power-off" -> if (state.on == true) if (state.extension == "atx-power") "power-short" else "dc-off" else null
            "reboot" -> if (state.extension == "atx-power" && state.on == true) "reset" else null
            else -> null
        }
    }
}
