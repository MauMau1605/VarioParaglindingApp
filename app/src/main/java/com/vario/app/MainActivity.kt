package com.vario.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Main activity of VarioAppli.
 *
 * Cockpit UI with real-time flight instrument displays and debug modal:
 * - Top header: GPS state, sensor mode badge, USB reconnect button, debug terminal button, flight timer, and audio mute toggle.
 * - Variometer section: Vertical ladder gauge alongside big Vz digits and flight status.
 * - Altitude section: Large barometric altitude readout with standard units.
 * - Info cards: Ceiling altitude reached ("Plafond atteint") and distance to takeoff ("Distance au déco").
 * - Action button: Full-width flight start/stop trigger.
 * - Debug Modal: Complete diagnostic center for GPS, USB/LK8EX1, Audio synthesis, Console logs, and Battery optimization.
 */
/**
 * Top-level application tabs.
 */
enum class CockpitTab {
    VARIO,
    MAP
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            startVarioService()
        }
    }

    private var lastUsbAttachTimeMs: Long = 0L

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val isPortOpen = VarioService.instance?.isSerialPortOpen() == true
                    if (isPortOpen) {
                        Log.d("MainActivity", "USB device attached ignored (port already open)")
                        return
                    }
                    lastUsbAttachTimeMs = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, "Module USB connecté", Toast.LENGTH_SHORT).show()
                    val reconnectIntent = Intent(this@MainActivity, VarioService::class.java).apply {
                        action = VarioService.ACTION_RECONNECT_USB
                    }
                    startService(reconnectIntent)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    lastUsbAttachTimeMs = 0L
                    Toast.makeText(this@MainActivity, "Module USB déconnecté - Mode GPS actif", Toast.LENGTH_SHORT).show()
                    VarioService.setUsbStatus(false, null)
                    val disconnectIntent = Intent(this@MainActivity, VarioService::class.java).apply {
                        action = VarioService.ACTION_DISCONNECT_USB
                    }
                    startService(disconnectIntent)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            org.osmdroid.config.Configuration.getInstance().load(
                applicationContext,
                getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
            )
            org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed initializing osmdroid configuration", e)
        }

        checkAndRequestLocationPermissions()
        if (hasLocationPermission()) {
            startVarioService()
        }

        setContent {
            VarioCockpitTheme {
                var currentTab by remember { mutableStateOf(CockpitTab.VARIO) }
                val varioData by VarioService.dataFlow.collectAsStateWithLifecycle()

                if (currentTab == CockpitTab.VARIO) {
                    VarioScreen(
                        varioData = varioData,
                        onStartFlight = { startFlight() },
                        onStopFlight = { stopFlight() },
                        onToggleMute = { toggleMute() },
                        onReconnectUsb = { manualReconnectUsb() },
                        onRequestUsbPermission = { requestUsbPermission() },
                        onSetBaudRate = { setBaudRate(it) },
                        onTestAudio = { testAudio(it) },
                        onStopAudioTest = { stopAudioTest() },
                        onOpenMap = { currentTab = CockpitTab.MAP }
                    )
                } else {
                    MapScreen(
                        varioData = varioData,
                        onBackToVario = { currentTab = CockpitTab.VARIO }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            startVarioService()
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering usbReceiver", e)
        }
    }

    private fun startVarioService() {
        val intent = Intent(this, VarioService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun checkAndRequestLocationPermissions() {
        if (!hasLocationPermission()) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!hasNotif) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun manualReconnectUsb() {
        if (VarioService.dataFlow.value.isUsbConnected) {
            Toast.makeText(this, "Module USB déjà connecté", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Recherche du module USB (30s)...", Toast.LENGTH_SHORT).show()
        val reconnectIntent = Intent(this, VarioService::class.java).apply {
            action = VarioService.ACTION_RECONNECT_USB
        }
        startService(reconnectIntent)
    }

    private fun requestUsbPermission() {
        val intent = Intent(this, VarioService::class.java).apply {
            action = VarioService.ACTION_REQUEST_USB_PERMISSION
        }
        startService(intent)
    }

    private fun setBaudRate(baudRate: Int) {
        val intent = Intent(this, VarioService::class.java).apply {
            action = VarioService.ACTION_SET_BAUD_RATE
            putExtra(VarioService.EXTRA_BAUD_RATE, baudRate)
        }
        startService(intent)
    }

    private fun testAudio(vz: Float) {
        val intent = Intent(this, VarioService::class.java).apply {
            action = VarioService.ACTION_TEST_TONE
            putExtra(VarioService.EXTRA_TEST_VZ, vz)
        }
        startService(intent)
    }

    private fun stopAudioTest() {
        val intent = Intent(this, VarioService::class.java).apply {
            action = VarioService.ACTION_STOP_TEST_TONE
        }
        startService(intent)
    }

    private fun startFlight() {
        val intent = Intent(this, VarioService::class.java).apply {
            action = VarioService.ACTION_START_FLIGHT
        }
        startForegroundService(intent)
    }

    private fun stopFlight() {
        val intent = Intent(this, VarioService::class.java).apply {
            action = VarioService.ACTION_STOP_FLIGHT
        }
        startService(intent)
    }

    private fun toggleMute() {
        val intent = Intent(this, VarioService::class.java).apply {
            action = VarioService.ACTION_TOGGLE_MUTE
        }
        startService(intent)
    }
}

// ── Theme ────────────────────────────────────────────────────────────────────

@Composable
private fun VarioCockpitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF090D14),
            surface = Color(0xFF131926),
            onBackground = Color(0xFFE2E8F0),
            onSurface = Color(0xFFF8FAFC),
            primary = Color(0xFF4ADE80),
            secondary = Color(0xFFF87171)
        ),
        content = content
    )
}

// ── Main Screen ──────────────────────────────────────────────────────────────

@Composable
private fun VarioScreen(
    varioData: VarioData,
    onStartFlight: () -> Unit,
    onStopFlight: () -> Unit,
    onToggleMute: () -> Unit,
    onReconnectUsb: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onSetBaudRate: (Int) -> Unit,
    onTestAudio: (Float) -> Unit,
    onStopAudioTest: () -> Unit,
    onOpenMap: () -> Unit
) {
    val context = LocalContext.current
    var showDebugModal by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            onStartFlight()
        }
    }

    fun handleStartClick() {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val permissionsToRequest = mutableListOf<String>()
        if (!hasFine && !hasCoarse) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            onStartFlight()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top Header Bar ───────────────────────────────────────────
            HeaderBar(
                gpsReady = varioData.gpsFixAcquired,
                sensorMode = varioData.sensorMode,
                isUsbConnected = varioData.isUsbConnected,
                isUsbScanning = varioData.isUsbScanning,
                flightDurationSec = varioData.flightDurationSec,
                isMuted = varioData.isMuted,
                onReconnectUsb = onReconnectUsb,
                onToggleMute = onToggleMute,
                onOpenDebug = { showDebugModal = true },
                onOpenMap = onOpenMap
            )

            // ── Variometer Section (Ladder + Giant Vz) ───────────────────
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Vertical Ladder Gauge
                    VarioLadderGauge(
                        vz = varioData.vzMs,
                        modifier = Modifier
                            .width(36.dp)
                            .height(170.dp)
                    )

                    // Large Vz Display & Status
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = formatVz(varioData.vzMs),
                            fontSize = 82.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDCE5F0),
                            textAlign = TextAlign.Center,
                            lineHeight = 84.sp
                        )
                        Text(
                            text = "m/s",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFF8B9CB0)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (varioData.isFlightActive) "En vol" else "Posé",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (varioData.isFlightActive) Color(0xFF4ADE80) else Color(0xFF8B9CB0)
                        )
                    }
                }

                // Subtle separator divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF161E2D))
                )
            }

            // ── Altitude Readout ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatAlt(varioData.altitudeM),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 48.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "m",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF8B9CB0),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                Text(
                    text = "Altitude",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF8B9CB0),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // ── Three Info Cards (Ceiling + Takeoff Distance + Total Distance) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Card: Plafond atteint
                val displayedCeiling = if (varioData.isFlightActive) {
                    varioData.maxAltitudeM
                } else {
                    maxOf(varioData.maxAltitudeM, varioData.altitudeM)
                }
                StatCard(
                    title = "Plafond",
                    value = formatAlt(displayedCeiling),
                    unit = "m",
                    modifier = Modifier.weight(1f)
                )

                // Card: Distance au déco
                val (distVal, distUnit) = formatDistanceParts(varioData.distanceToTakeoffM)
                StatCard(
                    title = "Dist. déco",
                    value = distVal,
                    unit = distUnit,
                    modifier = Modifier.weight(1f)
                )

                // Card: Distance totale parcourue
                val (totalDistVal, totalDistUnit) = formatDistanceParts(varioData.totalDistanceTraveledM)
                StatCard(
                    title = "Dist. totale",
                    value = totalDistVal,
                    unit = totalDistUnit,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Flight Action Button & Map Mode Switch ───────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (varioData.isFlightActive) {
                            onStopFlight()
                        } else {
                            handleStartClick()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (varioData.isFlightActive) Color(0xFFEF4444) else Color(0xFF4ADE80)
                    )
                ) {
                    Text(
                        text = if (varioData.isFlightActive) "Arrêter le vol" else "Démarrer le vol",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (varioData.isFlightActive) Color.White else Color(0xFF0B1710)
                    )
                }

                Button(
                    onClick = onOpenMap,
                    modifier = Modifier.height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF38BDF8))
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = "🗺", fontSize = 18.sp)
                        Text(text = "Carte", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF38BDF8))
                    }
                }
            }
        }

        // ── Full-featured Debug Modal ────────────────────────────────────
        if (showDebugModal) {
            DebugModal(
                varioData = varioData,
                onDismiss = { showDebugModal = false },
                onRequestUsbPermission = onRequestUsbPermission,
                onSetBaudRate = onSetBaudRate,
                onTestAudio = onTestAudio,
                onStopAudioTest = onStopAudioTest
            )
        }
    }
}

