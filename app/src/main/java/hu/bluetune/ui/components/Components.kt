package hu.bluetune.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.bluetune.model.EqSettings
import hu.bluetune.ui.theme.Warn
import kotlin.math.ln
import kotlin.math.pow

fun formatDb(v: Float): String = (if (v > 0.04f) "+" else "") + "%.1f dB".format(v)

fun formatShortDb(v: Float): String {
    val r = Math.round(v * 2) / 2f
    return when {
        r == 0f -> "0"
        r % 1f == 0f -> (if (r > 0) "+" else "") + r.toInt()
        else -> (if (r > 0) "+" else "") + "%.1f".format(r)
    }
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

fun safeStart(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        toast(ctx, "Ezen a készüléken ez a képernyő nem érhető el.")
    } catch (_: SecurityException) {
        toast(ctx, "A rendszer nem engedi megnyitni.")
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onFinished: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onFinished,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun WarningText(text: String) {
    Text("⚠ $text", style = MaterialTheme.typography.bodySmall, color = Warn)
}

/**
 * Függőleges csúszka EQ-sávokhoz. Húzás / koppintás állít, dupla koppintás nulláz.
 */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val change by rememberUpdatedState(onValueChange)
    val reset by rememberUpdatedState(onReset)
    val span = range.endInclusive - range.start

    fun toValue(y: Float, height: Int): Float {
        val frac = 1f - (y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        return range.start + frac * span
    }

    Canvas(
        modifier
            .pointerInput(range) {
                detectVerticalDragGestures(
                    onDragStart = { o -> change(toValue(o.y, size.height)) },
                ) { ch, _ ->
                    ch.consume()
                    change(toValue(ch.position.y, size.height))
                }
            }
            .pointerInput(range) {
                detectTapGestures(
                    onDoubleTap = { reset?.invoke() },
                    onTap = { o -> change(toValue(o.y, size.height)) },
                )
            },
    ) {
        val cx = size.width / 2f
        val r = 9.dp.toPx()
        val stroke = 6.dp.toPx()
        val frac = ((value - range.start) / span).coerceIn(0f, 1f)
        val zeroFrac = ((0f - range.start) / span).coerceIn(0f, 1f)
        val y = (size.height * (1f - frac)).coerceIn(r, size.height - r)
        val y0 = size.height * (1f - zeroFrac)
        drawLine(track, Offset(cx, r), Offset(cx, size.height - r), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(primary.copy(alpha = 0.25f), Offset(cx - r, y0), Offset(cx + r, y0), strokeWidth = 1.dp.toPx())
        drawLine(primary, Offset(cx, y0), Offset(cx, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(primary, radius = r, center = Offset(cx, y))
        drawCircle(Color.White.copy(alpha = 0.9f), radius = r * 0.38f, center = Offset(cx, y))
    }
}

/** A teljes frekvenciamenet görbéje (20 Hz – 20 kHz, ±18 dB). */
@Composable
fun EqCurve(settings: EqSettings, modifier: Modifier = Modifier, showGrid: Boolean = true) {
    val primary = if (settings.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val minDb = -18f
        val maxDb = 18f
        fun y(db: Float) = h * (1f - (db - minDb) / (maxDb - minDb))
        fun x(f: Float) = w * ((ln(f) - ln(20f)) / (ln(20000f) - ln(20f)))

        if (showGrid) {
            listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
                drawLine(
                    grid.copy(alpha = if (db == 0f) 0.9f else 0.35f),
                    Offset(0f, y(db)), Offset(w, y(db)),
                    strokeWidth = if (db == 0f) 1.5f else 1f,
                )
            }
            listOf(100f, 1000f, 10000f).forEach { f ->
                drawLine(grid.copy(alpha = 0.35f), Offset(x(f), 0f), Offset(x(f), h), strokeWidth = 1f)
            }
        }

        val gains = settings.effectiveGains()
        val line = Path()
        val fill = Path()
        val steps = 96
        for (i in 0..steps) {
            val f = 20f * 1000f.pow(i / steps.toFloat())
            val px = x(f)
            val py = y((settings.gainAt(f, gains) + settings.preamp).coerceIn(minDb, maxDb))
            if (i == 0) {
                line.moveTo(px, py)
                fill.moveTo(px, y(0f))
                fill.lineTo(px, py)
            } else {
                line.lineTo(px, py)
                fill.lineTo(px, py)
            }
        }
        fill.lineTo(w, y(0f))
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(primary.copy(alpha = 0.35f), primary.copy(alpha = 0.03f))))
        drawPath(line, primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
