@file:OptIn(ExperimentalMaterial3Api::class)

package hu.bluetune.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import hu.bluetune.BuildConfig
import hu.bluetune.audio.AudioEngine
import hu.bluetune.cloud.CloudRepo
import hu.bluetune.data.AppPrefs
import hu.bluetune.data.PresetStore
import hu.bluetune.model.EqSettings
import hu.bluetune.ui.components.EqCurve
import hu.bluetune.ui.components.SectionCard
import hu.bluetune.ui.components.SwitchRow
import hu.bluetune.ui.components.WarningText
import hu.bluetune.ui.components.safeStart
import kotlinx.coroutines.launch

private fun messageOf(t: Throwable): String = "Hiba: " + (t.message ?: t.javaClass.simpleName)

/** Fiók fül: bejelentkezés / regisztráció, hírlevél, mentés, közösségi presetek. */
@Composable
fun CloudScreen() {
    if (!CloudRepo.available) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionCard("A fiókfunkciók nincsenek beállítva") {
                Text(
                    "Ez a build Firebase nélkül készült. Az EQ és minden helyi funkció működik, " +
                        "a fiók, a felhős mentés és a közösségi presetek a Firebase beállítása után érhetők el " +
                        "(lásd: UTMUTATO.md a projektben).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    val account by CloudRepo.account.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val acc = account
        if (acc == null) {
            AuthCard()
        } else {
            AccountCard(acc)
            BackupCard(acc)
            CommunityCard(acc)
        }
        LegalLinks()
        Spacer(Modifier.height(24.dp))
    }
}

// =====================================================================  belépés / regisztráció

@Composable
private fun AuthCard() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 = belépés, 1 = regisztráció
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var newsletter by remember { mutableStateOf(false) } // GDPR: alapból NINCS bepipálva
    var acceptPrivacy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val emailOk = android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val canSubmit = !busy && emailOk && pw.isNotEmpty() &&
        (mode == 0 || (pw.length >= CloudRepo.MIN_PASSWORD && pw == pw2 && acceptPrivacy))

    SectionCard(if (mode == 0) "Bejelentkezés" else "Regisztráció") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Bejelentkezés", "Regisztráció").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = mode == i,
                    onClick = { mode = i; msg = null },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        Text(
            "Fiókkal mentheted a beállításaidat a felhőbe, presetet oszthatsz meg, és értesítést kaphatsz az új verziókról. " +
                "Az EQ fiók nélkül is teljesen működik.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.take(120) },
            label = { Text("Email cím") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it.take(128) },
            label = { Text("Jelszó") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = if (mode == 1) ({ Text("Legalább ${CloudRepo.MIN_PASSWORD} karakter") }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (mode == 1) {
            OutlinedTextField(
                value = pw2,
                onValueChange = { pw2 = it.take(128) },
                label = { Text("Jelszó még egyszer") },
                singleLine = true,
                isError = pw2.isNotEmpty() && pw2 != pw,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            CheckRow(
                checked = acceptPrivacy,
                onChange = { acceptPrivacy = it },
                text = "Elolvastam és elfogadom az adatvédelmi tájékoztatót (kötelező)",
                linkLabel = "Megnyitás",
                onLink = { safeStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL))) },
            )
            CheckRow(
                checked = newsletter,
                onChange = { newsletter = it },
                text = "Kérek emailt az új verziókról és funkciókról (bármikor leiratkozhatok)",
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        msg?.let { StatusText(it) }
        Button(
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    busy = true; msg = null
                    try {
                        if (mode == 0) {
                            CloudRepo.login(email, pw)
                        } else {
                            CloudRepo.register(email, pw, newsletter)
                        }
                    } catch (t: Throwable) {
                        msg = messageOf(t)
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (mode == 0) "Bejelentkezés" else "Fiók létrehozása") }
        if (mode == 0) {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; msg = null
                    try {
                        CloudRepo.sendPasswordReset(email)
                        msg = "Ha van fiók ezzel a címmel, elküldtük a jelszó-visszaállító linket."
                    } catch (t: Throwable) {
                        msg = messageOf(t)
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Elfelejtett jelszó") }
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    text: String,
    linkLabel: String? = null,
    onLink: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (linkLabel != null && onLink != null) TextButton(onClick = onLink) { Text(linkLabel) }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (text.startsWith("Hiba")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

// =====================================================================  fiók

@Composable
private fun AccountCard(acc: CloudRepo.Account) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var subscribed by remember { mutableStateOf<Boolean?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    fun task(block: suspend () -> Unit) {
        scope.launch {
            busy = true; msg = null
            try { block() } catch (t: Throwable) { msg = messageOf(t) } finally { busy = false }
        }
    }

    LaunchedEffect(acc.uid) {
        runCatching { subscribed = CloudRepo.isSubscribed() }
        if (!acc.verified) runCatching { CloudRepo.reloadUser() }
    }

    SectionCard("Fiók") {
        Text(acc.email, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (acc.verified) {
            Text("✓ Email cím megerősítve", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        } else {
            WarningText("Az email címed még nincs megerősítve. Nézd meg a postafiókod (a spam mappát is), és kattints a linkre.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    task { CloudRepo.resendVerification(); msg = "Megerősítő email elküldve." }
                }) { Text("Újraküldés") }
                Button(enabled = !busy, onClick = {
                    task { msg = if (CloudRepo.reloadUser()) "Megerősítve ✓" else "Még nincs megerősítve." }
                }) { Text("Megerősítettem") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        msg?.let { StatusText(it) }

        SwitchRow(
            title = "Frissítés-értesítők emailben",
            subtitle = if (acc.verified) {
                "Új verziók és funkciók – ritkán, bármikor leiratkozhatsz"
            } else {
                "Megerősítés után kezdünk küldeni"
            },
            checked = subscribed == true,
            enabled = !busy && subscribed != null,
            onChange = { on ->
                task {
                    CloudRepo.setNewsletter(on)
                    subscribed = on
                    msg = if (on) "Feliratkoztál ✓" else "Leiratkoztál."
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showPassword = true }, modifier = Modifier.weight(1f)) { Text("Jelszócsere") }
            OutlinedButton(onClick = { CloudRepo.signOut() }, modifier = Modifier.weight(1f)) { Text("Kijelentkezés") }
        }
        TextButton(onClick = { showDelete = true }) {
            Text("Fiók végleges törlése", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showDelete) DeleteAccountDialog(onDismiss = { showDelete = false })
    if (showPassword) ChangePasswordDialog(onDismiss = { showPassword = false }, onDone = { msg = it; showPassword = false })
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Fiók törlése") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Véglegesen törlődik: a fiókod, a felhős mentésed, a közzétett presetjeid és a hírlevél-feliratkozásod. " +
                        "A telefonon lévő beállítások megmaradnak. Ez nem vonható vissza.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Jelszó a megerősítéshez") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pw.isNotEmpty(), onClick = {
                scope.launch {
                    busy = true
                    try {
                        CloudRepo.deleteAccount(pw)
                        onDismiss()
                    } catch (t: Throwable) {
                        error = messageOf(t)
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Végleges törlés", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Mégse") } },
    )
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Jelszócsere") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    current, { current = it }, label = { Text("Jelenlegi jelszó") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    next, { next = it }, label = { Text("Új jelszó (min. ${CloudRepo.MIN_PASSWORD})") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && current.isNotEmpty() && next.length >= CloudRepo.MIN_PASSWORD, onClick = {
                scope.launch {
                    busy = true
                    try {
                        CloudRepo.changePassword(current, next)
                        onDone("Jelszó megváltoztatva ✓")
                    } catch (t: Throwable) {
                        error = messageOf(t)
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Mentés") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Mégse") } },
    )
}

// =====================================================================  mentés

@Composable
private fun BackupCard(acc: CloudRepo.Account) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var lastBackup by remember { mutableStateOf<String?>(null) }

    fun task(block: suspend () -> Unit) {
        scope.launch {
            busy = true; msg = null
            try { block() } catch (t: Throwable) { msg = messageOf(t) } finally { busy = false }
        }
    }

    LaunchedEffect(acc.uid) { runCatching { lastBackup = CloudRepo.lastBackupInfo() } }

    SectionCard("Felhős mentés") {
        Text(
            "Mentésre kerül: ${PresetStore.custom.value.size} saját preset, az eszközprofilok és az aktuális hangzás. " +
                "Új telefonon belépés után visszaállíthatod.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Utolsó mentés: ${lastBackup ?: "nincs"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        msg?.let { StatusText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                task {
                    CloudRepo.backup()
                    lastBackup = CloudRepo.lastBackupInfo()
                    msg = "Mentve a felhőbe ✓"
                }
            }) { Text("Mentés") }
            OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                task { msg = CloudRepo.restore() }
            }) { Text("Visszaállítás") }
        }
    }
}

// =====================================================================  közösség

@Composable
private fun CommunityCard(acc: CloudRepo.Account) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var community by remember { mutableStateOf<List<CloudRepo.CommunityPreset>>(emptyList()) }
    var liked by remember { mutableStateOf(AppPrefs.likedCommunityIds) }
    var showPublish by remember { mutableStateOf(false) }

    fun task(block: suspend () -> Unit) {
        scope.launch {
            busy = true; msg = null
            try { block() } catch (t: Throwable) { msg = messageOf(t) } finally { busy = false }
        }
    }

    LaunchedEffect(acc.uid) { task { community = CloudRepo.community() } }

    SectionCard(
        "Közösségi presetek",
        trailing = { TextButton(enabled = !busy, onClick = { task { community = CloudRepo.community() } }) { Text("Frissítés") } },
    ) {
        Button(enabled = !busy && acc.verified, onClick = { showPublish = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Jelenlegi hangzásom közzététele")
        }
        if (!acc.verified) {
            Text(
                "Közzétételhez erősítsd meg az email címed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        msg?.let { StatusText(it) }
        if (community.isEmpty() && !busy) {
            Text(
                "Még nincs közzétett preset – légy te az első!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        community.forEach { cp ->
            CommunityRow(
                cp = cp,
                liked = cp.id in liked,
                own = cp.ownerUid == acc.uid,
                onApply = {
                    AudioEngine.update { cur -> cur.withSound(cp.settings, cp.name) }
                    msg = "Alkalmazva: ${cp.name}"
                },
                onSave = {
                    PresetStore.save(cp.name, cp.settings)
                    msg = "Elmentve a saját presetek közé: ${cp.name}"
                },
                onLike = {
                    task {
                        CloudRepo.like(cp.id)
                        liked = AppPrefs.likedCommunityIds
                        community = community.map { if (it.id == cp.id) it.copy(likes = it.likes + 1) else it }
                    }
                },
                onDelete = {
                    task {
                        CloudRepo.delete(cp.id)
                        community = community.filterNot { it.id == cp.id }
                        msg = "Törölve"
                    }
                },
            )
        }
    }

    if (showPublish) {
        PublishDialog(
            initialName = AudioEngine.settings.value.presetName ?: "",
            settings = AudioEngine.settings.value,
            onDismiss = { showPublish = false },
            onResult = { m ->
                msg = m
                showPublish = false
                task { community = CloudRepo.community() }
            },
        )
    }
}

@Composable
private fun CommunityRow(
    cp: CloudRepo.CommunityPreset,
    liked: Boolean,
    own: Boolean,
    onApply: () -> Unit,
    onSave: () -> Unit,
    onLike: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cp.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${cp.author} · ♥ ${cp.likes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EqCurve(cp.settings, Modifier.width(90.dp).height(36.dp), showGrid = false)
            }
            if (cp.description.isNotBlank()) Text(cp.description, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onApply) { Text("Alkalmaz") }
                TextButton(onClick = onSave) { Text("Mentés") }
                TextButton(enabled = !liked, onClick = onLike) { Text(if (liked) "♥ Kedvelve" else "♡ Tetszik") }
                if (own) TextButton(onClick = onDelete) { Text("Törlés") }
            }
        }
    }
}

@Composable
private fun LegalLinks() {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { safeStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL))) }) {
            Text("Adatvédelem")
        }
        TextButton(onClick = { safeStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DELETE_ACCOUNT_URL))) }) {
            Text("Fióktörlés weben")
        }
    }
}

/** Közzététel a közösségi listába (a Presetek fülről is hívható). */
@Composable
fun PublishDialog(
    initialName: String,
    settings: EqSettings,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var author by remember { mutableStateOf(AppPrefs.authorName) }
    var desc by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Közzététel a közösségnek") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EqCurve(settings, Modifier.fillMaxWidth().height(70.dp))
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Preset neve") }, singleLine = true)
                OutlinedTextField(author, { author = it.take(30) }, label = { Text("Szerző (becenév)") }, singleLine = true)
                OutlinedTextField(desc, { desc = it.take(200) }, label = { Text("Leírás (pl. milyen fejhallgatóhoz)") })
                Text(
                    "A becenév és a preset mindenki számára látható lesz. Az email címed nem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    try {
                        AppPrefs.authorName = author
                        CloudRepo.publish(name, author, desc, settings)
                        onResult("Közzétéve: ${name.trim()} ✓")
                    } catch (t: Throwable) {
                        error = messageOf(t)
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Közzététel") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Mégse") } },
    )
}
