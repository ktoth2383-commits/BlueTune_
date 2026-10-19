package hu.bluetune.ui.screens

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.bluetune.BuildConfig
import hu.bluetune.audio.AudioEngine
import hu.bluetune.audio.VolumeControl
import hu.bluetune.cloud.CloudRepo
import hu.bluetune.data.AppPrefs
import hu.bluetune.service.EqService
import hu.bluetune.ui.components.LabeledSlider
import hu.bluetune.ui.components.SectionCard
import hu.bluetune.ui.components.SwitchRow
import hu.bluetune.ui.components.WarningText
import hu.bluetune.ui.components.safeStart
import hu.bluetune.ui.components.toast
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val running by EqService.running.collectAsState()
    val status by AudioEngine.status.collectAsState()

    var boot by remember { mutableStateOf(AppPrefs.startOnBoot) }
    var global by remember { mutableStateOf(AppPrefs.globalMode) }
    var sessions by remember { mutableStateOf(AppPrefs.sessionMode) }
    var dynamics by remember { mutableStateOf(AppPrefs.preferDynamics) }
    var autoProfile by remember { mutableStateOf(AppPrefs.autoProfile) }
    var restoreVol by remember { mutableStateOf(AppPrefs.restoreVolume) }
    var maxVol by remember { mutableFloatStateOf(AppPrefs.maxVolumePercent.toFloat()) }

    val pm = remember { ctx.getSystemService(PowerManager::class.java) }
    var ignoringBattery by remember { mutableStateOf(pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Szolgáltatás") {
            SwitchRow(
                title = "Hangmotor fut",
                subtitle = "Háttérben tartja az EQ-t (értesítéssel)",
                checked = running,
                onChange = { on ->
                    AppPrefs.serviceWanted = on
                    if (on) {
                        if (!EqService.start(ctx)) toast(ctx, "Nem sikerült elindítani")
                    } else {
                        EqService.stop(ctx)
                    }
                },
            )
            SwitchRow(
                title = "Indítás bekapcsoláskor",
                subtitle = "Telefon újraindítás után automatikusan elindul",
                checked = boot,
                onChange = { boot = it; AppPrefs.startOnBoot = it },
            )
            if (!ignoringBattery) {
                WarningText("Az akkumulátor-optimalizálás leállíthatja az EQ-t, és megakadályozhatja az automatikus indítást Bluetooth csatlakozáskor.")
                Text(
                    "A megnyíló listában keresd meg a BlueTune-t, és állítsd „Nincs optimalizálva” / „Korlátlan” értékre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    // Play-barát megoldás: a rendszer listáját nyitjuk meg (nem kell külön jogosultság)
                    safeStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Akkubeállítások megnyitása") }
                TextButton(onClick = {
                    ignoringBattery = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
                }) { Text("Újraellenőrzés") }
            } else {
                Text("✓ Akku-optimalizálás kikapcsolva", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("Hangmotor") {
            SwitchRow(
                title = "Globális mód",
                subtitle = "A teljes hangkimenetre hat (session 0). A legtöbb telefonon működik, de gyártófüggő.",
                checked = global,
                onChange = { global = it; AppPrefs.globalMode = it; AudioEngine.rebuild() },
            )
            SwitchRow(
                title = "Lejátszó-session mód",
                subtitle = "A lejátszók (Spotify, YT Music, Poweramp…) saját sessionjére is rárakja az EQ-t",
                checked = sessions,
                onChange = { sessions = it; AppPrefs.sessionMode = it; AudioEngine.rebuild() },
            )
            SwitchRow(
                title = "DynamicsProcessing motor",
                subtitle = "10 sáv + preamp + balansz + limiter (Android 9+). Kikapcsolva: klasszikus Equalizer.",
                checked = dynamics,
                onChange = { dynamics = it; AppPrefs.preferDynamics = it; AudioEngine.rebuild() },
            )
            Text("Motor: ${status.backend}", style = MaterialTheme.typography.bodySmall)
            status.sessions.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!status.hasControl) WarningText("Egy másik EQ app átvette az irányítást.")
            status.errors.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                enabled = running,
                onClick = { AudioEngine.rebuild(); toast(ctx, "Motor újraindítva") },
            ) { Text("Motor újraindítása") }
        }

        SectionCard("Bluetooth eszközök") {
            SwitchRow(
                title = "Eszközönkénti automatikus profil",
                subtitle = "Minden fejhallgató / hangszóró / autó a saját hangzását kapja csatlakozáskor",
                checked = autoProfile,
                onChange = { autoProfile = it; AppPrefs.autoProfile = it },
            )
            SwitchRow(
                title = "Hangerő visszaállítása eszközönként",
                subtitle = "A csúszkával beállított hangerőt is megjegyzi",
                checked = restoreVol,
                onChange = { restoreVol = it; AppPrefs.restoreVolume = it },
            )
        }

        SectionCard("Hallásvédelem") {
            LabeledSlider(
                label = "Maximális médiahangerő",
                value = maxVol,
                range = 30f..100f,
                valueText = if (maxVol >= 100f) "Nincs korlát" else "${maxVol.roundToInt()}%",
                onChange = { maxVol = (it / 5f).roundToInt() * 5f },
                onFinished = {
                    AppPrefs.maxVolumePercent = maxVol.roundToInt()
                    VolumeControl.enforceCap()
                },
            )
            Text(
                "A rendszer-hangerőgombokkal sem lehet efölé menni, amíg az app fut.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Névjegy") {
            Text("BlueTune ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Bluetooth hangeszköz-tuning: 10 sávos EQ, basszus, treble, boost, térhatás, balansz, limiter, eszközprofilok.\n" +
                    "Firebase: " + if (CloudRepo.available) "beállítva ✓" else "nincs beállítva (opcionális)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp).fillMaxWidth())
    }
}
