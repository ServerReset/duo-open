package com.duoopen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val Glass = Color.White.copy(alpha = 0.14f)
private val GlassEdge = Color.White.copy(alpha = 0.22f)
private val Dim = Color.White.copy(alpha = 0.72f)

/**
 * The surface under the fold in the app: wallpaper image plus a lock-screen
 * style clock, a live hinge readout and a few crisp shapes so the frost and
 * perspective read clearly.
 */
@Composable
fun HomePreview(
    image: ImageBitmap?,
    hingeAngle: Float,
    paneTilt: Float,
    simulate: Boolean,
    sensorPresent: Boolean,
    sensorStatus: () -> String,
    simulatedAngle: Float,
    onSimulateChange: (Boolean) -> Unit,
    onSimulatedAngleChange: (Float) -> Unit,
    batterySaverReduced: Boolean,
    overlayEnabled: Boolean,
    wallpaperActive: Boolean,
    onSetWallpaper: () -> Unit,
    onSetup: () -> Unit,
    onTune: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color(0xFF0D0A1C))) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.35f to Color.Transparent,
                        0.75f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Clock()

            if (!overlayEnabled) {
                Spacer(Modifier.height(14.dp))
                SetupBanner(onSetup)
            }

            Spacer(Modifier.weight(1f))

            HingeReadout(hingeAngle, paneTilt, simulate, batterySaverReduced)

            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Hinge", if (hingeAngle.isNaN()) "—" else "%.1f°".format(hingeAngle))
                StatTile("Pane tilt", "%.1f°".format(paneTilt))
                StatTile("State", if (paneTilt < 0.05f) "Flat" else "Folding")
            }

            Spacer(Modifier.height(14.dp))

            SimulatorCard(
                simulate = simulate,
                sensorPresent = sensorPresent,
                sensorStatus = sensorStatus,
                angle = simulatedAngle,
                onSimulateChange = onSimulateChange,
                onAngleChange = onSimulatedAngleChange,
            )

            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSetWallpaper,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF14121F),
                    ),
                ) {
                    Text(if (wallpaperActive) "Wallpaper active ✓" else "Set live wallpaper")
                }
                FilledTonalButton(
                    onClick = onTune,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Glass,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Tune")
                }
            }
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(15_000)
        }
    }
    Text(
        now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
        color = Dim,
        fontSize = 16.sp,
    )
    Text(
        now.format(DateTimeFormatter.ofPattern("H:mm")),
        color = Color.White,
        fontSize = 96.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-2).sp,
    )
}

@Composable
private fun SetupBanner(onSetup: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Full-screen fold is off",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Set it up to fold apps and the launcher too",
                color = Dim,
                fontSize = 12.sp,
            )
        }
        Button(
            onClick = onSetup,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF14121F),
            ),
        ) {
            Text("Set up")
        }
    }
}

@Composable
private fun SimulatorCard(
    simulate: Boolean,
    sensorPresent: Boolean,
    sensorStatus: () -> String,
    angle: Float,
    onSimulateChange: (Boolean) -> Unit,
    onAngleChange: (Float) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Simulate hinge",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                if (sensorPresent) SensorStatusLine(sensorStatus)
                else Text("No hinge sensor — slider only", color = Dim, fontSize = 11.sp)
            }
            Switch(
                checked = simulate,
                onCheckedChange = onSimulateChange,
                enabled = sensorPresent,
            )
        }
        if (simulate || !sensorPresent) {
            Spacer(Modifier.height(6.dp))
            Slider(
                value = angle,
                onValueChange = onAngleChange,
                valueRange = 0f..180f,
            )
            Text("Hinge %.0f°".format(angle), color = Dim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SensorStatusLine(status: () -> String) {
    // Own ticker so the status refreshes even with no events, without
    // recomposing the whole screen.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            tick++
        }
    }
    val text = remember(tick) { status() }
    Text(text, color = Dim, fontSize = 11.sp)
}

@Composable
private fun HingeReadout(
    hingeAngle: Float,
    paneTilt: Float,
    simulated: Boolean,
    batterySaverReduced: Boolean,
) {
    val hint = when {
        batterySaverReduced -> "Battery Saver on — effect reduced"
        simulated -> "Simulated hinge — drag the slider on the main screen"
        hingeAngle.isNaN() -> "Waiting for hinge sensor — check the sensor status below"
        paneTilt < 0.05f -> "Fold the phone partway, then open it"
        else -> "Keep opening"
    }
    Box(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(28.dp))
            .padding(vertical = 22.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "DUO OPEN",
                color = Dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hingeAngle.isNaN()) "—" else "%.1f°".format(hingeAngle),
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(hint, color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column(
        Modifier
            .size(width = 104.dp, height = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Dim, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
