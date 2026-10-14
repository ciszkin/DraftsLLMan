package by.ciszkin.draftsllman.sketch

import by.ciszkin.draftsllman.scene.CanvasSpec
import kotlin.math.abs

/**
 * The S1 system prompt: the PROVEN text-Architect prompt (same tool, same constraint vocabulary,
 * same satisfiability rules — known to produce satisfiable skeletons) plus a small addendum that
 * turns it into an image-recovery task and aligns the role vocabulary with the eval's reference
 * labels. The addendum encodes the live findings: hard caps (the model over-decomposes into
 * Unsatisfiable), a satisfiability checklist, and the expected element inventory (the recovery
 * omits "seen" components — the F1 recall gap).
 */
fun SKETCH_RECOVERY_PROMPT(canvas: CanvasSpec): String =
    by.ciszkin.draftsllman.architect.architectSystemPrompt(canvas) + "\n" + RECOVERY_ADDENDUM

/** The delta that turns the text-Architect prompt into a sketch-recovery prompt. */
const val RECOVERY_ADDENDUM =
    "The drawing to decompose is shown in the ATTACHED IMAGE: a HAND-DRAWN SKETCH of a " +
        "UI wireframe/diagram. Recover its CLEAN intended structure — ignore sketch noise, " +
        "wobble and imperfections. Thin pointer/scribble marks with a small label dot " +
        "(call-out annotations aimed at elements) are DESIGNER NOTES, NOT UI elements: " +
        "exclude them from the skeleton. " +
        "Use ONE OF these exact role names so the recovery can be scored: container, button, " +
        "icon, chart, divider, badge (large zones = container; small round avatars/pics = " +
        "icon; large circle = chart; long thin separators = divider; small polygon = badge). " +
        "HARD CAPS — emit AT MOST 7 components and AT MOST 12 constraints total. Do NOT add\n" +
        "align constraints everywhere: prefer ONE distance_ratio for the main span, a connect\n" +
        "tree, and one contain per floating component. A small skeleton that solves is worth\n" +
        "far more than a large one that cannot.\n" +
        "ELEMENT EXPECTATION — a dashboard/deep wireframe in this style typically shows: TWO large\n" +
        "container panels, ONE small button, ONE large chart circle, TWO long thin divider lines,\n" +
        "ONE small polygon badge. Recover ALL of them (do not merge the dividers, do not skip the\n" +
        "badge or the button); the small label dots with thin pointer lines are annotations to\n" +
        "EXCLUDE."

/** Canvas aspect ratio snapped to a canonical label (mirrors the Architect's, kept local). */
internal fun aspectOf(canvas: CanvasSpec): String {
    val actual = canvas.width.toInt().coerceAtLeast(1).toDouble() /
        canvas.height.toInt().coerceAtLeast(1).toDouble()
    val canonical = listOf(
        1 to 1, 4 to 3, 3 to 4, 16 to 9, 9 to 16, 3 to 2, 2 to 3,
    ).minBy { (cw, ch) -> abs(actual - cw.toDouble() / ch.toDouble()) }
    return "${canonical.first}:${canonical.second}"
}
