package com.vario.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Main (and only) activity of VarioAppli.
 *
 * This activity is **purely passive**: it observes [VarioService.dataFlow] and
 * renders the current Vz and altitude. All hardware interaction (USB, audio)
 * lives in [VarioService].
 *
 * The activity also serves as the USB device attachment target: when the SAMD21
 * dongle is plugged in, Android launches this activity via the
 * `USB_DEVICE_ATTACHED` intent filter declared in the manifest.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VarioTheme {
                VarioScreen(
                    onStartService = { startVarioService() },
                    onStopService = { stopVarioService() }
                )
            }
        }
    }

    private fun startVarioService() {
        startForegroundService(Intent(this, VarioService::class.java))
    }

    private fun stopVarioService() {
        stopService(Intent(this, VarioService::class.java))
    }
}

// ── Theme ────────────────────────────────────────────────────────────────────

/** Minimal dark color scheme — optimized for outdoor readability. */
@Composable
private fun VarioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0D0D0D),
            surface = Color(0xFF1A1A2E),
            onBackground = Color.White,
            onSurface = Color.White,
            primary = Color(0xFF00E676),
            secondary = Color(0xFFFF5252)
        ),
        content = content
    )
}

// ── Main screen ──────────────────────────────────────────────────────────────

/**
 * Variometer display screen.
 *
 * Shows the vertical speed (Vz) in large, color-coded digits and the
 * altitude below. Two buttons control the [VarioService] lifecycle.
 *
 * Color coding:
 * - **Green** (Vz > +0.3 m/s) — climbing
 * - **Red** (Vz < −2.0 m/s) — sinking
 * - **Grey** — dead zone
 */
@Composable
private fun VarioScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val varioData by VarioService.dataFlow.collectAsStateWithLifecycle()

    val vzColor = when {
        varioData.vzMs > 0.3f -> Color(0xFF00E676)   // green — climb
        varioData.vzMs < -2.0f -> Color(0xFFFF5252)   // red   — sink
        else -> Color(0xFFB0BEC5)                      // grey  — neutral
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── Vz ──────────────────────────────────────────────────────
            Text(
                text = "Vz",
                fontSize = 18.sp,
                color = Color(0xFF757575),
                fontWeight = FontWeight.Light
            )
            Text(
                text = formatVz(varioData.vzMs),
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = vzColor,
                textAlign = TextAlign.Center
            )
            Text(
                text = "m/s",
                fontSize = 20.sp,
                color = Color(0xFF757575),
                fontWeight = FontWeight.Light
            )

            Spacer(modifier = Modifier.height(48.dp))

            // ── Altitude ────────────────────────────────────────────────
            Text(
                text = "ALT",
                fontSize = 18.sp,
                color = Color(0xFF757575),
                fontWeight = FontWeight.Light
            )
            Text(
                text = formatAlt(varioData.altitudeM),
                fontSize = 48.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "m",
                fontSize = 20.sp,
                color = Color(0xFF757575),
                fontWeight = FontWeight.Light
            )

            Spacer(modifier = Modifier.height(64.dp))

            // ── Control buttons ─────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onStartService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676)
                    )
                ) {
                    Text("START", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onStopService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF5252)
                    )
                ) {
                    Text("STOP", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Formatting helpers (UI thread only, not on the audio fast path) ──────────

private fun formatVz(vz: Float): String {
    val sign = if (vz >= 0) "+" else ""
    return "$sign%.1f".format(vz)
}

private fun formatAlt(alt: Float): String {
    return "%.0f".format(alt)
}
