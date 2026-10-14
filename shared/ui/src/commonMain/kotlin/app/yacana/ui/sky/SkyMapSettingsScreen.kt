// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import app.yacana.domain.sky.InstalledCache
import app.yacana.domain.sky.NINA_CACHES
import app.yacana.domain.sky.NinaCache
import app.yacana.domain.sky.ONLINE_CACHE
import app.yacana.domain.sky.SkyCacheException
import app.yacana.domain.sky.SkyCacheFiles
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.sky.SkyLayer
import app.yacana.domain.solver.Arrival
import app.yacana.domain.solver.ArrivalStep
import app.yacana.ui.FilePicker
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatSize
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.sky_cache_cancel
import app.yacana.ui.resources.sky_cache_delete
import app.yacana.ui.resources.sky_cache_delete_download
import app.yacana.ui.resources.sky_cache_delete_hint
import app.yacana.ui.resources.sky_cache_delete_import
import app.yacana.ui.resources.sky_cache_delete_online
import app.yacana.ui.resources.sky_cache_download
import app.yacana.ui.resources.sky_cache_export
import app.yacana.ui.resources.sky_cache_export_failed
import app.yacana.ui.resources.sky_cache_export_hint
import app.yacana.ui.resources.sky_cache_exported
import app.yacana.ui.resources.sky_cache_failed
import app.yacana.ui.resources.sky_cache_full
import app.yacana.ui.resources.sky_cache_hint
import app.yacana.ui.resources.sky_cache_imported
import app.yacana.ui.resources.sky_cache_in_use
import app.yacana.ui.resources.sky_cache_installed
import app.yacana.ui.resources.sky_cache_narrowband
import app.yacana.ui.resources.sky_cache_no_space
import app.yacana.ui.resources.sky_cache_nsns_starless
import app.yacana.ui.resources.sky_cache_nsns_stars
import app.yacana.ui.resources.sky_cache_progress
import app.yacana.ui.resources.sky_cache_waiting_wifi
import app.yacana.ui.resources.sky_cache_whole_sky
import app.yacana.ui.resources.sky_caches
import app.yacana.ui.resources.sky_credits
import app.yacana.ui.resources.sky_fetch_online
import app.yacana.ui.resources.sky_fetch_online_hint
import app.yacana.ui.resources.sky_import
import app.yacana.ui.resources.sky_import_copying
import app.yacana.ui.resources.sky_import_explain
import app.yacana.ui.resources.sky_import_failed
import app.yacana.ui.resources.sky_import_folder
import app.yacana.ui.resources.sky_import_hint
import app.yacana.ui.resources.sky_import_zip
import app.yacana.ui.resources.sky_layer_dss
import app.yacana.ui.resources.sky_layer_narrowband
import app.yacana.ui.resources.sky_layer_unused_dss
import app.yacana.ui.resources.sky_layer_unused_narrowband
import app.yacana.ui.resources.sky_layers
import app.yacana.ui.resources.sky_online_detail
import app.yacana.ui.resources.sky_online_title
import app.yacana.ui.resources.sky_save_online
import app.yacana.ui.resources.sky_save_online_hint
import app.yacana.ui.resources.sky_wifi_only
import app.yacana.ui.resources.sky_wifi_only_hint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * "Settings · sky map": NINA's caches, each to download or in use; importing a ZIP or a
 * folder; downloads on WiFi only; and each layer's opacity. The online images
 * come with their task.
 */