// ── Header Bar ───────────────────────────────────────────────────────────────

@Composable
private fun HeaderBar(
    gpsReady: Boolean,
    sensorMode: SensorMode,
    isUsbConnected: Boolean,
    isUsbScanning: Boolean,
    flightDurationSec: Long,
    isMuted: Boolean,
    onReconnectUsb: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenMap: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: GPS indicator & Sensor Mode Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        color = if (gpsReady) Color(0xFF22C55E) else Color(0xFF64748B),
                        shape = CircleShape
                    )
            )
            Text(
                text = if (gpsReady) "GPS prêt" else "Attente",
                fontSize = 13.sp,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Medium
            )
            SensorModeBadge(mode = sensorMode, isScanning = isUsbScanning)
        }

        // Right: Flight timer, USB Reconnect button, Debug button, and Audio mute button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatDuration(flightDurationSec),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (flightDurationSec > 0) Color(0xFFCBD5E1) else Color(0xFF8B9CB0)
            )

            UsbStatusButton(
                isConnected = isUsbConnected,
                isScanning = isUsbScanning,
                onReconnect = onReconnectUsb
            )

            DebugStatusButton(
                onOpenDebug = onOpenDebug
            )

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color(0xFF131926), shape = RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF0284C7), shape = RoundedCornerShape(10.dp))
                    .clickable { onOpenMap() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🗺", fontSize = 17.sp)
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color(0xFF131926), shape = RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF1E2A3A), shape = RoundedCornerShape(10.dp))
                    .clickable { onToggleMute() },
                contentAlignment = Alignment.Center
            ) {
                SpeakerIcon(
                    isMuted = isMuted,
                    modifier = Modifier.size(20.dp),
                    tint = if (isMuted) Color(0xFFEF4444) else Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
private fun SensorModeBadge(
    mode: SensorMode,
    isScanning: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isBaro = mode == SensorMode.BARO_AND_GPS
    val text = when {
        isBaro -> "Baro + GPS"
        isScanning -> "Scan USB…"
        else -> "GPS seul"
    }
    val bgColor = when {
        isBaro -> Color(0xFF14532D).copy(alpha = 0.4f)
        isScanning -> Color(0xFF0369A1).copy(alpha = 0.4f)
        else -> Color(0xFF1E293B)
    }
    val textColor = when {
        isBaro -> Color(0xFF4ADE80)
        isScanning -> Color(0xFF38BDF8)
        else -> Color(0xFF93C5FD)
    }
    val borderColor = when {
        isBaro -> Color(0xFF22C55E).copy(alpha = 0.3f)
        isScanning -> Color(0xFF0284C7).copy(alpha = 0.5f)
        else -> Color(0xFF3B82F6).copy(alpha = 0.3f)
    }

    Box(
        modifier = modifier
            .background(bgColor, shape = RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

@Composable
private fun UsbStatusButton(
    isConnected: Boolean,
    isScanning: Boolean = false,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        isConnected -> Color(0xFF166534)
        isScanning -> Color(0xFF0284C7)
        else -> Color(0xFF1E2A3A)
    }
    val iconTint = when {
        isConnected -> Color(0xFF4ADE80)
        isScanning -> Color(0xFF38BDF8)
        else -> Color(0xFF94A3B8)
    }
    Box(
        modifier = modifier
            .size(38.dp)
            .background(Color(0xFF131926), shape = RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, shape = RoundedCornerShape(10.dp))
            .clickable { onReconnect() },
        contentAlignment = Alignment.Center
    ) {
        UsbIcon(
            modifier = Modifier.size(20.dp),
            tint = iconTint
        )
    }
}

@Composable
private fun DebugStatusButton(
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .background(Color(0xFF131926), shape = RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF1E2A3A), shape = RoundedCornerShape(10.dp))
            .clickable { onOpenDebug() },
        contentAlignment = Alignment.Center
    ) {
        TerminalIcon(
            modifier = Modifier.size(20.dp),
            tint = Color(0xFF94A3B8)
        )
    }
}

@Composable
private fun TerminalIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF94A3B8)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Outer terminal window
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.18f),
            size = Size(w * 0.76f, h * 0.64f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = 1.6.dp.toPx())
        )

        // Chevron `>`
        val prompt = Path().apply {
            moveTo(w * 0.28f, h * 0.38f)
            lineTo(w * 0.44f, h * 0.50f)
            lineTo(w * 0.28f, h * 0.62f)
        }
        drawPath(
            path = prompt,
            color = tint,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Underscore cursor `_`
        drawLine(
            color = tint,
            start = Offset(w * 0.50f, h * 0.62f),
            end = Offset(w * 0.68f, h * 0.62f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun UsbIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF94A3B8)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Central vertical trunk
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.85f),
            end = Offset(w * 0.5f, h * 0.28f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round
        )
        // Bottom circle
        drawCircle(
            color = tint,
            radius = 2.dp.toPx(),
            center = Offset(w * 0.5f, h * 0.85f)
        )
        // Left branch
        val leftBranch = Path().apply {
            moveTo(w * 0.5f, h * 0.65f)
            lineTo(w * 0.24f, h * 0.50f)
            lineTo(w * 0.24f, h * 0.38f)
        }
        drawPath(
            path = leftBranch,
            color = tint,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(
            color = tint,
            radius = 1.8.dp.toPx(),
            center = Offset(w * 0.24f, h * 0.36f)
        )

        // Right branch
        val rightBranch = Path().apply {
            moveTo(w * 0.5f, h * 0.54f)
            lineTo(w * 0.76f, h * 0.44f)
            lineTo(w * 0.76f, h * 0.36f)
        }
        drawPath(
            path = rightBranch,
            color = tint,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.76f - 1.8.dp.toPx(), h * 0.32f - 1.8.dp.toPx()),
            size = Size(3.6.dp.toPx(), 3.6.dp.toPx())
        )

        // Top triangle/arrow
        val topArrow = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.36f, h * 0.28f)
            lineTo(w * 0.64f, h * 0.28f)
            close()
        }
        drawPath(path = topArrow, color = tint)
    }
}

