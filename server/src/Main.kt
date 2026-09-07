package ghostbe.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.system.exitProcess

private const val BANNER = """
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
%%%%%%%%%%%%         %%%%%%%%%%%%
%%%%%%%%%%%            %%%%%%%%%%
%%%%%%%%%%   ##   ##   #%%%%%%%%%
%%%%%%%%%%   -  .  %   :####%%%%%
%%%%%%%###     =+#      ######%%%
%%%%#                       ###%%
%%%%                         ##%%
%%%###:                 ...######
%%%#####%                ########
%%######                 ########
%%%###                     ######
%%####..                  :%#####
%%%##### ..         .    ########
%%%%###########   ###############
%%%%###########################%%

ghost-be -- mock HTTP responses for your Android app, no backend changes needed
https://github.com/fcat97/ghost-be
"""

private const val USAGE = """Usage: ghost-be [options]

Options:
  --port <port>   Port to listen on (default: 8787)
  --rules <dir>   Directory of rule .yaml files to watch (default: ./rules)
  -h, --help      Show this help and exit

Docs: https://github.com/fcat97/ghost-be#readme"""

private sealed interface ParsedArgs {
    data object Help : ParsedArgs
    data class Run(val port: Int, val rulesDir: String) : ParsedArgs
}

private fun parseArgs(args: Array<String>): ParsedArgs {
    var port = 8787
    var rulesDir = "./rules"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-h", "--help" -> return ParsedArgs.Help
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--rules" -> { rulesDir = args[i + 1]; i += 2 }
            else -> { i += 1 }
        }
    }
    return ParsedArgs.Run(port, rulesDir)
}

private fun loadRulesOrExit(rulesDir: Path): List<Rule> {
    if (!FileSystem.SYSTEM.exists(rulesDir)) {
        println("ghost-be: rules directory not found: $rulesDir")
        println("ghost-be: create it (with your rule .yaml files inside) or point --rules at an existing directory")
        exitProcess(1)
    }
    return try {
        loadRulesFromDirectory(rulesDir)
    } catch (e: IllegalArgumentException) {
        println("ghost-be: failed to load rules from $rulesDir")
        println(e.message)
        exitProcess(1)
    }
}

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    if (parsed is ParsedArgs.Help) {
        println(USAGE)
        return
    }
    val (port, rulesDirArg) = parsed as ParsedArgs.Run
    val rulesDir = rulesDirArg.toPath()

    println(BANNER)
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
