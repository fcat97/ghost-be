package ghostbe.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import okio.Path
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

private fun loadRulesOrExit(rulesDir: Path): List<Rule> {
    return try {
        loadRulesFromDirectory(rulesDir)
    } catch (e: IllegalArgumentException) {
        println("ghost-be: failed to load rules from $rulesDir")
        println(e.message)
        exitProcess(1)
    }
}

fun main(args: Array<String>) {
    val (port, rulesDirArg) = parseArgs(args)
    val rulesDir = rulesDirArg.toPath()

    val rulesRef = AtomicReference(loadRulesOrExit(rulesDir))
    println("ghost-be: loaded ${rulesRef.value.size} rule(s) from $rulesDir")
    println("ghost-be: listening on http://127.0.0.1:$port")

    Worker.start(name = "rule-watcher").execute(TransferMode.SAFE, { rulesDir to rulesRef }) { (dir, ref) ->
        watchRulesDirectory(dir) {
            try {
                val reloaded = loadRulesFromDirectory(dir)
                ref.value = reloaded
                println("ghost-be: reloaded ${reloaded.size} rule(s) from $dir")
            } catch (e: IllegalArgumentException) {
                println("ghost-be: rule reload failed, keeping previous rules")
                println(e.message)
            }
        }
    }

    val resolver = ResponseResolver(rulesDir)
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        routing {
            interceptRoute({ rulesRef.value }, resolver)
        }
    }.start(wait = true)
}
