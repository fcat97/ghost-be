package ghostbe.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import okio.Path.Companion.toPath
import kotlin.system.exitProcess

private fun parseArgs(args: Array<String>): Pair<Int, String> {
    var port = 8787
    var rulesDir = "./rules"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--rules" -> { rulesDir = args[i + 1]; i += 2 }
            else -> { i += 1 }
        }
    }
    return port to rulesDir
}

fun main(args: Array<String>) {
    val (port, rulesDirArg) = parseArgs(args)
    val rulesDir = rulesDirArg.toPath()

    val rules = try {
        loadRulesFromDirectory(rulesDir)
    } catch (e: IllegalArgumentException) {
        println("ghost-be: failed to load rules from $rulesDir")
        println(e.message)
        exitProcess(1)
    }

    println("ghost-be: loaded ${rules.size} rule(s) from $rulesDir")
    println("ghost-be: listening on http://127.0.0.1:$port")

    val resolver = ResponseResolver(rulesDir)
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        routing {
            interceptRoute(rules, resolver)
        }
    }.start(wait = true)
}
