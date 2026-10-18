package kr.co.nexacore.jetkvm
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class RpcTrackerTest {
    @Test fun correlatesOutOfOrderRepliesAndIgnoresEventsAndDuplicates() {
        val tracker = RpcTracker { 100L }; val replies = mutableListOf<Int>()
        val first = tracker.add { replies.add(it!!.getInt("id")) }; val second = tracker.add { replies.add(it!!.getInt("id")) }
        tracker.receive(JSONObject("""{"method":"atxState","params":{"power":true}}"""))
        tracker.receive(JSONObject().put("id",second).put("result",true))
        tracker.receive(JSONObject().put("id",second).put("result",true))
        tracker.receive(JSONObject().put("id",first).put("error",JSONObject().put("code",-32601)))
        assertEquals(listOf(second,first),replies)
    }
    @Test fun lostAcknowledgementExpiresExactlyOnceAndIsNotReplayed() {
        var now = 10L; val tracker = RpcTracker { now }; var count = 0
        tracker.add { assertNull(it); count++ }
        now = 8009; tracker.expire(); assertEquals(0,count)
        now = 8010; tracker.expire(); tracker.expire(); assertEquals(1,count)
    }
    @Test fun closedSessionDiscardsCallbacks() {
        val tracker = RpcTracker { 100L }; val id = tracker.add { fail("Closed session callback") }
        tracker.clear(); tracker.failed(id); tracker.receive(JSONObject().put("id",id))
    }
}
