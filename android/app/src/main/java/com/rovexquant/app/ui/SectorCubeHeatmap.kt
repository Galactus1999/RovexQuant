package com.rovexquant.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class SectorHeatmapTile(
    val label: String,
    val shortLabel: String,
    val benchmark: String,
    val changePercent: Double?,
    val lastPrice: Double?,
    val isFresh: Boolean
)

private data class ProjectedSectorCube(
    val tile: SectorHeatmapTile,
    val center: Offset,
    val depth: Float,
    val top: List<Offset>,
    val left: List<Offset>,
    val front: List<Offset>
)

@Composable
fun SectorCubeDialog(
    sectors: List<SectorHeatmapTile>,
    liveCount: Int,
    onDismiss: () -> Unit,
    onSelectSector: (SectorHeatmapTile) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                .testTag("market.sector.cube.dialog"),
            shape = RoundedCornerShape(26.dp),
            color = PanelRaised
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("SECTOR MATRIX", color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("$liveCount / ${sectors.size} fresh sector quotes",
                            color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Drag to rotate the cube · tap any sector block to open its chart",
                    color = Muted, fontSize = 10.sp
                )
                Spacer(Modifier.height(8.dp))
                SectorCubeHeatmap(
                    sectors = sectors,
                    onSelectSector = onSelectSector,
                    modifier = Modifier.fillMaxWidth().height(390.dp)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendDot(Color(0xFF20B978), "Positive")
                    Spacer(Modifier.width(14.dp))
                    LegendDot(Color(0xFFE14E5E), "Negative")
                    Spacer(Modifier.width(14.dp))
                    LegendDot(Color(0xFF657386), "Stale / unavailable")
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
            drawCircle(color)
        }
        Text(label, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SectorCubeHeatmap(
    sectors: List<SectorHeatmapTile>,
    onSelectSector: (SectorHeatmapTile) -> Unit,
    modifier: Modifier = Modifier
) {
    var rotationX by remember { mutableFloatStateOf(-0.42f) }
    var rotationY by remember { mutableFloatStateOf(0.58f) }
    val density = LocalDensity.current
    val tapRadius = with(density) { 30.dp.toPx() }
    val overlaySize = 52.dp
    val overlayHalfPx = with(density) { overlaySize.toPx() / 2f }

    BoxWithConstraints(modifier.testTag("market.sector.cube.canvas")) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val projected = remember(sectors, rotationX, rotationY, widthPx, heightPx) {
            projectSectorCubes(sectors, widthPx, heightPx, rotationX, rotationY)
        }
        val currentProjected by rememberUpdatedState(projected)

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(sectors) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        rotationY += drag.x / size.width.coerceAtLeast(1) * 2.2f
                        rotationX = (rotationX + drag.y / size.height.coerceAtLeast(1) * 2.2f)
                            .coerceIn(-1.35f, 1.35f)
                    }
                }
                .pointerInput(sectors) {
                    detectTapGestures { point ->
                        val nearest = currentProjected.minByOrNull { cube ->
                            val dx = cube.center.x - point.x
                            val dy = cube.center.y - point.y
                            dx * dx + dy * dy
                        }
                        if (nearest != null) {
                            val dx = nearest.center.x - point.x
                            val dy = nearest.center.y - point.y
                            if (dx * dx + dy * dy <= tapRadius * tapRadius) {
                                onSelectSector(nearest.tile)
                            }
                        }
                    }
                }
        ) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            val labelSize = 8.sp.toPx()
            val valueSize = 8.sp.toPx()
            projected.sortedBy { it.depth }.forEach { cube ->
                val positive = cube.tile.changePercent?.let { it >= 0.0 } == true
                val magnitude = ((cube.tile.changePercent ?: 0.0).let(::abs) / 5.0).coerceIn(0.0, 1.0).toFloat()
                val base = when {
                    !cube.tile.isFresh || cube.tile.changePercent == null -> Color(0xFF657386)
                    positive -> Color(
                        red = 0.035f + 0.06f * magnitude,
                        green = 0.30f + 0.34f * magnitude,
                        blue = 0.20f + 0.08f * magnitude
                    )
                    else -> Color(
                        red = 0.34f + 0.34f * magnitude,
                        green = 0.055f + 0.04f * magnitude,
                        blue = 0.10f + 0.04f * magnitude
                    )
                }
                drawFace(cube.left, shade(base, 0.60f))
                drawFace(cube.front, base)
                drawFace(cube.top, shade(base, 1.35f))
                val textCenter = Offset(
                    cube.front.map { it.x }.average().toFloat(),
                    cube.front.map { it.y }.average().toFloat()
                )
                paint.textSize = labelSize
                paint.alpha = 255
                drawContext.canvas.nativeCanvas.drawText(
                    cube.tile.shortLabel.take(11), textCenter.x, textCenter.y - valueSize * 0.15f, paint
                )
                paint.textSize = valueSize
                val moveText = cube.tile.changePercent?.takeIf { cube.tile.isFresh }
                    ?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"
                drawContext.canvas.nativeCanvas.drawText(
                    moveText, textCenter.x, textCenter.y + valueSize * 0.95f, paint
                )
            }
        }

        projected.forEach { cube ->
            val move = cube.tile.changePercent?.takeIf { cube.tile.isFresh }
                ?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "Stale or unavailable"
            Box(
                Modifier.offset {
                    IntOffset(
                        (cube.center.x - overlayHalfPx).roundToInt(),
                        (cube.center.y - overlayHalfPx).roundToInt()
                    )
                }
                    .size(overlaySize)
                    .semantics {
                        contentDescription = "${cube.tile.label}, $move"
                        onClick(label = "Open ${cube.tile.label}") {
                            onSelectSector(cube.tile)
                            true
                        }
                    }
                    .testTag("sector.cube.${cube.tile.benchmark}")
            )
        }
    }
}

