package by.ciszkin.draftsllman.agent

/** Reports a no-op once [threshold] consecutive identical hashes have been recorded. Pure. */
class NoOpDetector(private val threshold: Int = 3) {
    private var lastHash: String? = null
    private var run = 0

    fun record(hash: String): Boolean {
        if (hash == lastHash) run += 1 else { lastHash = hash; run = 1 }
        return run >= threshold
    }

    /** Clears recorded state so a reused detector starts fresh for a new run. */
    fun reset() {
        lastHash = null
        run = 0
    }
}