@Composable
fun SkyMapSettingsScreen(store: SkyCacheStore, files: FilePicker) {
    val state by store.state.collectAsState()
    var importing by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf<InstalledCache?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var exportProblem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun export(cache: InstalledCache, title: String) {
        holding = null
        exported = null
        exportProblem = null
        files.newZip("${cache.name}.zip") { location ->
            scope.launch {
                try {
                    store.export(cache.name, location)
                    exported = title
                } catch (e: SkyCacheException) {
                    exportProblem = e.message
                }
            }
        }
    }
    fun import(location: String, folder: Boolean) {
        importing = false
        problem = null
        scope.launch {
            try {
                store.import(location, folder)
            } catch (e: SkyCacheException) {
                problem = e.message
            }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caches(state, store, onImport = { importing = true }, onHold = { holding = it })
        problem?.let { Error(stringResource(Res.string.sky_import_failed, it)) }
        exportProblem?.let { Error(stringResource(Res.string.sky_cache_export_failed, it)) }
        exported?.let { Muted(stringResource(Res.string.sky_cache_exported, it), Modifier.padding(horizontal = 20.dp)) }
        state.failed?.let { name ->
            val title = NINA_CACHES.firstOrNull { it.file == name }?.let { stringResource(it.title()) } ?: name
            Error(stringResource(Res.string.sky_cache_failed, title))
        }
        SectionCard {
            Row(Modifier.fillMaxWidth().clickable { store.setWifiOnly(!state.wifiOnly) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.sky_wifi_only), style = MaterialTheme.typography.bodyLarge)
                    Muted(stringResource(Res.string.sky_wifi_only_hint))
                }
                Switch(checked = state.wifiOnly, onCheckedChange = store::setWifiOnly)
            }
            Row(
                Modifier.fillMaxWidth().clickable { store.setFetchOnline(!state.fetchOnline) }.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.sky_fetch_online), style = MaterialTheme.typography.bodyLarge)
                    Muted(stringResource(Res.string.sky_fetch_online_hint))
                }
                Switch(checked = state.fetchOnline, onCheckedChange = store::setFetchOnline)
            }
            // Nothing to keep while the map asks for none.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.fetchOnline) { store.setSaveOnline(!state.saveOnline) }
                    .padding(top = 8.dp)
                    .alpha(if (state.fetchOnline) 1f else DISABLED_ALPHA),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.sky_save_online), style = MaterialTheme.typography.bodyLarge)
                    Muted(stringResource(Res.string.sky_save_online_hint))
                }
                Switch(checked = state.saveOnline, onCheckedChange = store::setSaveOnline, enabled = state.fetchOnline)
            }
        }
        Layers(state, store)
        Muted(stringResource(Res.string.sky_credits), Modifier.padding(horizontal = 20.dp))
    }
    if (importing) {
        BottomSheet(onDismiss = { importing = false }) {
            Text(stringResource(Res.string.sky_import), style = MaterialTheme.typography.titleMedium)
            Muted(stringResource(Res.string.sky_import_explain))
            OutlinedButton(onClick = { files.openFile { import(it, folder = false) } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.sky_import_zip))
            }
            OutlinedButton(onClick = { files.openFolder { import(it, folder = true) } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.sky_import_folder))
            }
        }
    }
    holding?.let { cache ->
        val title = cache.title()
        CacheSheet(
            cache,
            title,
            onDelete = { store.delete(cache.name) },
            onExport = if (cache.folder) ({ export(cache, title) }) else null,
            onDismiss = { holding = null },
        )
    }
}

/**
 * A cache held: a folder can be exported as a ZIP for N.I.N.A. on a PC, and any deleted, with what
 * it frees and the button to hold for it, as deleting a profile.
 */
@Composable
private fun CacheSheet(cache: InstalledCache, title: String, onDelete: () -> Unit, onExport: (() -> Unit)?, onDismiss: () -> Unit) {
    val nina = NINA_CACHES.any { it.file == cache.name }
    val size = formatSize(cache.bytes, decimalComma())
    val frees =
        when {
            nina -> Res.string.sky_cache_delete_download
            cache.name == ONLINE_CACHE -> Res.string.sky_cache_delete_online
            else -> Res.string.sky_cache_delete_import
        }
    BottomSheet(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        onExport?.let {
            Muted(stringResource(Res.string.sky_cache_export_hint))
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                Icon(YacanaIcons.FileExport, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                Text(stringResource(Res.string.sky_cache_export))
            }
        }
        Muted(stringResource(frees, size))
        HoldButton(
            text = stringResource(Res.string.sky_cache_delete),
            hint = stringResource(Res.string.sky_cache_delete_hint),
            onConfirm = {
                onDelete()
                onDismiss()
            },
            icon = YacanaIcons.Trash,
            danger = true,
        )
    }
}

/** NINA's three caches, and those imported, with what each is doing; and the import. */
@Composable
private fun Caches(state: SkyCacheFiles, store: SkyCacheStore, onImport: () -> Unit, onHold: (InstalledCache) -> Unit) {
    val comma = decimalComma()
    val others = state.installed.filter { cache -> NINA_CACHES.none { it.file == cache.name } }
    SectionCard {
        Muted(stringResource(Res.string.sky_caches))
        NINA_CACHES.forEach { cache ->
            val installed = state.installed.firstOrNull { it.name == cache.file }
            val size = formatSize(cache.bytes, comma)
            val reach = if (cache.layer == SkyLayer.Dss) Res.string.sky_cache_whole_sky else Res.string.sky_cache_narrowband
            CacheRow(
                title = stringResource(TITLES.getValue(cache.file)),
                detail = stringResource(reach, size),
                installed = installed,
                arrival = state.arriving[cache.file],
                problem = if (installed == null && state.freeBytes in 1 until cache.bytes) {
                    stringResource(Res.string.sky_cache_no_space, size, formatSize(state.freeBytes, comma))
                } else {
                    null
                },
                onDownload = { store.download(cache) },
                onCancel = { store.cancel(cache.file) },
                onUse = { store.use(cache.file, it) },
                onDelete = installed?.let { { onHold(it) } },
            )
        }
        others.forEach { cache ->
            val online = cache.name == ONLINE_CACHE
            CacheRow(
                title = cache.title(),
                detail = stringResource(
                    if (online) Res.string.sky_online_detail else Res.string.sky_cache_imported,
                    formatSize(cache.bytes, comma),
                ),
                installed = cache,
                arrival = null,
                problem = null,
                onDownload = {},
                onCancel = {},
                onUse = { store.use(cache.name, it) },
                onDelete = { onHold(cache) },
            )
        }
        state.arriving.filterKeys { name -> NINA_CACHES.none { it.file == name } }.forEach { (name, arrival) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(
                    stringResource(Res.string.sky_import_copying, name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Muted(percent(arrival))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onImport).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(YacanaIcons.FileImport, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(Res.string.sky_import), style = MaterialTheme.typography.bodyLarge)
                Muted(stringResource(Res.string.sky_import_hint))
            }
        }
        Muted(stringResource(Res.string.sky_cache_hint))
    }
}

