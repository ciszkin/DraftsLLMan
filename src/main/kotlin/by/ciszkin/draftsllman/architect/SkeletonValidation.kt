package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.contract.ValidationError
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.AnchorAxis
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.solver.AnchorAliases
import by.ciszkin.draftsllman.solver.resolveAliasMap
import by.ciszkin.draftsllman.solver.resolveAnchorNameOrNull
import java.util.Locale

/**
 * Validates an [EmitSkeleton] for the global solve: non-empty, unique component ids, supported types,
 * positive sizes, supported constraint kinds, anchor-type compatibility for each constraint kind,
 * alias names that do not collide with reserved geometric [Anchor] enum constants, and that every
 * constraint ref parses and names a known component. Pure; returns violations (empty = valid).
 *
 * Anchor-name resolution here routes through the SAME geometric-first chokepoint
 * ([resolveAnchorNameOrNull]) as [SkeletonBuilder], so a skeleton this function approves can never
 * resolve differently — and crash — in the builder.
 */
fun validateSkeleton(skeleton: EmitSkeleton, canvas: CanvasSpec): List<ValidationError> = buildList {
    if (skeleton.components.isEmpty()) add(ValidationError("components", "no components"))
    skeleton.components.groupingBy { it.id }.eachCount().filter { it.value > 1 }.forEach { (id, _) ->
        add(ValidationError("components", "duplicate component id '$id'"))
    }
    val validTypes = setOf("circle", "rect", "line", "group")
    skeleton.components.forEachIndexed { i, c ->
        if (c.id.isBlank()) add(ValidationError("components[$i].id", "id is blank"))
        // ':' in an id breaks every anchor ref (parseRef splits at the FIRST colon, so
        // "bike:wheel:hub" resolves component "bike" anchor "wheel:hub" — the wrong thing).
        if (':' in c.id) add(ValidationError("components[$i].id", "id must not contain ':' (it breaks \"id:anchor\" refs)"))
        if (c.type !in validTypes) add(ValidationError("components[$i].type", "unsupported type '${c.type}'"))
        if (c.width <= 0.0 || c.height <= 0.0) add(ValidationError("components[$i].size", "width/height must be positive"))
        // Sizes are PIXELS, not canvas fractions (live-run finding 2026-08-21: wheels declared
        // 0.2x0.2 misread as fractions — every other contract field is ratio-based). Sub-pixel
        // declarations are rejected with the unit spelled out instead of dying as a degenerate
        // solve later.
        if (c.width > 0.0 && c.height > 0.0 && (c.width < 2.0 || c.height < 2.0))
            add(ValidationError("components[$i].size",
                "width/height are in PIXELS, not fractions of the canvas — ${c.width}x${c.height} " +
                "collapses to a point. Declare macro sizes (e.g. a wheel ~60-100)."))
    }
    val compIds = skeleton.components.map { it.id }.toSet()
    // Group components are structural (no solver vars) — component-level kinds (contain /
    // relative_size / distribute) on them are rejected up front; connect/align/distance_ratio
    // on groups deliberately keep the engine-diagnostic path (the designed catch-all, pinned
    // by EmitSkeletonToolTest).
    val groupIds = skeleton.components.filter { it.type == "group" }.map { it.id }.toSet()
    val validKinds = setOf("connect", "align", "distance_ratio", "contain", "relative_size", "distribute")

    // Reject alias names that collide with a reserved geometric Anchor enum constant. Geometric-first
    // resolution (resolveAnchorNameOrNull, shared with SkeletonBuilder) would silently ignore such an
    // alias — the explicit error surfaces it so the Architect's reject->retry loop renames the alias
    // instead of SkeletonBuilder crashing later in SceneConstraint.Connect (the Slices 3/4 bug).
    val reservedAnchorNames = Anchor.entries.map { it.name }.toSet()  // case-insensitive collision set
    skeleton.components.forEachIndexed { i, c ->
        c.anchors.forEach { (aliasName, _) ->
            if (aliasName.uppercase() in reservedAnchorNames) {
                add(ValidationError(
                    "components[$i].anchors",
                    "alias name '$aliasName' on '${c.id}' collides with a reserved geometric anchor name; rename it (e.g. ${aliasName}Pt)"
                ))
            }
        }
    }

    // Build the alias lookup through the SAME chokepoint SkeletonBuilder uses (resolveAliasMap), so the
    // validator and builder construct identical maps. Invalid geometric values are dropped here and
    // surface as "unknown anchor" below when a constraint references one; the builder only ever sees
    // validator-approved maps.
    val anchorAliases: AnchorAliases = skeleton.components.associate { comp ->
        comp.id to resolveAliasMap(comp.anchors)
    }

    skeleton.constraints.forEachIndexed { i, c ->
        if (c.kind !in validKinds) add(ValidationError("constraints[$i].kind", "unsupported kind '${c.kind}'"))

        // contain is COMPONENT-level (no anchors — spec 2026-08-17): plain source id, literal
        // "canvas" target, margin fraction. Skip the anchor-ref machinery entirely.
        if (c.kind == "contain") {
            if (c.source in groupIds)
                add(ValidationError("constraints[$i].source", "contain applies to leaf components — '$c.source' is a group (structural, no geometry)"))
            if (':' in c.source)
                add(ValidationError("constraints[$i].source",
                    "contain takes a plain component id (no ':anchor'), got '${c.source}'"))
            else if (c.source !in compIds)
                add(ValidationError("constraints[$i].source", "unknown component '${c.source}'"))
            if (c.target != "canvas")
                add(ValidationError("constraints[$i].target", "contain target must be \"canvas\", got '${c.target}'"))
            if (c.margin !in 0.0..0.5)
                add(ValidationError("constraints[$i].margin", "contain margin must be in [0.0, 0.5], got ${c.margin}"))

            // Band pre-flight: reject margins that leave too small a band for rigid components (circle, rect).
            // Lines are exempt — a line with ≥2 connected ends is a stretchable strut that may compress at solve
            // time by design. Groups are exempt too (contain-on-group already fails via engine diagnostics).
            val comp = skeleton.components.firstOrNull { it.id == c.source }
            if (comp != null && (comp.type == "circle" || comp.type == "rect")) {
                val band = 1.0 - 2 * c.margin
                if (comp.width > band * canvas.width || comp.height > band * canvas.height)
                    add(ValidationError("constraints[$i].margin",
                        "contain margin ${c.margin} leaves a ${"%.3f".format(Locale.ROOT, band)} band of the canvas — " +
                        "too small for '${c.source}' (${comp.width}x${comp.height}). Lower the margin."))
            }
            return@forEachIndexed
        }

        // relative_size is component-level (spec 2026-08-20): plain ids, a ratio; the TARGET is
        // the size reference — the engine demotes only the SOURCE's size stay so the ratio scales
        // the source onto the target's declared size.
        if (c.kind == "relative_size") {
            for (id in listOf(c.source, c.target)) {
                if (id in groupIds)
                    add(ValidationError("constraints[$i].source", "relative_size applies to leaf components — '$id' is a group (structural, no geometry)"))
            }
            if (':' in c.source || ':' in c.target)
                add(ValidationError("constraints[$i].source",
                    "relative_size takes plain component ids (no ':anchor'), got '${if (':' in c.source) c.source else c.target}'"))
            else {
                if (c.source !in compIds) add(ValidationError("constraints[$i].source", "unknown component '${c.source}'"))
                if (c.target !in compIds) add(ValidationError("constraints[$i].target", "unknown component '${c.target}'"))
            }
            if (c.ratio <= 0.0 || c.ratio > 5.0)
                add(ValidationError("constraints[$i].ratio", "relative_size ratio must be in (0.0, 5.0], got ${c.ratio}"))
            return@forEachIndexed
        }

        // distribute is list-level (spec 2026-08-20): an ordered id list + an axis; source/target unused.
        if (c.kind == "distribute") {
            if (c.components.size < 3)
                add(ValidationError("constraints[$i].components",
                    "distribute requires at least 3 components (two leave nothing to equalize), got ${c.components.size}"))
            val dupes = c.components.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            if (dupes.isNotEmpty())
                add(ValidationError("constraints[$i].components", "duplicate components in distribute list: ${dupes.joinToString()}"))
            c.components.forEach { comp ->
                if (comp !in compIds) add(ValidationError("constraints[$i].components", "unknown component '$comp'"))
                if (comp in groupIds) add(ValidationError("constraints[$i].components", "distribute applies to leaf components — '$comp' is a group (structural, no geometry)"))
            }
            if (c.along !in setOf("width", "height"))
                add(ValidationError("constraints[$i].along", "distribute along must be \"width\" or \"height\", got '${c.along}'"))
            return@forEachIndexed
        }

        // Parse refs and validate anchor types for the constraint kind
        val sourceParts = runCatching { SkeletonBuilder.parseRef(c.source) }.getOrElse { e ->
            add(ValidationError("constraints[$i].source", "malformed ref '${c.source}': ${e.message}"))
            return@forEachIndexed
        }
        val targetParts = runCatching { SkeletonBuilder.parseRef(c.target) }.getOrElse { e ->
            add(ValidationError("constraints[$i].target", "malformed ref '${c.target}': ${e.message}"))
            return@forEachIndexed
        }

        val (sourceComp, sourceAnchorAlias) = sourceParts
        val (targetComp, targetAnchorAlias) = targetParts

        if (sourceComp !in compIds) add(ValidationError("constraints[$i].source", "unknown component '$sourceComp'"))
        if (targetComp !in compIds) add(ValidationError("constraints[$i].target", "unknown component '$targetComp'"))

        // Resolve anchor types via the SAME geometric-first resolver SkeletonBuilder uses
        // (resolveAnchorNameOrNull) so validator and builder can never diverge. Geometric names win;
        // a colliding alias name is already rejected above, so a non-colliding alias resolves here.
        val sourceAnchor = resolveAnchorNameOrNull(sourceAnchorAlias, sourceComp, anchorAliases)
        val targetAnchor = resolveAnchorNameOrNull(targetAnchorAlias, targetComp, anchorAliases)

        if (sourceAnchor == null) {
            add(ValidationError("constraints[$i].source", "unknown anchor '$sourceAnchorAlias' on component '$sourceComp'"))
            return@forEachIndexed
        }
        if (targetAnchor == null) {
            add(ValidationError("constraints[$i].target", "unknown anchor '$targetAnchorAlias' on component '$targetComp'"))
            return@forEachIndexed
        }

        // Validate anchor-type compatibility for the constraint kind
        when (c.kind) {
            "connect" -> {
                if (sourceAnchor.axis != AnchorAxis.POINT || targetAnchor.axis != AnchorAxis.POINT)
                    add(ValidationError("constraints[$i]", "connect requires POINT anchors (CENTER, TOP_LEFT, etc.), got $sourceAnchor / $targetAnchor"))
            }
            "align" -> {
                if (sourceAnchor.axis != targetAnchor.axis)
                    add(ValidationError("constraints[$i]", "align requires same-axis anchors (both X or both Y), got $sourceAnchor / $targetAnchor"))
            }
            "distance_ratio" -> {
                if (c.ratio <= 0.0 || c.ratio > 1.0)
                    add(ValidationError("constraints[$i]", "distance_ratio ratio must be in (0.0, 1.0], got ${c.ratio}"))
                if (c.relativeTo !in setOf("canvas_width", "canvas_height"))
                    add(ValidationError("constraints[$i]", "distance_ratio relative_to must be canvas_width or canvas_height, got ${c.relativeTo}"))
                else {
                    val expectedAxis = if (c.relativeTo == "canvas_width") AnchorAxis.X else AnchorAxis.Y
                    val axisLabel = if (c.relativeTo == "canvas_width") "X" else "Y"
                    if (sourceAnchor.axis != expectedAxis)
                        add(ValidationError("constraints[$i]", "distance_ratio(${c.relativeTo}) source anchor must be ${axisLabel}-axis, got $sourceAnchor"))
                    if (targetAnchor.axis != expectedAxis)
                        add(ValidationError("constraints[$i]", "distance_ratio(${c.relativeTo}) target anchor must be ${axisLabel}-axis, got $targetAnchor"))
                }
            }
        }
    }
}
