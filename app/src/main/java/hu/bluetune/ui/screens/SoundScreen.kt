package hu.bluetune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import hu.bluetune.audio.AudioEngine
import hu.bluetune.audio.VolumeControl
import hu.bluetune.bt.BluetoothMonitor
import hu.bluetune.data.AppPrefs
import hu.bluetune.data.PresetStore
import hu.bluetune.model.Bands
import hu.bluetune.model.BuiltInPresets
import hu.bluetune.service.EqService
import hu.bluetune.ui.components.EqCurve
import hu.bluetune.ui.components.LabeledSlider
import hu.bluetune.ui.components.SectionCard
import hu.bluetune.ui.components.SwitchRow
import hu.bluetune.ui.components.VerticalSlider
import hu.bluetune.ui.components.WarningText
import hu.bluetune.ui.components.formatDb
import hu.bluetune.ui.components.formatShortDb
import hu.bluetune.ui.components.toast
import hu.bluetune.ui.theme.Violet
import kotlin.math.abs
import kotlin.math.roundToInt

private fun snapHalf(v: Float) = (v * 2f).roundToInt() / 2f

@Composable
fun SoundScreen() {
    val s by AudioEngine.settings.collectAsState()
    val vol by VolumeControl.volume.collectAsState()
    val maxVol by VolumeControl.max.collectAsState()
    val custom by PresetStore.custom.collectAsState()
    var showSave by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DeviceHeader()

        // ------------------------------------------------ Hangerő
        SectionCard("Hangerő") {
            val cap = AppPrefs.maxVolumePercent
            LabeledSlider(
                label = "Médiahangerő" + if (cap < 100) " (korlát: $cap%)" else "",
                value = vol.toFloat(),
                range = 0f..maxVol.coerceAtLeast(1).toFloat(),
                valueText = "${vol * 100 / maxVol.coerceAtLeast(1)}%",
                onChange = { VolumeControl.set(it.roundToInt()) },
                steps = (maxVol - 1).coerceAtLeast(0),
            )
            LabeledSlider(
                label = "Hangerő boost",
                value = s.loudness,
                range = 0f..15f,
                valueText = "+%.1f dB".format(s.loudness),
                onChange = { v -> AudioEngine.update { it.copy(loudness = snapHalf(v)) } },
            )
            if (s.loudness > 6f) WarningText("Erős boost torzíthat, és hosszan károsíthatja a hallást / a hangszórót.")
        }

        // ------------------------------------------------ 10 sávos EQ
        SectionCard(
            "10 sávos EQ",
            trailing = {
                TextButton(onClick = {
                    AudioEngine.update { it.copy(bands = List(Bands.COUNT) { 0f }, preamp = 0f, presetName = null) }
                }) { Text("Nullázás") }
            },
        ) {
            EqCurve(s, Modifier.fillMaxWidth().height(110.dp))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Bands.LABELS.forEachIndexed { i, label ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            formatShortDb(s.bands[i]),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (abs(s.bands[i]) > 0.1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        VerticalSlider(
                            value = s.bands[i],
                            onValueChange = { v ->
                                AudioEngine.update { cur ->
                                    cur.copy(
                                        bands = cur.bands.toMutableList().also { it[i] = snapHalf(v) },
                                        presetName = null,
                                    )
                                }
                            },
                            range = Bands.MIN_DB..Bands.MAX_DB,
                            modifier = Modifier.height(200.dp).width(30.dp),
                            onReset = {
                                AudioEngine.update { cur ->
                                    cur.copy(bands = cur.bands.toMutableList().also { it[i] = 0f }, presetName = null)
                                }
                            },
                        )
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "Tipp: dupla koppintás egy sávon = nullázás",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledSlider(
                label = "Előerősítés (preamp)",
                value = s.preamp,
                range = -12f..12f,
                valueText = formatDb(s.preamp),
                onChange = { v -> AudioEngine.update { it.copy(preamp = snapHalf(v)) } },
            )
        }

        // ------------------------------------------------ Hangszín
        SectionCard("Hangszín") {
            LabeledSlider(
                label = "Basszus",
                value = s.bass,
                range = -12f..12f,
                valueText = formatDb(s.bass),
                onChange = { v -> AudioEngine.update { it.copy(bass = snapHalf(v), presetName = null) } },
            )
            LabeledSlider(
                label = "Magas hangok (treble)",
                value = s.treble,
                range = -12f..12f,
                valueText = formatDb(s.treble),
                onChange = { v -> AudioEngine.update { it.copy(treble = snapHalf(v), presetName = null) } },
            )
            LabeledSlider(
                label = "Basszuskiemelés (Bass Boost)",
                value = s.bassBoost.toFloat(),
                range = 0f..1000f,
                valueText = "${s.bassBoost / 10}%",
                onChange = { v -> AudioEngine.update { it.copy(bassBoost = (v / 10f).roundToInt() * 10) } },
            )
            LabeledSlider(
                label = "Térhatás (3D / Virtualizer)",
                value = s.virtualizer.toFloat(),
                range = 0f..1000f,
                valueText = "${s.virtualizer / 10}%",
                onChange = { v -> AudioEngine.update { it.copy(virtualizer = (v / 10f).roundToInt() * 10) } },
            )
            LabeledSlider(
                label = "Balansz (bal ↔ jobb)",
                value = s.balance,
                range = -1f..1f,
                valueText = balanceText(s.balance),
                onChange = { v ->
                    val snapped = (v * 20f).roundToInt() / 20f
                    AudioEngine.update { it.copy(balance = if (abs(snapped) < 0.03f) 0f else snapped) }
                },
            )
            SwitchRow(
                title = "Torzításvédelem (limiter)",
                subtitle = "Megfogja a csúcsokat erős basszus / boost mellett",
                checked = s.limiter,
                onChange = { on -> AudioEngine.update { it.copy(limiter = on) } },
            )
        }

        // ------------------------------------------------ Gyors presetek
        SectionCard("Gyors presetek") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (custom + BuiltInPresets.all).forEach { p ->
                    FilterChip(
                        selected = s.presetName == p.name,
                        onClick = { AudioEngine.applyPreset(p) },
                        label = { Text(p.name) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showSave = true }, modifier = Modifier.weight(1f)) { Text("Mentés presetként") }
            OutlinedButton(
                onClick = { AudioEngine.update { it.withSound(BuiltInPresets.all.first().settings, null) } },
                modifier = Modifier.weight(1f),
            ) { Text("Minden alaphelyzetbe") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showSave) {
        SavePresetDialog(
            initial = s.presetName ?: "",
            onDismiss = { showSave = false },
            onSave = { name ->
                PresetStore.save(name, AudioEngine.settings.value)
                AudioEngine.update { it.copy(presetName = name.trim().take(40)) }
                toast(ctx, "Preset mentve: $name")
                showSave = false
            },
        )
    }
}

fun balanceText(b: Float): String = when {
    abs(b) < 0.01f -> "Közép"
    b < 0 -> "B ${(abs(b) * 100).roundToInt()}%"
    else -> "J ${(b * 100).roundToInt()}%"
}

@Composable
private fun DeviceHeader() {
    val s by AudioEngine.settings.collectAsState()
    val dev by BluetoothMonitor.active.collectAsState()
    val status by AudioEngine.status.collectAsState()
    val running by EqService.running.collectAsState()
    val ctx = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            Violet.copy(alpha = 0.10f),
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (dev != null) "🎧 ${dev!!.name}" else "🔈 Nincs Bluetooth eszköz",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    val details = dev?.let { d ->
                        listOfNotNull(d.typeLabel, d.battery?.let { "Akku $it%" }, d.codec).joinToString(" · ")
                    } ?: "Telefon hangszóró / vezetékes kimenet"
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = s.enabled,
                    onCheckedChange = { on ->
                        AudioEngine.setEnabled(on)
                        if (on && !running) {
                            AppPrefs.serviceWanted = true
                            if (!EqService.start(ctx)) toast(ctx, "A szolgáltatás nem indult el")
                        }
                    },
                )
            }
            Text(
                when {
                    !running -> "Hangmotor leállítva – kapcsold be a jobb oldali kapcsolóval"
                    !s.enabled -> "EQ szüneteltetve"
                    else -> "Aktív: ${s.presetName ?: "Egyéni beállítás"}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (running && s.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running) {
                Text(
                    "Motor: ${status.backend} · ${status.sessions.size} session",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running && !status.hasControl) {
                WarningText("Egy másik hangeffekt-app átvette az irányítást. Beállítások → Motor újraindítása.")
            }
        }
    }
}

@Composable
fun SavePresetDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mentés presetként") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Preset neve") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Mentés") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}
