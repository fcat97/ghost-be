package ghostbe.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sse.SSE
import io.ktor.server.testing.*
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class TrafficSseRouteTest {
    @Test
    fun `streams a published traffic event as an SSE message`() = testApplication {
        application {
            install(SSE)
            routing { trafficSseRoute() }
        }
        val client = createClient { }

        withTimeout(5000) {
            coroutineScope {
                launch {
                    delay(200) // give the server-side sse route time to start collecting before we publish
                    TrafficBroadcaster.publish(TrafficEvent("GET", "https://api.example.com/v1/users/42", "get-user-42", 200))
                }
                client.prepareGet("/events/traffic").execute { response ->
                    val channel = response.bodyAsChannel()
                    var dataLine: String? = null
                    while (dataLine == null) {
                        val line = channel.readUTF8Line() ?: break
                        if (line.startsWith("data:")) {
                            dataLine = line.removePrefix("data:").trim()
                        }
                    }
                    assertTrue(dataLine?.contains("get-user-42") == true)
                }
            }
        }
    }
}
