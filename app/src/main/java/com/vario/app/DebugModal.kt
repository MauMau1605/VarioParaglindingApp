package com.vario.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Full-featured Diagnostic and Debug Modal for VarioAppli.
 *
 * Provides real-time visibility and hardware testing across 5 diagnostic tabs:
 * 1. GPS Diagnostic: fix status, accuracy (horizontal + vertical), coordinates, fix age, Vz.
 * 2. USB & LK8EX1 Diagnostic: VID/PID, port state, permission request, baud rate selection, frame counters, raw sentence.
 * 3. Audio Diagnostics: interactive climb and sink audio generator test buttons.
 * 4. Console Logs: live in-memory logs with clipboard export and buffer clearing.
 * 5. Battery Optimization: detection of OS background restrictions with deep link to settings.
 */
@Composable
fun DebugModal(
    varioData: VarioData,
    onDismiss: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onSetBaudRate: (Int) -> Unit,
    onTestAudio: (Float) -> Unit,
    onStopAudioTest: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("GPS", "USB & LK8EX1", "Audio", "Console Logs", "Batterie")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D14).copy(alpha = 0.95f))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131926))
                    .border(1.dp, Color(0xFF1E2A3A), RoundedCornerShape(16.dp))
            ) {
                // ── Top Title Bar ────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF182234))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color(0xFF4ADE80), CircleShape)
                        )
                        Text(
                            text = "Diagnostics & Débogage",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF222F44), RoundedCornerShape(8.dp))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                // ── Tab Bar ──────────────────────────────────────────────────
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF101622),
                    contentColor = Color(0xFF4ADE80),
                    edgePadding = 12.dp,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFF4ADE80)
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTab == index) Color(0xFF4ADE80) else Color(0xFF8B9CB0)
                                )
                            }
                        )
                    }
                }

                // ── Tab Contents ─────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    when (selectedTab) {
                        0 -> GpsDiagnosticTab(varioData)
                        1 -> UsbDiagnosticTab(varioData, onRequestUsbPermission, onSetBaudRate)
                        2 -> AudioDiagnosticTab(onTestAudio, onStopAudioTest)
                        3 -> ConsoleLogsTab()
                        4 -> BatteryOptimizationTab()
                    }
                }
            }
        }
    }
}

// ── Tab 1: GPS Diagnostic ────────────────────────────────────────────────────

@Composable
private fun GpsDiagnosticTab(data: VarioData) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DiagnosticCard(title = "État du récepteur GNSS") {
            DiagRow("Fix GPS acquis", if (data.gpsFixAcquired) "OUI" else "EN ATTENTE", if (data.gpsFixAcquired) Color(0xFF4ADE80) else Color(0xFFF87171))
            DiagRow("Disponibilité matérielle", if (data.isGpsAvailable) "DISPONIBLE" else "INDISPONIBLE", if (data.isGpsAvailable) Color(0xFF4ADE80) else Color(0xFFF87171))
            DiagRow("Précision horizontale", if (data.gpsAccuracyM > 0f) "± %.1f m".format(data.gpsAccuracyM) else "Inconnue")
            DiagRow("Précision verticale", if (data.gpsVerticalAccuracyM > 0f) "± %.1f m".format(data.gpsVerticalAccuracyM) else "N/A")
            DiagRow(
                "Âge du dernier fix",
                if (data.lastGpsFixAgeSec >= 0f) "%.1f s".format(data.lastGpsFixAgeSec) else "N/A",
                if (data.lastGpsFixAgeSec in 0f..2.5f) Color(0xFF4ADE80) else Color(0xFFFACC15)
            )
        }

        DiagnosticCard(title = "Navigation & Données") {
            DiagRow("Latitude", "%.6f°".format(data.latitude))
            DiagRow("Longitude", "%.6f°".format(data.longitude))
            DiagRow("Altitude GPS", "%.1f m".format(data.gpsAltitudeM))
            DiagRow("Altitude baro/affichée", "%.1f m".format(data.altitudeM))
            DiagRow("Vitesse verticale (Vz)", "%+.2f m/s".format(data.vzMs))
            DiagRow("QNH calibré", if (data.isCalibrated) "OUI" else "NON", if (data.isCalibrated) Color(0xFF4ADE80) else Color(0xFFFACC15))
            DiagRow("Distance déco", data.distanceToTakeoffM?.let { "%.1f m".format(it) } ?: "N/A")
        }
    }
}

