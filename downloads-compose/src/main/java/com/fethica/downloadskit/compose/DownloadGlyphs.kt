package com.fethica.downloadskit.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * The five glyphs the offline button morphs between, drawn rather than shipped as
 * vectors: it keeps the library free of an icon dependency and lets the stroke weight
 * follow the glyph size.
 *
 * Every one is drawn inside a square of [DrawScope.size], centred, and takes its colour
 * from the caller so the button never picks one of its own.
 */
private val DrawScope.unit: Float get() = size.minDimension

internal fun DrawScope.drawDownloadArrow(color: Color) {
    val u = unit
    val stroke = Stroke(width = u * 0.09f, cap = StrokeCap.Round)
    val cx = size.width / 2f
    val top = size.height * 0.16f
    val shaftBottom = size.height * 0.62f
    drawLine(color, Offset(cx, top), Offset(cx, shaftBottom), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(cx - u * 0.19f, shaftBottom - u * 0.19f), Offset(cx, shaftBottom), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(cx + u * 0.19f, shaftBottom - u * 0.19f), Offset(cx, shaftBottom), stroke.width, StrokeCap.Round)
    // The tray the arrow lands in.
    val trayY = size.height * 0.84f
    drawLine(color, Offset(cx - u * 0.30f, trayY), Offset(cx + u * 0.30f, trayY), stroke.width, StrokeCap.Round)
}

internal fun DrawScope.drawClock(color: Color) {
    val u = unit
    val stroke = u * 0.09f
    val radius = u * 0.36f
    val centre = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color, radius, centre, style = Stroke(width = stroke))
    drawLine(color, centre, centre.copy(y = centre.y - radius * 0.55f), stroke, StrokeCap.Round)
    drawLine(color, centre, centre.copy(x = centre.x + radius * 0.45f), stroke, StrokeCap.Round)
}

internal fun DrawScope.drawStopSquare(color: Color) {
    val side = unit * 0.30f
    drawRect(
        color = color,
        topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
        size = Size(side, side),
    )
}

internal fun DrawScope.drawCheckInCircle(color: Color, checkColor: Color) {
    val u = unit
    val centre = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color, u * 0.42f, centre)
    val stroke = u * 0.10f
    val left = Offset(centre.x - u * 0.17f, centre.y)
    val mid = Offset(centre.x - u * 0.04f, centre.y + u * 0.13f)
    val right = Offset(centre.x + u * 0.19f, centre.y - u * 0.14f)
    drawLine(checkColor, left, mid, stroke, StrokeCap.Round)
    drawLine(checkColor, mid, right, stroke, StrokeCap.Round)
}

internal fun DrawScope.drawWarning(color: Color) {
    val u = unit
    val cx = size.width / 2f
    val top = size.height * 0.14f
    val bottom = size.height * 0.82f
    val path = Path().apply {
        moveTo(cx, top)
        lineTo(cx + u * 0.42f, bottom)
        lineTo(cx - u * 0.42f, bottom)
        close()
    }
    drawPath(path, color, style = Stroke(width = u * 0.09f))
    drawLine(color, Offset(cx, top + u * 0.26f), Offset(cx, bottom - u * 0.25f), u * 0.09f, StrokeCap.Round)
    drawCircle(color, u * 0.05f, Offset(cx, bottom - u * 0.10f))
}

internal fun DrawScope.drawBin(color: Color) {
    val u = unit
    val stroke = u * 0.08f
    val cx = size.width / 2f
    val lidY = size.height * 0.26f
    drawLine(color, Offset(cx - u * 0.34f, lidY), Offset(cx + u * 0.34f, lidY), stroke, StrokeCap.Round)
    drawLine(color, Offset(cx - u * 0.14f, lidY - u * 0.10f), Offset(cx + u * 0.14f, lidY - u * 0.10f), stroke, StrokeCap.Round)
    val path = Path().apply {
        moveTo(cx - u * 0.25f, lidY)
        lineTo(cx - u * 0.19f, size.height * 0.84f)
        lineTo(cx + u * 0.19f, size.height * 0.84f)
        lineTo(cx + u * 0.25f, lidY)
    }
    drawPath(path, color, style = Stroke(width = stroke))
}

/** The "opens a screen" chevron on a group row. Mirrored in RTL, like any direction cue. */
internal fun DrawScope.drawChevron(color: Color, rtl: Boolean) {
    val u = unit
    val stroke = u * 0.10f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val reach = u * 0.18f
    val tipX = if (rtl) cx - reach else cx + reach
    val backX = if (rtl) cx + reach * 0.4f else cx - reach * 0.4f
    drawLine(color, Offset(backX, cy - reach), Offset(tipX, cy), stroke, StrokeCap.Round)
    drawLine(color, Offset(backX, cy + reach), Offset(tipX, cy), stroke, StrokeCap.Round)
}

/**
 * What stands in for artwork there is none of: four bars of a level meter.
 *
 * Drawn rather than shipped for the same reason the rest of this file is, and kept flat
 * and quiet on purpose — a placeholder that draws attention is worse than a plain tile.
 */
internal fun DrawScope.drawLevels(color: Color) {
    val u = unit
    val stroke = u * 0.09f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val gap = u * 0.17f
    // Heights as a fraction of the tile, so the shape reads the same at 40dp and 72dp.
    listOf(0.16f, 0.30f, 0.22f, 0.12f).forEachIndexed { index, half ->
        val x = cx + (index - 1.5f) * gap
        drawLine(
            color,
            Offset(x, cy - u * half),
            Offset(x, cy + u * half),
            stroke,
            StrokeCap.Round,
        )
    }
}

/** The ring around a running download. [progress] null spins a fixed arc at [spinDegrees]. */
internal fun DrawScope.drawProgressRing(
    trackColor: Color,
    progressColor: Color,
    progress: Float?,
    spinDegrees: Float,
) {
    val u = unit
    val stroke = u * 0.09f
    val inset = stroke / 2f + u * 0.04f
    val diameter = u - inset * 2f
    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    val arcSize = Size(diameter, diameter)
    drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(width = stroke))
    if (progress == null) {
        rotate(spinDegrees) {
            drawArc(progressColor, -90f, 90f, false, topLeft, arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    } else {
        drawArc(
            progressColor,
            -90f,
            360f * progress.coerceIn(0f, 1f),
            false,
            topLeft,
            arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
