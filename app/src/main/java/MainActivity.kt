package com.phocorin.easyaudio

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.*
import androidx.media3.session.*
import com.google.android.gms.ads.*
import com.google.android.ump.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var adsReady by mutableStateOf(false)
    private var shortcutStart = false
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else Repository.error.value = R.string.permission_needed
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shortcutStart = savedInstanceState == null && intent.action == "com.phocorin.easyaudio.RECORD"
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future.addListener({ runCatching { controller = future.get() }.onFailure { Repository.error.value = R.string.play_error } }, ContextCompat.getMainExecutor(this))
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver { override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { MediaController.releaseFuture(future) } })
        val consent = UserMessagingPlatform.getConsentInformation(this)
        consent.requestConsentInfoUpdate(this, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { if (consent.canRequestAds()) MobileAds.initialize(this) { runOnUiThread { adsReady = true } } }
        }, { /* Offline or failed consent: recording works, ads stay disabled. */ })
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        setContentView(composeView)
        composeView.setContent { EasyAudio() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (intent.action == "com.phocorin.easyaudio.RECORD") requestRecording() }
    private fun requestRecording() {
        if (RecordingService.state.value.active || RecordingService.state.value.saving) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun beginRecording() {
        controller?.stop()
        runCatching { RecordingService.command(this, RecordingService.START) }.onFailure { Repository.error.value = R.string.record_error }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun share(recordings: List<Recording>) {
        runCatching {
            val uris = ArrayList(recordings.map { FileProvider.getUriForFile(this, "$packageName.files", Repository.file(it), it.name.replace('/', '_').replace(92.toChar(), '_') + "." + it.file.substringAfterLast('.')) })
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (recordings.all { it.file.endsWith(".wav") }) "audio/wav" else if (recordings.all { it.file.endsWith(".m4a") }) "audio/mp4" else "audio/*"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newUri(contentResolver, getString(R.string.recordings), uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }.onFailure { Repository.error.value = R.string.operation_error }
    }
    @Composable private fun EasyAudio() {
        val prefs = remember { getSharedPreferences("settings", 0) }
        var theme by remember { mutableIntStateOf(prefs.getInt("theme", 0)) }
        val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8DB8FF)) else lightColorScheme(primary = Color(0xFF1764D7), background = Color(0xFFF8FAFD))) {
            val library by Repository.library.collectAsStateWithLifecycle()
            val ready by Repository.ready.collectAsStateWithLifecycle()
            val capture by RecordingService.state.collectAsStateWithLifecycle()
            val error by Repository.error.collectAsStateWithLifecycle()
            var screen by rememberSaveable { mutableStateOf("home") }
            var detailId by rememberSaveable { mutableStateOf<String?>(null) }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var folder by rememberSaveable { mutableStateOf<String?>(null) }
            var query by rememberSaveable { mutableStateOf("") }
            var searching by rememberSaveable { mutableStateOf(false) }
            var sort by rememberSaveable { mutableIntStateOf(0) }
            var sortMenu by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf(setOf<String>()) }
            var deleteIds by remember { mutableStateOf(setOf<String>()) }
            var edit by remember { mutableStateOf<Recording?>(null) }
            var moving by remember { mutableStateOf(setOf<String>()) }
            var newFolder by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            fun operation(errorRes: Int = R.string.operation_error, block: () -> Unit) { scope.launch { runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { Repository.error.value = errorRes } } }
            LaunchedEffect(ready) { if (ready && shortcutStart) { shortcutStart = false; requestRecording() } }
            val current = library.recordings.firstOrNull { it.id == detailId }
            androidx.activity.compose.BackHandler(enabled = screen == "settings" && !capture.active && !capture.saving) { screen = "home" }
            androidx.activity.compose.BackHandler(enabled = screen == "home" && current == null && !capture.active && !capture.saving && (selected.isNotEmpty() || folder != null || searching)) { if (selected.isNotEmpty()) selected = emptySet() else if (folder != null) folder = null else { searching = false; query = "" } }
            Surface(Modifier.fillMaxSize()) {
                when {
                    capture.active || capture.saving -> RecordingScreen(capture) { RecordingService.command(this, it) }
                    screen == "settings" -> SettingsScreen(theme, { theme = it; prefs.edit().putInt("theme", it).apply() }, { screen = "home" })
                    current != null -> PlayerScreen(current, controller, { detailId = null }, { share(listOf(current)) }, { edit = current }, { deleteIds = setOf(current.id) })
                    else -> Scaffold(
                        topBar = { Column {
                            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { searching = !searching }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                                IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                            }
                            if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text(stringResource(R.string.search)) }, singleLine = true)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (folder != null) IconButton(onClick = { folder = null }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
                                Text(folder ?: stringResource(if (tab == 1) R.string.favorites else if (tab == 2) R.string.folders else R.string.recordings), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Box { IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, stringResource(R.string.sort)) }
                                    DropdownMenu(sortMenu, { sortMenu = false }) { listOf(R.string.sort_date, R.string.sort_name, R.string.sort_length).forEachIndexed { i, res -> DropdownMenuItem({ Text(stringResource(res)) }, { sort = i; sortMenu = false }) } }
                                }
                                if (tab == 2 && folder == null) IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.new_folder)) }
                            }
                            if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selected = emptySet() }) { Text(stringResource(R.string.selected_count, selected.size)); Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                                IconButton(onClick = { share(library.recordings.filter { it.id in selected }) }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                                IconButton(onClick = { moving = selected }) { Icon(Icons.Default.DriveFileMove, stringResource(R.string.move)) }
                                IconButton(onClick = { val ids = selected; operation { Repository.update { lib -> lib.copy(recordings = lib.recordings.map { if (it.id in ids) it.copy(favorite = true) else it }) } } }) { Icon(Icons.Default.Star, stringResource(R.string.favorite)) }
                                IconButton(onClick = { deleteIds = selected }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                            }
                        } },
                        bottomBar = { Column {
                            if (adsReady && ready) TestBanner()
                            NavigationBar { listOf(R.string.recordings to Icons.Default.GraphicEq, R.string.favorites to Icons.Default.Star, R.string.folders to Icons.Default.Folder).forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(tab == i, { tab = i; folder = null; selected = emptySet() }, icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
                            } }
                        } },
                        floatingActionButton = { FloatingActionButton(onClick = { if (ready) requestRecording() }, containerColor = Color(0xFFE53935), contentColor = Color.White, shape = CircleShape, modifier = Modifier.size(80.dp)) { Icon(Icons.Default.Mic, stringResource(R.string.record), Modifier.size(36.dp)) } }
                    ) { padding ->
                        val filtered = library.recordings.filter { r -> (tab != 1 || r.favorite) && (folder == null || r.folder == folder) && (r.name.contains(query, true) || r.note.contains(query, true)) }
                        val sorted = when (sort) { 1 -> filtered.sortedBy { it.name.lowercase() }; 2 -> filtered.sortedByDescending { it.duration }; else -> filtered.sortedByDescending { it.created } }
                        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!ready) item { Text(stringResource(R.string.loading), Modifier.padding(24.dp)) }
                            if (tab == 2 && folder == null) {
                                items(library.folders, key = { "folder-$it" }) { f ->
                                    ElevatedCard(onClick = { folder = f }) { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(16.dp)); Text(f, style = MaterialTheme.typography.titleMedium) } }
                                }
                            }
                            if (ready && sorted.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.GraphicEq, null, Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(16.dp)); Text(stringResource(if (tab == 1) R.string.empty_favorites else R.string.empty)) } }
                            items(sorted, key = { it.id }) { r -> RecordingCard(r, r.id in selected, selected.isNotEmpty(), {
                                if (selected.isNotEmpty()) selected = if (r.id in selected) selected - r.id else selected + r.id
                                else { detailId = r.id; controller?.let { play(it, r) } }
                            }, { selected = selected + r.id }, { operation { Repository.change(r.copy(favorite = !r.favorite)) } }, { edit = r }, { share(listOf(r)) }, { moving = setOf(r.id) }, { deleteIds = setOf(r.id) }) }
                        }
                    }
                }
                if (error != null) AlertDialog(onDismissRequest = { Repository.error.value = null }, title = { Text(stringResource(R.string.error)) }, text = { Text(stringResource(error!!)) }, confirmButton = { TextButton(onClick = { Repository.error.value = null }) { Text(stringResource(R.string.close)) } })
                if (deleteIds.isNotEmpty()) AlertDialog(onDismissRequest = { deleteIds = emptySet() }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.delete_confirm)) }, confirmButton = { TextButton(onClick = {
                    val ids = deleteIds; deleteIds = emptySet(); controller?.stop(); detailId = null; selected = selected - ids; operation(R.string.delete_error) { Repository.delete(ids) }
                }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { deleteIds = emptySet() }) { Text(stringResource(R.string.cancel)) } })
                edit?.let { r -> EditDialog(r, { edit = null }) { name, note -> operation { Repository.change(r.copy(name = name, note = note)) }; edit = null } }
                if (newFolder) NameDialog({ newFolder = false }) { name ->
                    if (name in library.folders) false else { operation { Repository.update { it.copy(folders = it.folders + name) } }; newFolder = false; true }
                }
                if (moving.isNotEmpty()) AlertDialog(onDismissRequest = { moving = emptySet() }, title = { Text(stringResource(R.string.move)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    (listOf("") + library.folders).forEach { f -> TextButton(onClick = { val ids = moving; moving = emptySet(); operation { Repository.update { lib -> lib.copy(recordings = lib.recordings.map { if (it.id in ids) it.copy(folder = f) else it }) } } }) { Text(if (f.isEmpty()) stringResource(R.string.all) else f) } }
                } }, confirmButton = { TextButton(onClick = { moving = emptySet() }) { Text(stringResource(R.string.cancel)) } })
            }
        }
    }
    @Composable private fun TestBanner() {
        val ad = remember { AdView(this).apply { adUnitId = "ca-app-pub-3940256099942544/6300978111"; setAdSize(AdSize.BANNER) } }
        DisposableEffect(ad) { ad.loadAd(AdRequest.Builder().build()); onDispose { ad.destroy() } }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AndroidView(factory = { ad }, modifier = Modifier.size(320.dp, 50.dp)) }
    }
    @Composable private fun SettingsScreen(theme: Int, setTheme: (Int) -> Unit, back: () -> Unit) {
        val prefs = remember { getSharedPreferences("settings", 0) }
        var quality by remember { mutableIntStateOf(prefs.getInt("quality", 0)) }
        var vibration by remember { mutableStateOf(prefs.getBoolean("vibrate", true)) }
        var privacy by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageTitle(R.string.settings, back)
            Text(stringResource(R.string.quality), style = MaterialTheme.typography.titleMedium)
            listOf(R.string.standard, R.string.high, R.string.lossless).forEachIndexed { i, r -> Row(Modifier.fillMaxWidth().clickable { quality = i; prefs.edit().putInt("quality", i).apply() }, verticalAlignment = Alignment.CenterVertically) { RadioButton(quality == i, { quality = i; prefs.edit().putInt("quality", i).apply() }); Text(stringResource(r)) } }
            Text(stringResource(R.string.quality_help), style = MaterialTheme.typography.bodySmall)
            ToggleRow(R.string.vibration, vibration) { vibration = it; prefs.edit().putBoolean("vibrate", it).apply() }
            HorizontalDivider()
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            listOf("" to R.string.system_language, "en" to R.string.english, "ja" to R.string.japanese).forEach { (code, res) -> Row(Modifier.fillMaxWidth().clickable { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code)) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(current == code, { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code)) }); Text(stringResource(res)) } }
            HorizontalDivider(); Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
            listOf(R.string.system_theme, R.string.light, R.string.dark).forEachIndexed { i, res -> Row(Modifier.fillMaxWidth().clickable { setTheme(i) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(theme == i, { setTheme(i) }); Text(stringResource(res)) } }
            OutlinedButton(onClick = {
                val manager = getSystemService(ShortcutManager::class.java)
                if (manager.isRequestPinShortcutSupported) manager.requestPinShortcut(ShortcutInfo.Builder(this@MainActivity, "record-audio").setShortLabel(getString(R.string.shortcut_name)).setIcon(Icon.createWithResource(this@MainActivity, R.drawable.ic_mic)).setIntent(Intent(this@MainActivity, MainActivity::class.java).setAction("com.phocorin.easyaudio.RECORD")).build(), null)
                else Repository.error.value = R.string.shortcut_unavailable
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shortcut)) }
            TextButton(onClick = { privacy = true }) { Text(stringResource(R.string.privacy)) }
            TextButton(onClick = {
                val consent = UserMessagingPlatform.getConsentInformation(this@MainActivity)
                if (consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) UserMessagingPlatform.showPrivacyOptionsForm(this@MainActivity) { if (it != null) Repository.error.value = R.string.ad_unavailable }
                else Repository.error.value = R.string.ad_unavailable
            }) { Text(stringResource(R.string.ad_privacy)) }
        }
        if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text(stringResource(R.string.privacy)) }, text = { Text(stringResource(R.string.privacy_text)) }, confirmButton = { TextButton(onClick = { privacy = false }) { Text(stringResource(R.string.close)) } })
    }
}