// ── Tab 2: USB & LK8EX1 Diagnostic ───────────────────────────────────────────

@Composable
private fun UsbDiagnosticTab(
    data: VarioData,
    onRequestPermission: () -> Unit,
    onSetBaudRate: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DiagnosticCard(title = "Périphérique USB") {
            DiagRow("Nom de l'appareil", data.usbDeviceName ?: "Aucun dongle connecté")
            val vidHex = "0x%04X".format(data.usbVid)
            val pidHex = "0x%04X".format(data.usbPid)
            DiagRow("Identifiant (VID : PID)", "$vidHex : $pidHex")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Permission USB", fontSize = 13.sp, color = Color(0xFF8B9CB0))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (data.usbPermissionGranted) "ACCORDÉE" else "NON ACCORDÉE",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (data.usbPermissionGranted) Color(0xFF4ADE80) else Color(0xFFF87171)
                    )
                    if (!data.usbPermissionGranted) {
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Demander", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }

            DiagRow(
                "Scan USB (30s)",
                if (data.isUsbScanning) "EN COURS" else "INACTIF",
                if (data.isUsbScanning) Color(0xFF38BDF8) else Color(0xFF8B9CB0)
            )

            DiagRow(
                "État du port série",
                if (data.usbPortOpen) "OUVERT" else "FERMÉ",
                if (data.usbPortOpen) Color(0xFF4ADE80) else Color(0xFFF87171)
            )
        }

        DiagnosticCard(title = "Configuration Vitesse (Baud Rate)") {
            Text(
                text = "Vitesse active : ${data.currentBaudRate} bauds",
                fontSize = 13.sp,
                color = Color(0xFFCBD5E1),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VarioService.SUPPORTED_BAUD_RATES.forEach { baud ->
                    val isCurrent = data.currentBaudRate == baud
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCurrent) Color(0xFF4ADE80) else Color(0xFF1E2A3A))
                            .clickable { onSetBaudRate(baud) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "$baud",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrent) Color(0xFF090D14) else Color(0xFFE2E8F0)
                        )
                    }
                }
            }
        }

        DiagnosticCard(title = "Statistiques & Flux LK8EX1") {
            DiagRow("Octets lus (Total)", "${data.totalBytesRead} B")
            DiagRow("Trames valides parsées", "${data.validFramesCount}", Color(0xFF4ADE80))
            DiagRow("Erreurs CRC / Mismatch", "${data.crcErrorsCount}", if (data.crcErrorsCount > 0L) Color(0xFFF87171) else Color(0xFF8B9CB0))
            DiagRow("Pression brute trame", "${data.lastRawPressurePa} Pa")
            DiagRow("Altitude baro calculée", "%.1f m".format(data.altitudeM), Color(0xFF4ADE80))
            DiagRow("Vario brut trame (champ 2)", "${data.lastRawVarioCmS} cm/s")
            DiagRow("Vitesse verticale Vz active", "%+.2f m/s".format(data.vzMs))

            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "Dernière trame LK8EX1 brute :", fontSize = 12.sp, color = Color(0xFF8B9CB0))
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0F1A), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = if (data.lastRawSentence.isNotEmpty()) data.lastRawSentence else "(En attente de trame LK8EX1...)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (data.lastRawSentence.isNotEmpty()) Color(0xFF4ADE80) else Color(0xFF64748B)
                )
            }
        }
    }
}

// ── Tab 3: Audio Diagnostic ──────────────────────────────────────────────────

