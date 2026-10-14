package by.ciszkin.draftsllman.agent

import java.io.PrintWriter

/**
 * Real-time tracing output for the agent pipeline: [brief] lines go to a sink (stderr by default —
 * immediate) + optionally a [file] (detailed). [detail] lines go to the file only.
 */
class Tracer(
    private val briefSink: (String) -> Unit = { System.err.println(it) },
    private val file: PrintWriter? = null,
) {
    fun brief(line: String) {
        briefSink(line)
        file?.println(line); file?.flush()
    }
    fun detail(line: String) {
        file?.println(line); file?.flush()
    }
}
