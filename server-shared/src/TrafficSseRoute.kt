package ghostbe.server

import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

fun Route.trafficSseRoute() {
    sse("/events/traffic") {
        TrafficBroadcaster.events.collect { event ->
            send(data = json.encodeToString(event))
        }
    }
}
