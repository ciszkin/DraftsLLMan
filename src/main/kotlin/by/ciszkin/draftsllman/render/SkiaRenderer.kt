package by.ciszkin.draftsllman.render

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.Color as SceneColor
import by.ciszkin.draftsllman.scene.CurveNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.InstanceNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ShapeNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

object SkiaRenderer {
    fun render(graph: SceneGraph): ByteArray = encode(renderToImage(graph))

    fun renderRegion(graph: SceneGraph, box: ResolvedBox, targetSize: Int): ByteArray =
        cropToSize(renderToImage(graph), squareSrc(graph.canvas, box), targetSize)

    /**
     * Foveated pair from a single rasterization: a full-canvas global map plus an optional ROI crop.
     * The scene is rasterized once and both regions are cropped from the same snapshot.
     */
    fun renderFoveated(
        graph: SceneGraph,
        globalSize: Int,
        roiBox: ResolvedBox?,
        roiSize: Int,
    ): Pair<ByteArray, ByteArray?> {
        val image = renderToImage(graph)
        val global = cropToSize(image, squareSrc(graph.canvas, fullCanvasBox(graph.canvas)), globalSize)
        val roi = roiBox?.let { cropToSize(image, squareSrc(graph.canvas, it), roiSize) }
        return global to roi
    }

    private fun fullCanvasBox(canvas: CanvasSpec): ResolvedBox =
        ResolvedBox(0.0, 0.0, canvas.width, canvas.height)

    private fun renderToImage(graph: SceneGraph): Image =
        renderToSurface(graph).use { it.makeImageSnapshot() }

    private fun cropToSize(image: Image, src: Rect, targetSize: Int): ByteArray =
        Surface.makeRaster(ImageInfo.makeN32Premul(targetSize, targetSize)).use { surface ->
            val canvas = surface.canvas
            canvas.clear(Color.WHITE)
            canvas.drawImageRect(image, src, Rect.makeWH(targetSize.toFloat(), targetSize.toFloat()), SamplingMode.LINEAR, null, true)
            encode(surface.makeImageSnapshot())
        }

    private fun squareSrc(canvas: CanvasSpec, box: ResolvedBox): Rect {
        val w = canvas.width.toInt().coerceAtLeast(1)
        val h = canvas.height.toInt().coerceAtLeast(1)
        val side = maxOf(box.width, box.height).coerceAtLeast(1.0)
        val maxX = (w - side).coerceAtLeast(0.0)
        val maxY = (h - side).coerceAtLeast(0.0)
        val sx = (box.cx - side / 2.0).coerceIn(0.0, maxX)
        val sy = (box.cy - side / 2.0).coerceIn(0.0, maxY)
        return Rect.makeLTRB(sx.toFloat(), sy.toFloat(), (sx + side).toFloat(), (sy + side).toFloat())
    }

    private fun renderToSurface(graph: SceneGraph): Surface {
        val w = graph.canvas.width.toInt().coerceAtLeast(1)
        val h = graph.canvas.height.toInt().coerceAtLeast(1)
        val surface = Surface.makeRaster(ImageInfo.makeN32Premul(w, h))
        val canvas = surface.canvas
        canvas.clear(Color.WHITE)
        for (id in graph.rootIds) drawNode(canvas, graph, id)
        return surface
    }

    private fun encode(img: Image): ByteArray =
        img.encodeToData(EncodedImageFormat.PNG, 100)?.use { it.bytes }
            ?: error("Skia encodeToData returned null")

