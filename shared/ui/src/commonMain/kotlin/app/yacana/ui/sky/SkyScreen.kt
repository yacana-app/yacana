// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.sky.LookUp
import app.yacana.domain.sky.ObjectSearch
import app.yacana.domain.sky.SkyObject
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.SearchField
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.obj_magnitude
import app.yacana.ui.resources.sky_loading
import app.yacana.ui.resources.sky_search_help
import app.yacana.ui.resources.sky_search_hint
import app.yacana.ui.resources.sky_simbad
import app.yacana.ui.resources.sky_simbad_failed
import app.yacana.ui.resources.sky_simbad_hint
import app.yacana.ui.resources.sky_simbad_many
import app.yacana.ui.resources.sky_simbad_not_found
import app.yacana.ui.resources.sky_simbad_searching
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Sky section's list, as "Sky · Sky Atlas": the search box and the objects it finds in the
 * app's catalogue without internet, and below them a search in SIMBAD for what is not
 * there; with nothing to search, the [atlas] of tonight, which is told whether it shows, for its
 * filters' sheet. A tap opens the object's page.
 */
@Composable
internal fun SkySearch(objects: ObjectSearch, open: (Screen) -> Unit, atlas: @Composable (shown: Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val catalogue by objects.objects.collectAsState()
    val results = remember(query, catalogue) { catalogue?.search(query).orEmpty() }
    Column(Modifier.padding(top = 8.dp)) {
        SearchField(query, stringResource(Res.string.sky_search_hint)) { query = it }
        when {
            catalogue == null -> Hint(stringResource(Res.string.sky_loading))

            query.isBlank() -> atlas(true)

            else -> {
                atlas(false)
                Results(query, results, objects, open)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** What a search on SIMBAD is doing. */
private sealed interface Online {
    data object Idle : Online

    data object Searching : Online

    data object NotFound : Online

    /** No object has the name as typed: those with names like it. */
    data class Many(val items: List<SkyObject>) : Online

    data class Failed(val reason: String) : Online
}

@Composable
private fun Results(query: String, results: List<SkyObject>, objects: ObjectSearch, open: (Screen) -> Unit) {
    var online by remember(query) { mutableStateOf<Online>(Online.Idle) }
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        itemsIndexed(results, key = { _, item -> item.designation }) { index, item ->
            ObjectRow(item, first = index == 0, last = index == results.lastIndex) { open(Screen.SkyObject(item.designation)) }
        }
        item {
            SimbadRow(query, online, Modifier.padding(top = if (results.isEmpty()) 0.dp else 12.dp)) {
                online = Online.Searching
                scope.launch {
                    online =
                        when (val result = objects.lookUp(query)) {
                            is LookUp.Found -> Online.Idle.also { open(Screen.SkyObject(result.item.designation)) }
                            is LookUp.Many -> Online.Many(result.items)
                            LookUp.NotFound -> Online.NotFound
                            is LookUp.Failed -> Online.Failed(result.reason)
                        }
                }
            }
        }
        val many = (online as? Online.Many)?.items.orEmpty()
        // Keyed apart from the catalogue's results above, which may show the same object.
        itemsIndexed(many, key = { _, item -> "simbad:" + item.designation }) { index, item ->
            SimbadMatch(item, first = index == 0, last = index == many.lastIndex) {
                scope.launch { open(Screen.SkyObject(objects.choose(item).designation)) }
            }
        }
    }
}

/** An object SIMBAD has with a name like the one searched: its name, kind and magnitude. */
@Composable
private fun SimbadMatch(item: SkyObject, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val comma = decimalComma()
    val details = listOfNotNull(item.kind(), item.magnitude?.let { stringResource(Res.string.obj_magnitude, formatDecimal(it, 1, comma)) })
    ListRow(first, last, onClick) {
        Text(item.name(), style = MaterialTheme.typography.bodyLarge)
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An object as the design's list draws it: its name, then its designation, kind and size. */
@Composable
private fun ObjectRow(item: SkyObject, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val name = item.name()
    val details = listOfNotNull(item.designation.takeIf { it != name }, item.kind(), item.size()).joinToString(" · ")
    ListRow(first, last, onClick) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The search in SIMBAD, with what it is doing. */
@Composable
private fun SimbadRow(query: String, online: Online, modifier: Modifier, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        ListRow(first = true, last = true, onClick = { if (online != Online.Searching) onClick() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    YacanaIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(Res.string.sky_simbad, query.trim()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                when (online) {
                    Online.Idle -> stringResource(Res.string.sky_simbad_hint)

                    Online.Searching -> stringResource(Res.string.sky_simbad_searching)

                    Online.NotFound -> stringResource(Res.string.sky_simbad_not_found, query.trim())

                    is Online.Many -> pluralStringResource(Res.plurals.sky_simbad_many, online.items.size, online.items.size)

                    // The reason is a network library's, in English: what to do is enough.
                    is Online.Failed -> stringResource(Res.string.sky_simbad_failed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (online is Online.Failed || online == Online.NotFound) MaterialTheme.colorScheme.error else muted,
            )
        }
    }
}

/** A row of a list that looks like one card, as the designs' lists. */
@Composable
private fun ListRow(first: Boolean, last: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val corner = 20.dp
    val shape =
        RoundedCornerShape(
            topStart = if (first) corner else 0.dp,
            topEnd = if (first) corner else 0.dp,
            bottomStart = if (last) corner else 0.dp,
            bottomEnd = if (last) corner else 0.dp,
        )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = if (first) 12.dp else 6.dp, bottom = if (last) 12.dp else 6.dp),
    ) { content() }
}
