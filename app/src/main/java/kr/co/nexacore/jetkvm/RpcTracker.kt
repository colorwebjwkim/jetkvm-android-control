package kr.co.nexacore.jetkvm

import org.json.JSONObject

/** Correlates replies and expires requests once; unsolicited events cannot consume a request. */
class RpcTracker(private val clock: () -> Long) {
    private var nextId = 0
    private val pending = mutableMapOf<Int, Pair<Long,(JSONObject?) -> Unit>>()
    fun add(callback: (JSONObject?) -> Unit): Int = (++nextId).also { pending[it] = clock() to callback }
    fun receive(message: JSONObject) { if (message.has("id")) pending.remove(message.optInt("id"))?.second?.invoke(message) }
    fun failed(id: Int) { pending.remove(id)?.second?.invoke(null) }
    fun expire() { pending.filterValues { clock() - it.first >= 8000 }.keys.toList().forEach { failed(it) } }
    fun clear() { pending.clear() }
}
