package ghostbe.server

import okio.Path

/**
 * Blocks the calling thread forever, invoking [onChange] once per detected
 * filesystem change under [rulesDir]. Multiple changes that land close
 * together (e.g. several rule files edited at once) collapse into a single
 * [onChange] call -- that's intentional, it avoids reloading once per file.
 */
expect fun watchRulesDirectory(rulesDir: Path, onChange: () -> Unit)