private fun drawFace(points: List<Offset>, color: Color) {
    if (points.size != 4) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color)
    drawPath(path, Color.White.copy(alpha = 0.26f), style = Stroke(width = 1.1f))
}

private fun shade(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha
)

private fun projectSectorCubes(
    sectors: List<SectorHeatmapTile>,
    width: Float,
    height: Float,
    rotationX: Float,
    rotationY: Float
): List<ProjectedSectorCube> {
    if (width <= 0f || height <= 0f) return emptyList()
    val positions = buildList {
        for (y in 1 downTo -1) {
            for (z in 1 downTo -1) {
                for (x in -1..1) {
                    if (!(x != 0 && y != 0 && z != 0)) add(Triple(x, y, z))
                }
            }
        }
    }
    val unit = min(width, height) * 0.245f
    val spacing = 0.86f
    val half = 0.31f
    val focal = 5.2f
    val sinX = sin(rotationX)
    val cosX = cos(rotationX)
    val sinY = sin(rotationY)
    val cosY = cos(rotationY)

    fun project(x: Float, y: Float, z: Float): Pair<Offset, Float> {
        val rotatedX = x * cosY + z * sinY
        val rotatedZ = -x * sinY + z * cosY
        val rotatedY = y * cosX - rotatedZ * sinX
        val depth = y * sinX + rotatedZ * cosX
        val perspective = focal / (focal + depth * 0.18f)
        return Offset(
            width / 2f + rotatedX * unit * perspective,
            height / 2f - rotatedY * unit * perspective
        ) to depth
    }

    return sectors.take(positions.size).mapIndexed { index, tile ->
        val (gx, gy, gz) = positions[index]
        val cx = gx * spacing
        val cy = gy * spacing
        val cz = gz * spacing
        val vertices = listOf(
            project(cx - half, cy - half, cz - half),
            project(cx + half, cy - half, cz - half),
            project(cx + half, cy + half, cz - half),
            project(cx - half, cy + half, cz - half),
            project(cx - half, cy - half, cz + half),
            project(cx + half, cy - half, cz + half),
            project(cx + half, cy + half, cz + half),
            project(cx - half, cy + half, cz + half)
        )
        fun face(indices: List<Int>) = indices.map { vertices[it].first }
        val center = project(cx, cy, cz)
        ProjectedSectorCube(
            tile = tile,
            center = center.first,
            depth = center.second,
            top = face(listOf(3, 2, 6, 7)),
            left = face(listOf(0, 3, 7, 4)),
            front = face(listOf(4, 5, 6, 7))
        )
    }
}
