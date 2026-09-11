package com.duoopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Step-by-step walkthrough for Android's "restricted settings" wall.
 *
 * Since Android 13 a sideloaded app can't be granted Accessibility until the
 * user explicitly allows restricted settings, and the system hides that option
 * until you first try to enable the service and get refused. This sheet drives
 * exactly that sequence and deep-links to the two settings screens involved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityGuide(
    overlayEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onCheckAgain: () -> Unit,
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
                .padding(bottom = 24.dp),
        ) {
            Text("Enable the full-screen fold", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Android blocks Accessibility for apps installed outside the Play Store — it calls this " +
                    "“restricted settings”. This is a one-time setup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            if (overlayEnabled) {
                SuccessCard()
            } else {
                Step(
                    number = 1,
                    title = "Get refused on purpose",
                    body = "Open Accessibility, find “Duo Open Live full-screen fold”, and try to turn it on. " +
                        "Android will refuse with “Restricted setting — for your security, this setting is " +
                        "currently unavailable.” Dismiss that dialog. This refusal is exactly what makes the " +
                        "next option appear — most guides skip it.",
                )
                Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Accessibility settings")
                }

                Spacer(Modifier.height(18.dp))

                Step(
                    number = 2,
                    title = "Allow restricted settings",
                    body = "Open this app's info page and tap the ⋮ menu in the top-right, then choose " +
                        "“Allow restricted settings” and confirm with your PIN, pattern or fingerprint.\n\n" +
                        "Samsung One UI 6+: the option may be a plain menu item further down the app info page " +
                        "instead of behind ⋮. If neither appears, see the notes at the bottom.",
                )
                Button(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app info")
                }

                Spacer(Modifier.height(18.dp))

                Step(
                    number = 3,
                    title = "Turn it on",
                    body = "Go back to Accessibility and enable “Duo Open Live full-screen fold”. Tap Allow when " +
                        "prompted, then come back here.",
                )
                OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to Accessibility")
                }

                Spacer(Modifier.height(20.dp))

                Button(onClick = onCheckAgain, modifier = Modifier.fillMaxWidth()) {
                    Text("I've done this — check now")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 20.dp))

            Text("If the option still won't show", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Bullet("Samsung: turn off Settings → Security and privacy → Auto Blocker, then repeat step 2.")
            Bullet("If the app never appears under Accessibility → Installed apps, uninstall it and reinstall by tapping the APK from your file manager (installing over ADB can hide the app).")
            Bullet("On some OnePlus (OxygenOS 15) builds the ⋮ menu is gone; installing the APK with a session installer like SAI makes Android treat it as a store app and the restriction never applies.")
            Bullet("Full written guide with per-brand steps: github.com/ServerReset/duo-open/blob/main/docs/ENABLE_ACCESSIBILITY.md")

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun SuccessCard() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Accessibility is on", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "The fold now covers the whole screen — launcher, apps, lock screen. Fold the phone partway " +
                    "and open it, or use Test it now.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Step(number: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.size(28.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("$number", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
