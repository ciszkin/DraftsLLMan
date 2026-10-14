package by.ciszkin.draftsllman.architect

/** Single-slot holder for the [EmitSkeleton] emitted by the Architect's `emit_skeleton` tool. */
class SkeletonSink {
    private var value: EmitSkeleton? = null
    fun offer(skeleton: EmitSkeleton) { value = skeleton }
    fun poll(): EmitSkeleton? { val current = value; value = null; return current }
}
