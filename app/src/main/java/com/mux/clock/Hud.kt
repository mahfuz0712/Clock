package com.mux.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF030A10)
val PanelBg = Color(0xFF050E14)
val Notch = Color(0xFF02060A)
val Mono = FontFamily.Monospace

class AccentTheme(val name: String, val main: Color, val dim: Color, val second: Color)

val ACCENTS = listOf(
    AccentTheme("CYAN", Color(0xFF00E5FF), Color(0xFF0B4658), Color(0xFFFFB300)),
    AccentTheme("GOLD", Color(0xFFFFB300), Color(0xFF5E4300), Color(0xFF00E5FF)),
    AccentTheme("GREEN", Color(0xFF00FF66), Color(0xFF0A5A30), Color(0xFFFFB300)),
    AccentTheme("RED", Color(0xFFFF3D3D), Color(0xFF5E1717), Color(0xFF00E5FF))
)
private val theme: AccentTheme get() = ACCENTS[Store.accent.coerceIn(0, ACCENTS.lastIndex)]
val cMain: Color get() = theme.main
val cDim: Color get() = theme.dim
val cSecond: Color get() = theme.second
val cSub: Color get() = theme.main.copy(alpha = 0.62f)
val cLine: Color get() = theme.main.copy(alpha = 0.8f)
val cWhite = Color(0xFFD8F3F7)
val cRed = Color(0xFFFF6B6B)

@Composable
fun T(
    text: String, size: Float = 14f, color: Color = cMain, bold: Boolean = false,
    mod: Modifier = Modifier, lines: Int = 1, align: TextAlign = TextAlign.Start
) = Text(
    text, modifier = mod, color = color, fontFamily = Mono, fontSize = size.sp, letterSpacing = 0.5.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = align
)

/** Rectangle with the top-left and bottom-right corners cut diagonally. */
class ChamferShape(
    private val cut: Dp,
    private val tl: Boolean = true, private val br: Boolean = true,
    private val tr: Boolean = false, private val bl: Boolean = false
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(if (tl) c else 0f, 0f)
            lineTo(if (tr) w - c else w, 0f)
            if (tr) lineTo(w, c)
            lineTo(w, if (br) h - c else h)
            if (br) lineTo(w - c, h)
            lineTo(if (bl) c else 0f, h)
            if (bl) lineTo(0f, h - c)
            lineTo(0f, if (tl) c else 0f)
            close()
        }
        return Outline.Generic(p)
    }
}

/** Dark panel with cut corners, a thin glowing border and a small dark notch in each cut. */
fun Modifier.hudPanel(fill: Color = PanelBg, border: Color = cLine, cut: Dp = 14.dp, glow: Boolean = false): Modifier {
    val shape = ChamferShape(cut)
    return this
        .drawBehind {
            val c = cut.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
            val a = Path().apply { moveTo(0f, 0f); lineTo(c, 0f); lineTo(0f, c); close() }
            val b = Path().apply { moveTo(size.width, size.height); lineTo(size.width - c, size.height); lineTo(size.width, size.height - c); close() }
            drawPath(a, Notch)
            drawPath(b, Notch)
        }
        .background(if (glow) Brush.radialGradient(listOf(Color(0xFF123039), PanelBg)) else SolidColor(fill), shape)
        .border(1.5.dp, border, shape)
}

@Composable
fun HudBackground(content: @Composable BoxScope.() -> Unit) {
    val line = cMain.copy(alpha = 0.07f)
    val arc = cMain.copy(alpha = 0.22f)
    Box(
        Modifier.fillMaxSize().background(Bg).drawBehind {
            val step = 36.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y)); y += step }
            drawCircle(arc, size.width * 0.45f, Offset(size.width * 0.5f, -size.height * 0.02f), style = Stroke(1.dp.toPx()))
            drawCircle(arc, size.width * 0.34f, Offset(size.width * 1.02f, size.height * 0.3f), style = Stroke(1.dp.toPx()))
        },
        content = content
    )
}

@Composable
fun HudTag(text: String) {
    Box(Modifier.background(cMain).padding(horizontal = 10.dp, vertical = 2.dp)) {
        T(text, 11f, Color(0xFF031014), true)
    }
}

/** Main panel with an optional filled label tab on the top edge. */
@Composable
fun HudCard(
    modifier: Modifier = Modifier, tag: String? = null, glow: Boolean = false, pad: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.hudPanel(PanelBg, cLine, 16.dp, glow)) {
        Box(
            Modifier.fillMaxSize().padding(start = pad, end = pad, bottom = pad, top = if (tag != null) 32.dp else pad),
            content = content
        )
        if (tag != null) Box(Modifier.align(Alignment.TopStart).padding(start = 24.dp)) { HudTag(tag) }
    }
}

@Composable
fun StatusCard(title: String, info: String, tag: String = "STATUS", color: Color = cSecond) {
    Box(Modifier.fillMaxWidth().hudPanel(PanelBg, cLine, 14.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 12.dp)) {
            T(title, 20f, color, true, lines = 2)
            T(info, 11f, cSub, mod = Modifier.padding(top = 4.dp), lines = 2)
        }
        Box(Modifier.align(Alignment.TopStart).padding(start = 20.dp)) { HudTag(tag) }
    }
}

@Composable
fun HudButton(
    label: String, active: Boolean = false, mod: Modifier = Modifier, height: Dp = 60.dp,
    textSize: Float = 15f, color: Color = cMain, onClick: () -> Unit
) {
    val shape = ChamferShape(10.dp)
    Box(
        mod.height(height)
            .hudPanel(if (active) cDim else Color(0xFF06131A), color.copy(alpha = 0.85f), 10.dp)
            .clip(shape).clickable(onClick = onClick),
        Alignment.Center
    ) { T(label, textSize, color, true, align = TextAlign.Center) }
}

@Composable
fun Chip(label: String, on: Boolean, mod: Modifier = Modifier, onClick: () -> Unit) {
    val shape = ChamferShape(6.dp)
    Box(
        mod.hudPanel(if (on) cDim else Color(0xFF06131A), if (on) cMain else cMain.copy(alpha = 0.45f), 6.dp)
            .clip(shape).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        Alignment.Center
    ) { T(label, 11f, if (on) cMain else cSub, on, align = TextAlign.Center) }
}

@Composable
fun HudHeader(title: String, sub: String) {
    val main = cMain
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(20.dp)) {
            val c = center
            val r = size.minDimension / 2f
            val outer = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }
            val q = r * 0.45f
            val inner = Path().apply { moveTo(c.x, c.y - q); lineTo(c.x + q, c.y); lineTo(c.x, c.y + q); lineTo(c.x - q, c.y); close() }
            drawPath(outer, main, style = Stroke(1.8.dp.toPx()))
            drawPath(inner, main)
        }
        T(title, 26f, cMain, true, Modifier.padding(start = 12.dp).weight(1f))
        T(sub, 13f, cSub)
    }
}

@Composable
fun sliderColors() = SliderDefaults.colors(thumbColor = cSecond, activeTrackColor = cMain, inactiveTrackColor = cDim)
