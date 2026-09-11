package com.duoopen.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.isInnerPanel
import com.duoopen.overlay.FoldOverlayService
import com.duoopen.overlay.OverlayState
import com.duoopen.power.PowerState
import com.duoopen.settings.DuoSettings
import com.duoopen.wallpaper.DuoWallpaperService
import com.duoopen.wallpaper.WallpaperImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DuoApp(foldLineFlow: StateFlow<FoldLine?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by DuoSettings.config.collectAsStateWithLifecycle()
    val foldLine by foldLineFlow.collectAsStateWithLifecycle()

    var hingeAngle by remember { mutableFloatStateOf(Float.NaN) }
    val hinge = remember { HingeAngleSource(context.applicationContext) { hingeAngle = it } }
    // Keep the sensor registered for the whole composition. Tying it to
    // onResume/onPause stopped it whenever the fold briefly paused or refocused
    // the activity, which is what made the display only catch up on reopen.
    DisposableEffect(hinge) {
        hinge.start()
        onDispose { hinge.stop() }
    }
    val batterySaver by PowerState.batterySaver.collectAsStateWithLifecycle()

    // Without a hinge sensor (emulator, non-foldable) the slider is the only input.
    var simulate by rememberSaveable { mutableStateOf(hinge.sensor == null) }
    var simulatedAngle by rememberSaveable { mutableFloatStateOf(120f) }
    val angle = if (simulate || hinge.sensor == null) simulatedAngle else hingeAngle
    // Re-read the panel on every configuration change (the fold swaps panels).
    LocalConfiguration.current
    val onCover = !simulate && hinge.sensor != null && !context.display.isInnerPanel()
    // HingeAngleSource already de-jitters with a deadband + adaptive low-pass,
    // so the preview and the readout use the value directly.
    val targetTilt = if (angle.isNaN() || onCover) 0f else DuoShader.tiltForHinge(angle, config)
    val paneTilt = targetTilt

    val shader = remember { DuoShader.create(context) }
    // In Battery Saver the preview stays flat too, matching the services.
    val effectReduced = batterySaver && config.powerSaveReducesEffect
    val pxPerMm = remember(context) { DuoShader.pxPerMm(context) }
    val image by produceState<ImageBitmap?>(null, config.imageVersion) {
        value = withContext(Dispatchers.IO) {
            WallpaperImage.load(context.applicationContext, config.imageVersion).asImageBitmap()
        }
    }

    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    val wallpaperActive = remember(resumeTick) { isWallpaperActive(context) }
    val overlayEnabled = remember(resumeTick) { FoldOverlayService.isEnabled(context) }
    val overlayRunning by OverlayState.running.collectAsStateWithLifecycle()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { WallpaperImage.import(context.applicationContext, uri) }.isSuccess
                }
                if (!ok) Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val setWallpaper = { openWallpaperPicker(context) }

    var showSheet by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HomePreview(
            image = image,
            hingeAngle = angle,
            paneTilt = paneTilt,
            simulate = simulate,
            sensorPresent = hinge.sensor != null,
            sensorStatus = { hinge.statusText() },
            simulatedAngle = simulatedAngle,
            onSimulateChange = { simulate = it },
            onSimulatedAngleChange = { simulatedAngle = it },
            batterySaverReduced = effectReduced,
            overlayEnabled = overlayEnabled,
            wallpaperActive = wallpaperActive,
            onSetWallpaper = setWallpaper,
            onSetup = {
                showSheet = false
                showGuide = true
            },
            onTune = { showSheet = true },
            modifier = if (shader != null && !overlayRunning && !effectReduced) {
                Modifier.foldEffect(shader, { paneTilt }, config, pxPerMm, foldLine)
            } else {
                Modifier
            },
        )

        if (showSheet) {
            ControlSheet(
                config = config,
                sensorName = hinge.sensors.takeIf { it.isNotEmpty() }?.joinToString("\n") {
                    "${it.name} (${if (it.isWakeUpSensor) "wake-up" else "continuous"})"
                },
                hingeAngle = angle,
                paneTilt = paneTilt,
                onPickImage = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onDefaultImage = { WallpaperImage.reset(context.applicationContext) },
                onSetWallpaper = setWallpaper,
                overlayEnabled = overlayEnabled,
                onEnableOverlay = { openAccessibilitySettings(context) },
                onOpenGuide = {
                    showSheet = false
                    showGuide = true
                },
                onTestOverlay = {
                    val service = FoldOverlayService.instance
                    if (service == null) {
                        Toast.makeText(context, "Turn on the full-screen fold first", Toast.LENGTH_SHORT).show()
                    } else {
                        showSheet = false
                        // Let the sheet finish closing so it isn't in the snapshot.
                        scope.launch {
                            kotlinx.coroutines.delay(450)
                            service.playDemo()
                        }
                    }
                },
                onDismiss = { showSheet = false },
            )
        }

        if (showGuide) {
            AccessibilityGuide(
                overlayEnabled = overlayEnabled,
                onOpenAccessibility = { openAccessibilitySettings(context) },
                onOpenAppInfo = { openAppInfo(context) },
                onCheckAgain = { resumeTick++ },
                onDismiss = { showGuide = false },
            )
        }
    }
}

private fun wallpaperComponent(context: Context) =
    ComponentName(context, DuoWallpaperService::class.java)

private fun isWallpaperActive(context: Context): Boolean =
    WallpaperManager.getInstance(context).wallpaperInfo?.component == wallpaperComponent(context)

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, "Couldn't open Accessibility settings", Toast.LENGTH_SHORT).show()
    }
}

/** The app info page is where the "Allow restricted settings" option lives. */
private fun openAppInfo(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, "Couldn't open app info", Toast.LENGTH_SHORT).show()
    }
}

private fun openWallpaperPicker(context: Context) {
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, wallpaperComponent(context))
    val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
    for (intent in listOf(direct, chooser)) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    Toast.makeText(context, "No live wallpaper picker found", Toast.LENGTH_SHORT).show()
}
