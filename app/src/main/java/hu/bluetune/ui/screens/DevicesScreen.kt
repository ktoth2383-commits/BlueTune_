package hu.bluetune.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.bluetune.audio.AudioEngine
import hu.bluetune.audio.ToneGenerator
import hu.bluetune.audio.VolumeControl
import hu.bluetune.bt.BluetoothMonitor
import hu.bluetune.data.DeviceProfileStore
import hu.bluetune.ui.components.InfoRow
import hu.bluetune.ui.components.LabeledSlider
import hu.bluetune.ui.components.SectionCard
import hu.bluetune.ui.components.SwitchRow
import hu.bluetune.ui.components.WarningText
import hu.bluetune.ui.components.safeStart
import hu.bluetune.ui.components.toast
import java.text.DateFormat
import java.util.Date
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun DevicesScreen() {
    val active by BluetoothMonitor.active.collectAsState()
    val outputs by BluetoothMonitor.outputs.collectAsState()
    val profiles by DeviceProfileStore.profiles.collectAsState()
    val perm by BluetoothMonitor.hasPermission.collectAsState()
    val ctx = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!perm) {
            SectionCard("Bluetooth engedély szükséges") {
                Text(
                    "Az eszköz nevéhez, akkuszintjéhez és kodekjéhez engedélyezd a „Közeli eszközök” jogosultságot.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = {
                    safeStart(
                        ctx,
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)),
                    )
                }) { Text("Engedélyek megnyitása") }
            }
        }

        // ------------------------------------------------ aktív eszköz
        SectionCard(
            "Aktív zenei eszköz",
            trailing = { TextButton(onClick = { BluetoothMonitor.refreshNow() }) { Text("Frissítés") } },
        ) {
            val d = active
            if (d == null) {
                Text(
                    "Most nincs Bluetooth hangeszköz csatlakoztatva. Csatlakoztass fejhallgatót, hangszórót vagy autót – a BlueTune automatikusan felismeri és külön profilt ment neki.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InfoRow("Név", d.name)
                InfoRow("Típus", d.typeLabel)
                if (d.address.isNotBlank()) InfoRow("Cím", d.address)
                InfoRow("Akkumulátor", d.battery?.let { "$it%" } ?: "nem elérhető")
                InfoRow("Kodek", d.codec ?: if (d.typeId == 8) "nem olvasható ki" else "—")
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        DeviceProfileStore.upsert(d.key, d.name, settings = AudioEngine.settings.value, volume = VolumeControl.volume.value)
                        toast(ctx, "Profil mentve: ${d.name}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Jelenlegi hangzás + hangerő mentése ehhez az eszközhöz") }
            }
        }

        // ------------------------------------------------ összes BT kimenet
        if (outputs.size > 1) {
            SectionCard("Csatlakoztatott Bluetooth kimenetek") {
                outputs.forEach { o ->
                    Text(o.name, style = MaterialTheme.typography.bodyLarge)
                    Text(o.typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }

        // ------------------------------------------------ profilok
        SectionCard("Mentett eszközprofilok (${profiles.size})") {
            if (profiles.isEmpty()) {
                Text(
                    "Amint csatlakozik egy Bluetooth eszköz, itt megjelenik a saját hangzásával.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
            profiles.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (p.key == active?.key) "● " else "") + p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (p.key == active?.key) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            listOfNotNull(
                                p.settings.presetName ?: "Egyéni",
                                p.volume?.let { "hangerő $it" },
                                "utoljára: " + df.format(Date(p.lastSeen)),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        AudioEngine.update { cur -> p.settings.copy(enabled = cur.enabled) }
                        toast(ctx, "Betöltve: ${p.name}")
                    }) { Text("Betöltés") }
                    TextButton(onClick = { DeviceProfileStore.delete(p.key) }) { Text("Törlés") }
                }
                HorizontalDivider()
            }
        }

        // ------------------------------------------------ hangteszt
        SectionCard("Hangteszt") { ToneTestPanel() }

        // ------------------------------------------------ rendszer
        SectionCard("Rendszerbeállítások") {
            Text(
                "A Bluetooth kodek (LDAC / aptX / AAC), a mintavétel és az „abszolút hangerő” a Fejlesztői beállításokban állítható.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { safeStart(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, modifier = Modifier.weight(1f)) {
                    Text("Bluetooth")
                }
                OutlinedButton(onClick = { safeStart(ctx, Intent(Settings.ACTION_SOUND_SETTINGS)) }, modifier = Modifier.weight(1f)) {
                    Text("Hang")
                }
            }
            OutlinedButton(
                onClick = { safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Fejlesztői beállítások (kodek)") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToneTestPanel() {
    val playing by ToneGenerator.playing.collectAsState()
    // csúszka pozíció 0..1 → 20 Hz..20 kHz logaritmikusan
    var pos by remember { mutableFloatStateOf(posFromFreq(ToneGenerator.frequency)) }
    var channel by remember { mutableStateOf(ToneGenerator.channel) }
    var sweep by remember { mutableStateOf(ToneGenerator.sweep) }

    DisposableEffect(Unit) { onDispose { ToneGenerator.stop() } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WarningText("Kezdd alacsony hangerőn! A tesztjelre az EQ is hat.")
        LabeledSlider(
            label = "Frekvencia",
            value = pos,
            range = 0f..1f,
            valueText = freqText(freqFromPos(pos)),
            enabled = !sweep,
            onChange = {
                pos = it
                ToneGenerator.frequency = freqFromPos(it)
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ToneGenerator.Channel.LEFT to "Bal",
                ToneGenerator.Channel.BOTH to "Mindkettő",
                ToneGenerator.Channel.RIGHT to "Jobb",
            ).forEach { (c, label) ->
                FilterChip(
                    selected = channel == c,
                    onClick = {
                        channel = c
                        ToneGenerator.channel = c
                    },
                    label = { Text(label) },
                )
            }
        }
        SwitchRow(
            title = "Sweep 20 Hz → 20 kHz",
            subtitle = "Végigfut a teljes hangtartományon (12 mp)",
            checked = sweep,
            onChange = {
                sweep = it
                ToneGenerator.sweep = it
            },
        )
        Button(
            onClick = { if (playing) ToneGenerator.stop() else ToneGenerator.start() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (playing) "■ Leállítás" else "▶ Tesztjel lejátszása") }
    }
}

private fun freqFromPos(p: Float): Float = 20f * 1000f.pow(p.coerceIn(0f, 1f))
private fun posFromFreq(f: Float): Float = (ln(f / 20f) / ln(1000f)).coerceIn(0f, 1f)
private fun freqText(f: Float): String =
    if (f >= 1000f) "%.1f kHz".format(f / 1000f) else "${f.roundToInt()} Hz"
