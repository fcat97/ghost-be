package ghostbe.server

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class TrafficBroadcasterTest {
    @Test
    fun `delivers a published event to a subscriber`() = runBlocking {
        val received = async { TrafficBroadcaster.events.first() }
        yield() // let the async block above reach first()'s subscription point before we publish
        TrafficBroadcaster.publish(TrafficEvent("GET", "https://api.example.com/v1/users/42", "get-user-42", 200))
        val event = received.await()
        assertEquals("get-user-42", event.ruleMatched)
        assertEquals(200, event.status)
    }
}
