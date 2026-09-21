package ghostbe.server

import okio.Path

/**
 * The directory containing the currently running binary, or null if it can't be
 * determined (e.g. the platform call failed). Used to find files bundled alongside
 * the binary in a release archive, independent of the process's working directory.
 */
expect fun executableDir(): Path?