private fun play(player: Player, r: Recording, start: Long = 0, end: Long? = null) {
    val builder = MediaItem.Builder().setMediaId(r.id).setUri(android.net.Uri.fromFile(Repository.file(r)))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(r.name).setArtist("Easy Audio").build())
    if (end != null) builder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build())
    player.setMediaItem(builder.build()); player.prepare(); if (end == null) player.seekTo(start); player.play()
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RecordingCard(r: Recording, selected: Boolean, selection: Boolean, open: () -> Unit, select: () -> Unit, favorite: () -> Unit, edit: () -> Unit, share: () -> Unit, move: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = select), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selection) Checkbox(selected, { open() }) else Icon(Icons.Default.GraphicEq, null, Modifier.padding(8.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.created)), style = MaterialTheme.typography.bodySmall)
                Text(Pcm.formatTime(r.duration) + " · " + r.file.substringAfterLast('.').uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (r.note.isNotEmpty()) Text(r.note, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = favorite) { Icon(if (r.favorite) Icons.Default.Star else Icons.Default.StarBorder, stringResource(if (r.favorite) R.string.unfavorite else R.string.favorite), tint = if (r.favorite) Color(0xFFDA9800) else MaterialTheme.colorScheme.onSurfaceVariant) }
            Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) { listOf(R.string.edit_details to edit, R.string.share to share, R.string.move to move, R.string.select to select, R.string.delete to delete).forEach { (res, action) -> DropdownMenuItem({ Text(stringResource(res)) }, { menu = false; action() }) } }
            }
        }
    }
}
@Composable private fun EditDialog(r: Recording, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var name by rememberSaveable(r.id) { mutableStateOf(r.name) }; var note by rememberSaveable(r.id) { mutableStateOf(r.note) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.edit_details)) }, text = { Column {
        OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
        OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text(stringResource(R.string.note)) }, maxLines = 5)
    } }, confirmButton = { TextButton(onClick = { save(name.trim(), note.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
@Composable private fun NameDialog(dismiss: () -> Unit, save: (String) -> Boolean) {
    var name by rememberSaveable { mutableStateOf("") }; var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.new_folder)) }, text = { Column {
        OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text(stringResource(R.string.folder_name)) }, isError = invalid, singleLine = true)
        if (invalid) Text(stringResource(R.string.invalid_name), color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = { invalid = name.isBlank() || !save(name.trim()) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
@Composable private fun PageTitle(title: Int, back: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }; Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
}
@Composable private fun ToggleRow(label: Int, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(label), Modifier.weight(1f)); Switch(value, change) }
}
@Composable private fun Waveform(peaks: List<Float>, progress: Float = 0f) {
    val color = MaterialTheme.colorScheme.primary; val muted = MaterialTheme.colorScheme.outlineVariant; val description = stringResource(R.string.waveform)
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        val bars = minOf(80, peaks.size)
        if (bars == 0) drawLine(muted, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 2f)
        else for (i in 0 until bars) { val peak = peaks[i * peaks.size / bars].coerceIn(0f, 1f); val height = maxOf(3f, peak * size.height); val x = (i + .5f) * size.width / bars
            drawLine(if (i.toFloat() / bars <= progress) color else muted, androidx.compose.ui.geometry.Offset(x, (size.height - height) / 2), androidx.compose.ui.geometry.Offset(x, (size.height + height) / 2), maxOf(2f, size.width / bars * .55f), androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
@Composable private fun RecordingScreen(s: CaptureState, command: (String) -> Unit) {
    androidx.activity.compose.BackHandler { /* Keep recording visible; Home can background the app. */ }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(if (s.saving) R.string.saving else if (s.paused) R.string.paused else R.string.recording), style = MaterialTheme.typography.headlineMedium)
        Text(Pcm.formatTime(s.elapsed), fontSize = 44.sp, fontWeight = FontWeight.Light)
        Waveform(s.peaks, 1f)
        Text(stringResource(R.string.level), style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress = { s.level }, modifier = Modifier.fillMaxWidth())
        if (s.interrupted) Text(stringResource(R.string.interrupted), color = MaterialTheme.colorScheme.error)
        if (s.saving) CircularProgressIndicator() else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { command(RecordingService.PAUSE) }, modifier = Modifier.size(64.dp)) { Icon(if (s.paused) Icons.Default.PlayArrow else Icons.Default.Pause, stringResource(if (s.paused) R.string.resume else R.string.pause)) }
                FilledIconButton(onClick = { command(RecordingService.STOP) }, modifier = Modifier.size(88.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935))) { Icon(Icons.Default.Stop, stringResource(R.string.stop_save), Modifier.size(40.dp)) }
            }
            OutlinedButton(onClick = { command(RecordingService.MARK) }) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.bookmark)) }
            Text(stringResource(R.string.bookmarks) + ": " + s.marks.size)
            Text(stringResource(R.string.notification_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun PlayerScreen(r: Recording, player: MediaController?, back: () -> Unit, share: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var position by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var repeat by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(prefs.getBoolean("skip", false)) }
    var trim by rememberSaveable(r.id) { mutableStateOf(false) }
    var start by rememberSaveable(r.id) { mutableFloatStateOf(0f) }
    var end by rememberSaveable(r.id) { mutableFloatStateOf(r.duration / 1000f) }
    var keep by rememberSaveable { mutableStateOf(true) }
    val busy by Repository.editing.collectAsStateWithLifecycle()
    val completed by Repository.editCompleted.collectAsStateWithLifecycle()
    var previewing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(completed) { if (completed == r.id) { Repository.editCompleted.value = null; back() } }
    androidx.activity.compose.BackHandler { if (!busy) { if (trim) { trim = false; player?.stop() } else back() } }
    LaunchedEffect(r.id, player) {
        if (player != null) {
            if (player.currentMediaItem?.mediaId != r.id) play(player, r)
            while (true) { position = player.currentPosition; playing = player.isPlaying; speed = player.playbackParameters.speed; repeat = player.repeatMode == Player.REPEAT_MODE_ONE; delay(150) }
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onPlayerError(error: PlaybackException) { Repository.error.value = R.string.play_error } }
        player?.addListener(listener); onDispose { player?.removeListener(listener) }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!busy) { if (trim) { trim = false; player?.stop() } else back() } }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(if (trim) R.string.trim else R.string.recordings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (!trim) { IconButton(onClick = edit) { Icon(Icons.Default.Edit, stringResource(R.string.edit_details)) }; IconButton(onClick = share) { Icon(Icons.Default.Share, stringResource(R.string.share)) } }
        }
        Text(r.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
        Waveform(r.peaks, position.toFloat() / r.duration.coerceAtLeast(1))
        if (trim) {
            Text(stringResource(R.string.trim_help))
            Text(Pcm.formatTime((start * 1000).toLong()) + " – " + Pcm.formatTime((end * 1000).toLong()))
            val description = stringResource(R.string.trim_range)
            RangeSlider(start..end, { start = it.start; end = it.endInclusive; player?.pause(); previewing = false }, enabled = !busy, valueRange = 0f..(r.duration / 1000f).coerceAtLeast(.001f), modifier = Modifier.semantics { contentDescription = description })
            OutlinedButton(onClick = { player?.let { it.repeatMode = Player.REPEAT_MODE_OFF; play(it, r, (start * 1000).toLong(), (end * 1000).toLong()); previewing = true } }, enabled = !busy && end - start >= .1f && player != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.preview)) }
            if (previewing) Text(Pcm.formatTime(position))
            ToggleRow(R.string.keep_original, keep) { if (!busy) keep = it }
            val saveTrim: () -> Unit = {
                player?.stop()
                Repository.trim(r, (start * 1000).toLong(), (end * 1000).toLong(), !keep)
            }
            Button(onClick = { if (keep) saveTrim() else confirmRemove = true }, enabled = !busy && end - start >= .1f, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (busy) R.string.trim_busy else R.string.trim_save)) }
            if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.delete_confirm)) }, confirmButton = { TextButton(onClick = { confirmRemove = false; saveTrim() }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Text(Pcm.formatTime(position) + " / " + Pcm.formatTime(r.duration), style = MaterialTheme.typography.titleMedium)
            val label = stringResource(R.string.position)
            Slider(position.coerceIn(0, r.duration).toFloat(), { player?.seekTo(it.toLong()); position = it.toLong() }, valueRange = 0f..r.duration.coerceAtLeast(1).toFloat(), modifier = Modifier.semantics { contentDescription = label })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player?.seekBack() }, enabled = player != null) { Icon(Icons.Default.Replay10, stringResource(R.string.back_10), Modifier.size(32.dp)) }
                FilledIconButton(onClick = { player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED || it.playbackState == Player.STATE_IDLE) play(it, r) else it.play() } } }, enabled = player != null, modifier = Modifier.size(80.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.pause_playback else R.string.play), Modifier.size(40.dp)) }
                IconButton(onClick = { player?.seekForward() }, enabled = player != null) { Icon(Icons.Default.Forward10, stringResource(R.string.forward_10), Modifier.size(32.dp)) }
                IconButton(onClick = { player?.stop(); player?.seekTo(0) }) { Icon(Icons.Default.Stop, stringResource(R.string.stop_playback)) }
            }
            Text(stringResource(R.string.speed) + ": " + "%.2f×".format(speed))
            Slider(speed, { speed = it; player?.setPlaybackSpeed(it) }, valueRange = .5f..2f, steps = 5)
            ToggleRow(R.string.skip_silence, skip) { skip = it; prefs.edit().putBoolean("skip", it).apply() }
            ToggleRow(R.string.repeat, repeat) { repeat = it; player?.repeatMode = if (it) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { player?.pause(); trim = true; start = 0f; end = r.duration / 1000f }, enabled = r.duration >= 200) { Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.trim)) }
                TextButton(onClick = delete) { Icon(Icons.Default.DeleteOutline, null); Text(stringResource(R.string.delete)) }
            }
            if (r.marks.isNotEmpty()) {
                Text(stringResource(R.string.bookmarks), style = MaterialTheme.typography.titleMedium)
                r.marks.forEach { time -> OutlinedButton(onClick = { player?.let { play(it, r, time) } }) { Icon(Icons.Default.Bookmark, null); Text(Pcm.formatTime(time)) } }
            }
        }
    }
}