/** One cache: its name and size, and on the right what it is doing, which a tap changes; held, it can be deleted. */
@Composable
@Suppress("LongParameterList")
private fun CacheRow(
    title: String,
    detail: String,
    installed: InstalledCache?,
    arrival: Arrival?,
    problem: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val action: (() -> Unit)? =
        when {
            arrival != null -> onCancel
            installed != null -> ({ onUse(!installed.inUse) })
            problem == null -> onDownload
            else -> null
        }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = action != null || onDelete != null, onLongClick = onDelete) { action?.invoke() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(YacanaIcons.Database, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Muted(problem ?: detail)
        }
        val status =
            when {
                arrival?.step == ArrivalStep.WaitingForWifi -> stringResource(Res.string.sky_cache_waiting_wifi)
                arrival != null -> "${percent(arrival)} · ${stringResource(Res.string.sky_cache_cancel)}"
                installed?.inUse == true -> stringResource(Res.string.sky_cache_in_use)
                installed != null -> stringResource(Res.string.sky_cache_installed)
                else -> stringResource(Res.string.sky_cache_download)
            }
        // As the design: what the map shows, and what a tap starts, in the accent.
        val accent = installed?.inUse == true || action == onDownload
        val color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        if (installed?.inUse == true) {
            Icon(YacanaIcons.CircleDot, contentDescription = null, tint = color, modifier = Modifier.padding(end = 4.dp).size(16.dp))
        }
        Text(status, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** A slider for each layer, from hidden to solid. */
@Composable
private fun Layers(state: SkyCacheFiles, store: SkyCacheStore) {
    // A layer without a cache in use draws nothing: its slider is off, and says what would show it.
    val shown = state.installed.filter { it.inUse }.map { it.layer }.toSet()
    SectionCard {
        Muted(stringResource(Res.string.sky_layers))
        SkyLayer.entries.forEach { layer ->
            val opacity = state.opacity[layer] ?: 1f
            val inUse = layer in shown
            val color = if (inUse) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (layer == SkyLayer.Dss) Res.string.sky_layer_dss else Res.string.sky_layer_narrowband),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                    modifier = Modifier.width(LABEL_WIDTH),
                )
                // In steps of 5 %, so that a layer can be put at 0 % and drawn not at all.
                Slider(
                    value = opacity,
                    onValueChange = { store.setOpacity(layer, it) },
                    enabled = inUse,
                    steps = OPACITY_STEPS,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(opacity * PERCENT).roundToInt()} %",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (!inUse) {
                val unused = if (layer == SkyLayer.Dss) Res.string.sky_layer_unused_dss else Res.string.sky_layer_unused_narrowband
                Muted(stringResource(unused))
            }
        }
    }
}

@Composable
private fun percent(arrival: Arrival): String = stringResource(
    Res.string.sky_cache_progress,
    if (arrival.totalBytes >
        0
    ) {
        (arrival.doneBytes * PERCENT / arrival.totalBytes).toInt()
    } else {
        0
    },
)

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Error(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

private val TITLES =
    mapOf(
        NINA_CACHES[0].file to Res.string.sky_cache_full,
        NINA_CACHES[1].file to Res.string.sky_cache_nsns_stars,
        NINA_CACHES[2].file to Res.string.sky_cache_nsns_starless,
    )

private val LABEL_WIDTH = 120.dp
private const val PERCENT = 100
private const val DISABLED_ALPHA = 0.4f

/** The marks between 0 and 100 %, every 5 %. */
private const val OPACITY_STEPS = 19

/** How a cache on the phone is called: NINA's by their names here, the photos online as such, others by their file. */
@Composable
internal fun InstalledCache.title(): String = when (name) {
    ONLINE_CACHE -> stringResource(Res.string.sky_online_title)
    else -> NINA_CACHES.firstOrNull { it.file == name }?.let { stringResource(it.title()) } ?: name
}

/** The name the map's chips and Settings give the caches in use. */
internal fun NinaCache.title() = TITLES.getValue(file)
