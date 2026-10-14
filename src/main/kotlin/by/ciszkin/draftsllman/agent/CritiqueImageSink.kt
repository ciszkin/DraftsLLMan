package by.ciszkin.draftsllman.agent

import by.ciszkin.draftsllman.engine.CritiqueResult

/**
 * Single-slot side-channel carrying the last rendered [CritiqueResult] from the tool-execution point
 * (where `dispatch` produces it) to the vision-injection graph node (which attaches it as an image).
 *
 * The agent loop is single-threaded (`AgentRunner` uses `runBlocking`) and the injection node runs
 * immediately after each tool call, so at most one critique is unconsumed at a time — a single slot
 * (last-offer-wins, [poll] reads-and-clears) is sufficient and avoids unbounded retention.
 */
class CritiqueImageSink {
    private var value: CritiqueResult? = null

    /** Records [critique] as the latest rendered critique (overwrites any unconsumed prior value). */
    fun offer(critique: CritiqueResult) { value = critique }

    /** Returns the latest critique and clears the slot, or null if none was offered since the last poll. */
    fun poll(): CritiqueResult? {
        val current = value
        value = null
        return current
    }
}