    internal fun drawNode(canvas: Canvas, graph: SceneGraph, id: String) {
        val node = graph.nodes[id] ?: return
        when (node) {
            is CircleNode -> {
                // SVG-faithful: explicit fill AND/OR stroke; hollow when fill=null with a stroke.
                // A node with NEITHER color keeps the legacy black-ink fallback (default-style
                // detail nodes rely on it).
                val fillColor = if (node.style.fill == null && node.style.stroke == null) SceneColor.BLACK
                    else node.style.fill
                val strokeColor = node.style.stroke
                val cx = node.box.cx.toFloat()
                val cy = node.box.cy.toFloat()
                val r = node.radius.toFloat()
                if (fillColor != null) Paint().use { p ->
                    p.isAntiAlias = true; p.mode = PaintMode.FILL
                    p.color = fillColor.argb
                    canvas.drawCircle(cx, cy, r, p)
                }
                if (strokeColor != null) Paint().use { p ->
                    p.isAntiAlias = true; p.mode = PaintMode.STROKE
                    p.strokeWidth = node.style.strokeWidth.toFloat()
                    p.color = strokeColor.argb
                    canvas.drawCircle(cx, cy, r, p)
                }
            }
            is RectNode -> {
                val fillColor = if (node.style.fill == null && node.style.stroke == null) SceneColor.BLACK
                    else node.style.fill
                val strokeColor = node.style.stroke
                val radius = node.style.cornerRadius.toFloat()
                val rr = if (radius > 0f)
                    RRect.makeXYWH(
                        node.box.x.toFloat(), node.box.y.toFloat(),
                        node.box.width.toFloat(), node.box.height.toFloat(),
                        radius * 2f, radius * 2f,
                    ) else null
                fun rectPaint(p: Paint) {
                    if (rr != null) canvas.drawRRect(rr, p)
                    else canvas.drawRect(
                        Rect.makeXYWH(node.box.x.toFloat(), node.box.y.toFloat(), node.box.width.toFloat(), node.box.height.toFloat()), p,
                    )
                }
                if (fillColor != null) Paint().use { p ->
                    p.isAntiAlias = true; p.mode = PaintMode.FILL
                    p.color = fillColor.argb
                    rectPaint(p)
                }
                if (strokeColor != null) Paint().use { p ->
                    p.isAntiAlias = true; p.mode = PaintMode.STROKE
                    p.strokeWidth = node.style.strokeWidth.toFloat()
                    p.color = strokeColor.argb
                    rectPaint(p)
                }
            }
            is LineNode -> Paint().use { p ->
                p.isAntiAlias = true; p.mode = PaintMode.STROKE; p.strokeWidth = node.style.strokeWidth.toFloat()
                p.color = (node.style.stroke ?: SceneColor.BLACK).argb
                canvas.drawLine(node.start.first.toFloat(), node.start.second.toFloat(), node.end.first.toFloat(), node.end.second.toFloat(), p)
            }
            is GroupNode -> for (child in graph.children[node.id].orEmpty()) drawNode(canvas, graph, child)
            is InstanceNode -> { } // Skia rendering of instances deferred — the SVG export path handles <defs>/<use>
            is CurveNode -> {
                Paint().use { paint ->
                    paint.isAntiAlias = true; paint.mode = PaintMode.STROKE
                    paint.strokeWidth = node.style.strokeWidth.toFloat()
                    paint.color = (node.style.stroke ?: SceneColor.BLACK).argb
                    // Skia consumes the path lazily at flush time, so it must outlive this call —
                    // do not .use-close it here; Skiko's Cleaner frees it after rasterization.
                    val skPath = Path()
                    val (sx, sy) = node.path.startPoint
                    val (ex, ey) = node.path.endPoint
                    val (c1x, c1y) = node.path.controlPoint1
                    skPath.moveTo(sx.toFloat(), sy.toFloat())
                    val c2 = node.path.controlPoint2
                    if (c2 != null) {
                        skPath.cubicTo(
                            c1x.toFloat(), c1y.toFloat(),
                            c2.first.toFloat(), c2.second.toFloat(),
                            ex.toFloat(), ey.toFloat(),
                        )
                    } else {
                        skPath.quadTo(c1x.toFloat(), c1y.toFloat(), ex.toFloat(), ey.toFloat())
                    }
                    canvas.drawPath(skPath, paint)
                }
            }
            is ShapeNode -> {
                val fillColor = if (node.closed && node.style.fill == null && node.style.stroke == null) SceneColor.BLACK
                    else if (node.closed) node.style.fill else null
                val strokeColor = if (!node.closed) node.style.stroke ?: SceneColor.BLACK else node.style.stroke
                // See CurveNode: do not .use-close the path; Skia reads it at flush time.
                val skPath = Path()
                val first = node.points.first()
                skPath.moveTo(first.first.toFloat(), first.second.toFloat())
                for (i in 1 until node.points.size) {
                    val pt = node.points[i]
                    skPath.lineTo(pt.first.toFloat(), pt.second.toFloat())
                }
                if (node.closed) skPath.closePath()
                if (fillColor != null) Paint().use { p ->
                    p.isAntiAlias = true; p.mode = PaintMode.FILL
                    p.color = fillColor.argb
                    canvas.drawPath(skPath, p)
                }
                if (strokeColor != null) Paint().use { p ->
                    p.isAntiAlias = true; p.mode = PaintMode.STROKE
                    p.strokeWidth = node.style.strokeWidth.toFloat()
                    p.color = strokeColor.argb
                    canvas.drawPath(skPath, p)
                }
            }
        }
    }
}
