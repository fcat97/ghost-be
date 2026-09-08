package ghostbe.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import okio.FileSystem
import okio.Path

private fun contentTypeFor(fileName: String): ContentType = when {
    fileName.endsWith(".html") -> ContentType.Text.Html
    fileName.endsWith(".js") || fileName.endsWith(".mjs") -> ContentType("application", "javascript")
    fileName.endsWith(".wasm") -> ContentType("application", "wasm")
    fileName.endsWith(".css") -> ContentType.Text.CSS
    else -> ContentType.Application.OctetStream
}

fun Route.webStaticRoute(webDist: Path, fileSystem: FileSystem = FileSystem.SYSTEM) {
    get("/{path...}") {
        val requestedSegments = call.parameters.getAll("path").orEmpty()
        val requestedPath = if (requestedSegments.isEmpty()) "index.html" else requestedSegments.joinToString("/")
        if (requestedPath.contains("..")) {
            call.respondText("invalid path", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@get
        }
        val filePath = webDist / requestedPath
        if (!fileSystem.exists(filePath) || fileSystem.metadata(filePath).isDirectory) {
            call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@get
        }
        val bytes = fileSystem.read(filePath) { readByteArray() }
        call.respondBytes(bytes, contentTypeFor(filePath.name))
    }
}
