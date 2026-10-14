// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.imaging.FileNames
import app.yacana.ui.FrameFolder
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.image_files_folder
import app.yacana.ui.resources.image_files_folder_change
import app.yacana.ui.resources.image_files_folder_summary
import app.yacana.ui.resources.image_files_next
import app.yacana.ui.resources.image_files_no_profile
import app.yacana.ui.resources.image_files_pattern
import app.yacana.ui.resources.image_files_pattern_hint
import app.yacana.ui.resources.image_files_pattern_reset
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * Where frames go and their names, as "Settings · images and files": the
 * folder, and the name pattern with its tokens as chips that are tapped in and the name the next
 * frame will get. Frames are always FITS; names belong to the profile.
 */
@Composable
fun ImageFilesScreen(
    equipment: EquipmentController,
    folder: FrameFolder,
    clock: Clock = Clock.System,
    zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    val location by folder.location.collectAsState()
    val state by equipment.state.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Text(stringResource(Res.string.image_files_folder), style = MaterialTheme.typography.bodyMedium, color = muted)
            Text(location, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
            Text(stringResource(Res.string.image_files_folder_summary), style = MaterialTheme.typography.bodyMedium, color = muted)
            SettingChip(
                stringResource(Res.string.image_files_folder_change),
                onClick = folder::change,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        val profile = state.active
        if (profile == null) {
            Text(stringResource(Res.string.image_files_no_profile), color = muted, modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }
        fun setPattern(pattern: String) {
            if (pattern.isNotBlank()) equipment.save(profile.copy(options = profile.options.copy(filePattern = pattern)))
        }
        SectionCard {
            Text(stringResource(Res.string.image_files_pattern), style = MaterialTheme.typography.bodyMedium, color = muted)
            var text by remember(profile.options.filePattern) { mutableStateOf(profile.options.filePattern) }
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    setPattern(it)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier =
                Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Text(stringResource(Res.string.image_files_pattern_hint), style = MaterialTheme.typography.bodyMedium, color = muted)
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FileNames.TOKENS.forEach { token -> SettingChip(token, onClick = { setPattern(text + "\$\$$token\$\$") }) }
            }
            Text(
                stringResource(Res.string.image_files_next),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                FileNames.example(profile.options.filePattern, clock.now(), zone()) + ".fits",
                style = MaterialTheme.typography.bodySmall,
            )
            SettingChip(
                stringResource(Res.string.image_files_pattern_reset),
                onClick = { setPattern(FileNames.DEFAULT_PATTERN) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
