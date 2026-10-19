@file:OptIn(ExperimentalMaterial3Api::class)

package hu.bluetune.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import hu.bluetune.cloud.CloudRepo
import kotlinx.coroutines.launch
import hu.bluetune.audio.AudioEngine
import hu.bluetune.audio.VolumeControl
import hu.bluetune.bt.BluetoothMonitor
import hu.bluetune.data.AppPrefs
import hu.bluetune.service.EqService
import hu.bluetune.ui.screens.CloudScreen
import hu.bluetune.ui.screens.DevicesScreen
import hu.bluetune.ui.screens.PresetsScreen
import hu.bluetune.ui.screens.SettingsScreen
import hu.bluetune.ui.screens.SoundScreen
import hu.bluetune.ui.theme.BlueTuneTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        BluetoothMonitor.onPermissionChanged()
        startServiceIfWanted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BlueTuneTheme { AppRoot() } }

        // Az app megnyitása mindig elindítja a háttérszolgáltatást.
        // Leállítani az értesítés „Leállítás” gombjával lehet – utána a következő megnyitásig nem indul újra.
        AppPrefs.serviceWanted = true

        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else startServiceIfWanted()
    }

    override fun onResume() {
        super.onResume()
        VolumeControl.refresh()
        BluetoothMonitor.onPermissionChanged()
        // Ha a felhasználó közben megnyitotta a megerősítő linket, itt frissül az állapot.
        val acc = CloudRepo.account.value
        if (acc != null && !acc.verified) {
            lifecycleScope.launch { runCatching { CloudRepo.reloadUser() } }
        }
    }

    private fun startServiceIfWanted() {
        if (AppPrefs.serviceWanted && !EqService.running.value) EqService.start(this)
    }
}

private data class TabItem(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("Hangzás", Icons.Default.Home),
    TabItem("Presetek", Icons.Default.Star),
    TabItem("Eszközök", Icons.Default.Phone),
    TabItem("Fiók", Icons.Default.Person),
    TabItem("Beállítás", Icons.Default.Settings),
)

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val s by AudioEngine.settings.collectAsState()
    val running by EqService.running.collectAsState()
    val dev by BluetoothMonitor.active.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BlueTune", fontWeight = FontWeight.Bold)
                        Text(
                            dev?.name ?: "Bluetooth hangtuning",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Switch(
                        checked = running && s.enabled,
                        onCheckedChange = { on ->
                            AudioEngine.setEnabled(on)
                            if (on && !running) {
                                AppPrefs.serviceWanted = true
                                EqService.start(ctx)
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> SoundScreen()
                1 -> PresetsScreen()
                2 -> DevicesScreen()
                3 -> CloudScreen()
                else -> SettingsScreen()
            }
        }
    }
}
