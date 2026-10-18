package kr.co.nexacore.jetkvm

object PowerRestorePolicy {
    @Volatile internal var automaticConnectionsAllowed = true
    fun select(verifiedIds: List<String>,lastDeviceId: String?): String? =
        if (lastDeviceId != null) lastDeviceId.takeIf { it in verifiedIds } else verifiedIds.singleOrNull()
}
