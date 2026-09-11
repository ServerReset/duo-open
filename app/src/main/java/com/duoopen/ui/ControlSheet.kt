package com.duoopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlin.math.roundToInt

/**
 * Tuning controls. A modal sheet is its own window, so it stays crisp while
 * the screen behind it folds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlSheet(
    config: DuoConfig,
    sensorName: String?,
    hingeAngle: Float,
    paneTilt: Float,
    onPickImage: () -> Unit,
    onDefaultImage: () -> Unit,
    onSetWallpaper: () -> Unit,
    overlayEnabled: Boolean,
    onEnableOverlay: () -> Unit,
    onOpenGuide: () -> Unit,
    onCopyReport: () -> Unit,
    onTestOverlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text("Full-screen fold", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (overlayEnabled) {
                    "On — folds the whole screen (any wallpaper, icons, apps) as you open the phone."
                } else {
                    "Off — only the live wallpaper folds. Android blocks Accessibility for sideloaded apps " +
                        "(“restricted settings”), so turning it on takes two extra taps the first time. The " +
                        "guide walks you through it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (overlayEnabled) {
                    Button(onClick = onTestOverlay, modifier = Modifier.weight(1f)) { Text("Test it now") }
                    OutlinedButton(onClick = onEnableOverlay, modifier = Modifier.weight(1f)) { Text("Accessibility") }
                } else {
                    Button(onClick = onEnableOverlay, modifier = Modifier.weight(1f)) { Text("Turn on in Accessibility") }
                    OutlinedButton(onClick = onOpenGuide, modifier = Modifier.weight(1f)) { Text("Setup guide") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Unfold effect", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (sensorName != null) "Hinge sensor: $sensorName" else "No hinge sensor found on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Hinge ${if (hingeAngle.isNaN()) "—" else "%.1f°".format(hingeAngle)}  ·  pane tilt %.1f°".format(paneTilt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onCopyReport) { Text("Copy sensor report") }

            Spacer(Modifier.height(12.dp))

            Text("Moving half", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "Left", 1 to "Right", 0 to "Both").forEach { (side, label) ->
                    FilterChip(
                        selected = config.movingSide == side,
                        onClick = { DuoSettings.update { it.copy(movingSide = side) } },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Cover screen frost from", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to "Right", false to "Left").forEach { (fromRight, label) ->
                    FilterChip(
                        selected = config.coverFrostFromRight == fromRight,
                        onClick = { DuoSettings.update { it.copy(coverFrostFromRight = fromRight) } },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            LabeledSlider(
                label = "Strength",
                valueText = "%.2f×".format(config.intensity),
                value = config.intensity,
                onValueChange = { v -> DuoSettings.update { it.copy(intensity = v) } },
                range = 0.5f..3f,
            )
            LabeledSlider(
                label = "Frost",
                valueText = "%.2f".format(config.blurSpread),
                value = config.blurSpread,
                onValueChange = { v -> DuoSettings.update { it.copy(blurSpread = v) } },
                range = 0.02f..0.3f,
            )
            LabeledSlider(
                label = "Darkening",
                valueText = "%.3f".format(config.darkening),
                value = config.darkening,
                onValueChange = { v -> DuoSettings.update { it.copy(darkening = v) } },
                range = 0f..0.04f,
            )
            LabeledSlider(
                label = "Eye distance",
                valueText = "${config.eyeDistanceMm.roundToInt()} mm",
                value = config.eyeDistanceMm,
                onValueChange = { v -> DuoSettings.update { it.copy(eyeDistanceMm = v) } },
                range = 200f..800f,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Fold splits the ${if (config.foldSplitsLong) "long" else "short"} side",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { DuoSettings.update { it.copy(foldSplitsLong = !it.foldSplitsLong) } }) {
                    Text("Flip")
                }
            }
            TextButton(onClick = DuoSettings::resetTuning) { Text("Reset tuning") }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reduce in Battery Saver", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Lets Android turn the full-screen fold off and slows the sensor while saving power.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.powerSaveReducesEffect,
                    onCheckedChange = { v -> DuoSettings.update { it.copy(powerSaveReducesEffect = v) } },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                    Text("Choose image")
                }
                OutlinedButton(onClick = onDefaultImage, modifier = Modifier.weight(1f)) {
                    Text("Default image")
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSetWallpaper, modifier = Modifier.fillMaxWidth()) {
                Text("Set as live wallpaper")
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}