// ── Vertical Variometer Ladder Gauge ─────────────────────────────────────────

@Composable
private fun VarioLadderGauge(
    vz: Float,
    modifier: Modifier = Modifier,
    maxVz: Float = 5.0f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f
        val railX = w * 0.82f

        // Draw vertical baseline
        drawLine(
            color = Color(0xFF1E293B),
            start = Offset(railX, 0f),
            end = Offset(railX, h),
            strokeWidth = 2.dp.toPx()
        )

        // Draw active level bar along the rail
        val clampedVz = vz.coerceIn(-maxVz, maxVz)
        val barHeight = (clampedVz / maxVz) * centerY
        val activeColor = when {
            vz > 0.2f -> Color(0xFF4ADE80)
            vz < -1.5f -> Color(0xFFF87171)
            else -> Color(0xFF334155)
        }

        if (barHeight != 0f) {
            val barTop = if (barHeight > 0) centerY - barHeight else centerY
            val barBottom = if (barHeight > 0) centerY else centerY - barHeight
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(railX - 2.5.dp.toPx(), barTop),
                size = Size(5.dp.toPx(), (barBottom - barTop).coerceAtLeast(2.dp.toPx())),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }

        // Draw horizontal tick marks (10 intervals from +maxVz to -maxVz)
        val numSteps = 10
        for (i in 0..numSteps) {
            val stepVz = maxVz - (i * (2 * maxVz / numSteps))
            val y = (i / numSteps.toFloat()) * h
            val isCenter = i == numSteps / 2
            val isCovered = (vz > 0.2f && stepVz in 0f..vz) || (vz < -1.5f && stepVz in vz..0f)

            val tickWidth = if (isCenter) w * 0.75f else w * 0.45f
            val tickColor = when {
                isCovered -> activeColor
                isCenter -> Color(0xFF64748B)
                else -> Color(0xFF1E293B)
            }
            val strokeW = if (isCenter) 2.5.dp.toPx() else 1.5.dp.toPx()

            drawLine(
                color = tickColor,
                start = Offset(railX - tickWidth, y),
                end = Offset(railX, y),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }
}

// ── Speaker / Mute Icon (Zero Allocation Canvas) ─────────────────────────────

@Composable
private fun SpeakerIcon(
    isMuted: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF94A3B8)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Speaker cone path
        val bodyPath = Path().apply {
            moveTo(w * 0.15f, h * 0.35f)
            lineTo(w * 0.35f, h * 0.35f)
            lineTo(w * 0.62f, h * 0.15f)
            lineTo(w * 0.62f, h * 0.85f)
            lineTo(w * 0.35f, h * 0.65f)
            lineTo(w * 0.15f, h * 0.65f)
            close()
        }
        drawPath(
            path = bodyPath,
            color = tint,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        if (!isMuted) {
            // Sound wave arc
            val arcRect = Rect(w * 0.42f, h * 0.28f, w * 0.85f, h * 0.72f)
            drawArc(
                color = tint,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = arcRect.topLeft,
                size = arcRect.size,
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )
        } else {
            // Mute cross
            drawLine(
                color = tint,
                start = Offset(w * 0.72f, h * 0.38f),
                end = Offset(w * 0.92f, h * 0.62f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = tint,
                start = Offset(w * 0.92f, h * 0.38f),
                end = Offset(w * 0.72f, h * 0.62f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

// ── Stat Card Component ──────────────────────────────────────────────────────

@Composable
private fun StatCard(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF131926), shape = RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF1B2436), shape = RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = Color(0xFF8B9CB0),
            fontWeight = FontWeight.Normal,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 24.sp
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    fontSize = 14.sp,
                    color = Color(0xFF8B9CB0),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

// ── Formatting Utilities ─────────────────────────────────────────────────────

private fun formatVz(vz: Float): String {
    val sign = if (vz >= 0) "+" else ""
    return "$sign%.1f".format(vz)
}

private fun formatAlt(alt: Float): String {
    return "%.0f".format(alt)
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}

private fun formatDistanceParts(distM: Float?): Pair<String, String> {
    if (distM == null) return Pair("0,0", "km")
    return if (distM < 1000f) {
        Pair("${distM.toInt()}", "m")
    } else {
        Pair("%.1f".format(distM / 1000f).replace('.', ','), "km")
    }
}