@Composable
private fun AudioDiagnosticTab(
    onTestAudio: (Float) -> Unit,
    onStopAudioTest: () -> Unit
) {
    var activeToneDesc by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DiagnosticCard(title = "Synthèse sonore temps réel") {
            Text(
                text = "Testez la génération PCM et la latence audio indépendamment des capteurs USB et GPS.",
                fontSize = 13.sp,
                color = Color(0xFF94A3B8)
            )
            Spacer(modifier = Modifier.height(8.dp))
            DiagRow("Statut du test", activeToneDesc ?: "Inactif (Audio capteurs)", if (activeToneDesc != null) Color(0xFF4ADE80) else Color(0xFF8B9CB0))
        }

        Button(
            onClick = {
                activeToneDesc = "Bip Montée (+2.5 m/s)"
                onTestAudio(2.5f)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
        ) {
            Text("🔊 Tester Bip Montée (+2.5 m/s)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Button(
            onClick = {
                activeToneDesc = "Alarme Descente (-3.0 m/s)"
                onTestAudio(-3.0f)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
            Text("🚨 Tester Alarme Descente (-3.0 m/s)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Button(
            onClick = {
                activeToneDesc = null
                onStopAudioTest()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
        ) {
            Text("⏹ Arrêter le son de test", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

// ── Tab 4: Console Logs ──────────────────────────────────────────────────────

@Composable
private fun ConsoleLogsTab() {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(DebugLogger.getLogs()) }
    val listState = rememberLazyListState()

    // Periodically refresh logs every 800ms
    LaunchedEffect(Unit) {
        while (true) {
            delay(800L)
            logs = DebugLogger.getLogs()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Controls Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${logs.size} entrées",
                fontSize = 12.sp,
                color = Color(0xFF8B9CB0),
                fontFamily = FontFamily.Monospace
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val text = logs.joinToString("\n")
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("VarioLogs", text)
                        cm.setPrimaryClip(clip)
                        Toast.makeText(context, "Logs copiés (${logs.size} lignes)", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Copier", fontSize = 11.sp, color = Color.White)
                }

                Button(
                    onClick = {
                        DebugLogger.clear()
                        logs = emptyList()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Effacer", fontSize = 11.sp, color = Color.White)
                }
            }
        }

        // Scrollable Log List
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF090D14), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "Aucun log enregistré.",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logs) { entry ->
                        val color = when {
                            entry.contains("[ERROR]") -> Color(0xFFF87171)
                            entry.contains("[WARN]") -> Color(0xFFFBBF24)
                            entry.contains("[DEBUG]") -> Color(0xFF94A3B8)
                            else -> Color(0xFF4ADE80)
                        }
                        Text(
                            text = entry,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Tab 5: Battery Optimization ──────────────────────────────────────────────

@Composable
private fun BatteryOptimizationTab() {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoring by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DiagnosticCard(title = "Gestion d'énergie Android") {
            Text(
                text = "Pour éviter que le système ne coupe le service variomètre et le son lorsque l'écran s'éteint ou en vol prolongé, les optimisations de batterie doivent être désactivées pour cette application.",
                fontSize = 13.sp,
                color = Color(0xFF94A3B8)
            )
            Spacer(modifier = Modifier.height(10.dp))
            DiagRow(
                "État de l'optimisation",
                if (isIgnoring) "IGNORÉE (RECOMMANDÉ)" else "RESTREINTE",
                if (isIgnoring) Color(0xFF4ADE80) else Color(0xFFF87171)
            )
        }

        if (!isIgnoring) {
            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (e2: Exception) {
                            Toast.makeText(context, "Impossible d'ouvrir les paramètres batterie", Toast.LENGTH_SHORT).show()
                        }
                    }
                    isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEAB308))
            ) {
                Text("⚡ Désactiver restrictions de batterie", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF090D14))
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14532D).copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF22C55E).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "✓ Parfait ! L'application est autorisée à fonctionner en arrière-plan sans interruption CPU.",
                    fontSize = 13.sp,
                    color = Color(0xFF4ADE80)
                )
            }
        }
    }
}

// ── Reusable Diagnostic UI Helpers ───────────────────────────────────────────

@Composable
private fun DiagnosticCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF182234), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1F2E45), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF93C5FD)
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun DiagRow(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = Color(0xFF8B9CB0))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}
