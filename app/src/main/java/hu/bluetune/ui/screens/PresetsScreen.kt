package hu.bluetune.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.bluetune.audio.AudioEngine
import hu.bluetune.cloud.CloudRepo
import hu.bluetune.data.PresetStore
import hu.bluetune.model.BuiltInPresets
import hu.bluetune.model.Preset
import hu.bluetune.ui.components.EqCurve
import hu.bluetune.ui.components.toast

@Composable
fun PresetsScreen() {
    val custom by PresetStore.custom.collectAsState()
    val s by AudioEngine.settings.collectAsState()
    val account by CloudRepo.account.collectAsState()
    val ctx = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf<Preset?>(null) }
    var deleting by remember { mutableStateOf<Preset?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (custom.isEmpty()) {
                            toast(ctx, "Nincs mit exportálni – előbb ments egy presetet.")
                        } else {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "BlueTune presetek")
                                .putExtra(Intent.EXTRA_TEXT, PresetStore.exportJson())
                            ctx.startActivity(Intent.createChooser(send, "Presetek megosztása"))
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Exportálás") }
                OutlinedButton(onClick = { showImport = true }, modifier = Modifier.weight(1f)) { Text("Importálás") }
            }
        }

        item { SectionTitle("Saját presetek (${custom.size})") }
        if (custom.isEmpty()) {
            item {
                Text(
                    "Még nincs saját preset. A Hangzás fülön állítsd be, majd „Mentés presetként”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(custom, key = { "c_" + it.name }) { p ->
            PresetRow(
                preset = p,
                active = s.presetName == p.name,
                onApply = { AudioEngine.applyPreset(p) },
                onShare = if (account?.verified == true) ({ publishing = p }) else null,
                onDelete = { deleting = p },
            )
        }

        item { SectionTitle("Beépített presetek") }
        items(BuiltInPresets.all, key = { "b_" + it.name }) { p ->
            PresetRow(
                preset = p,
                active = s.presetName == p.name,
                onApply = { AudioEngine.applyPreset(p) },
                onShare = null,
                onDelete = null,
            )
        }
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { text ->
                val n = PresetStore.importJson(text)
                toast(ctx, if (n > 0) "$n preset importálva" else "Nem találtam érvényes presetet")
                if (n > 0) showImport = false
            },
        )
    }

    publishing?.let { p ->
        PublishDialog(
            initialName = p.name,
            settings = p.settings,
            onDismiss = { publishing = null },
            onResult = { msg ->
                toast(ctx, msg)
                publishing = null
            },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Törlöd?") },
            text = { Text("„${p.name}” véglegesen törlődik.") },
            confirmButton = {
                TextButton(onClick = {
                    PresetStore.delete(p.name)
                    deleting = null
                }) { Text("Törlés") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Mégse") } },
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun PresetRow(
    preset: Preset,
    active: Boolean,
    onApply: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onApply),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (active) "✓ " else "") + preset.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    preset.settings.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EqCurve(preset.settings, Modifier.width(84.dp).height(34.dp), showGrid = false)
            if (onShare != null) {
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Közzététel") }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Törlés") }
            }
        }
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presetek importálása") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Illeszd be az exportált JSON szöveget:", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    placeholder = { Text("[{\"name\": …}]") },
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onImport(text) }) { Text("Importálás") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}
