package by.ciszkin.draftsllman.agent

/**
 * Single-slot side-channel carrying the INPUT sketch PNG from [by.ciszkin.draftsllman.sketch.SketchRunner]
 * to the [sketchVisionStrategy] graph node, which attaches it as the image in the FIRST LLM user
 * message. Mirrors [CritiqueImageSink]: single-threaded loop, last-offer-wins, poll reads-and-clears.
 */
class SketchSink {
    private var value: ByteArray? = null

    fun offer(png: ByteArray) { value = png }

    fun poll(): ByteArray? {
        val current = value
        value = null
        return current
    }
}