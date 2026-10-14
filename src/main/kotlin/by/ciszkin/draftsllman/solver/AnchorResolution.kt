package by.ciszkin.draftsllman.solver

import by.ciszkin.draftsllman.scene.Anchor

/** Per-node named-anchor aliases: nodeId -> (anchorName -> geometric [Anchor]). */
typealias AnchorAliases = Map<String, Map<String, Anchor>>

/**
 * Resolves an anchor name the LLM emitted to a concrete geometric [Anchor], or null if unknown.
 *
 * Geometric-first (the Slice-3 "geometric names win" policy): a name matching a geometric [Anchor]
 * enum constant resolves directly — an alias cannot rebind "CENTER" / "TOP" / etc. — otherwise the
 * [nodeId]'s declared [aliases] are consulted, with a case-insensitive match on the alias key.
 *
 * Pure — the SINGLE chokepoint for named-anchor resolution, shared by the tool layer
 * ([by.ciszkin.draftsllman.contract.ToolArgs.AddConstraint]), [by.ciszkin.draftsllman.architect.SkeletonBuilder],
 * and [by.ciszkin.draftsllman.architect.validateSkeleton]. Routing both validation and building
 * through this one function guarantees a validator-approved skeleton can never resolve differently
 * (and crash) in the builder — the root requirement of the Slices 3/4 divergence fix.
 */
fun resolveAnchorNameOrNull(name: String, nodeId: String, aliases: AnchorAliases): Anchor? {
    val upper = name.uppercase()
    // Geometric names win: a reserved Anchor enum constant is always resolved as itself.
    val geometric = runCatching { Anchor.valueOf(upper) }.getOrNull()
    if (geometric != null) return geometric
    val nodeAliases = aliases[nodeId] ?: return null
    // Case-insensitive alias-key lookup (alias maps may be keyed raw or uppercased).
    return nodeAliases.entries.firstOrNull { it.key.uppercase() == upper }?.value
}

/**
 * Resolves an anchor name the LLM emitted to a concrete geometric [Anchor]. Geometric-first
 * (see [resolveAnchorNameOrNull]); throws [IllegalStateException] if the name is unknown.
 *
 * Pure — thin throwing wrapper over the shared [resolveAnchorNameOrNull] chokepoint.
 */
fun resolveAnchorName(name: String, nodeId: String, aliases: AnchorAliases): Anchor =
    resolveAnchorNameOrNull(name, nodeId, aliases)
        ?: error("Unknown anchor '$name' on node '$nodeId'")

/**
 * Builds an alias map (alias-name -> geometric [Anchor]) from the LLM's raw alias-name -> geometric-name
 * strings, resolving each geometric name case-insensitively and dropping entries whose value is not a
 * valid [Anchor] enum constant.
 *
 * Pure — the SINGLE chokepoint for alias-VALUE resolution, shared by
 * [by.ciszkin.draftsllman.architect.SkeletonBuilder] and [by.ciszkin.draftsllman.architect.validateSkeleton].
 * Routing both through this one function guarantees they construct identical alias maps, so a skeleton
 * the validator approves (including one with lowercase/mixed-case geometric values, or an unreferenced
 * alias whose value is invalid) can never resolve differently and crash in the builder. This closes the
 * alias-VALUE case-sensitivity gap left after the alias-NAME collision fix ([resolveAnchorNameOrNull]).
 */
fun resolveAliasMap(rawAliases: Map<String, String>): Map<String, Anchor> =
    rawAliases.mapNotNull { (alias, geometric) ->
        runCatching { Anchor.valueOf(geometric.uppercase()) }.getOrNull()?.let { alias to it }
    }.toMap()
