package com.vario.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that maintains the entire variometer pipeline:
 *
 * 1. Acquires a [PARTIAL_WAKE_LOCK][PowerManager.PARTIAL_WAKE_LOCK] to keep the CPU alive
 *    when the screen is off.
 * 2. Opens the USB serial port (CDC/ACM) via `usb-serial-for-android` at configurable baud rate (default 115200).
 * 3. Reads incoming bytes and parses **LK8EX1** sentences with a zero-allocation state machine.
 * 4. Extracts native Vz from field 2 (cm/s → m/s) and computes calibrated barometric altitude.
 * 5. Immediately updates [VarioAudioEngine.currentVz] (volatile write — no lock).
 * 6. Publishes a [VarioData] snapshot on [dataFlow] for the UI layer.
 * 7. Requests GPS location updates (1 Hz) as single source of truth to calibrate QNH at takeoff,
 *    estimate GPS vertical speed, and track takeoff distance.
 *
 * ## Zero-allocation contract (fast path)
 * The [parseByte] and [onSentenceComplete] methods are on the fast path.
 * They must **never** create JVM objects (no String, no Array, no boxing).
 * All parser state lives in pre-allocated primitive fields.
 */
class VarioService : Service(), Lk8ex1Parser.Listener {

    // ── Public API ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VarioService"
        private const val CHANNEL_ID = "vario_channel"
        private const val NOTIFICATION_ID = 1
        private const val USB_READ_BUFFER_SIZE = 256
        private const val RECENT_RAW_BUFFER_SIZE = 256
        private const val WAKELOCK_TAG = "VarioAppli::VarioService"

        const val ACTION_RESET_TAKEOFF = "com.vario.app.ACTION_RESET_TAKEOFF"
        const val ACTION_START_FLIGHT = "com.vario.app.ACTION_START_FLIGHT"
        const val ACTION_STOP_FLIGHT = "com.vario.app.ACTION_STOP_FLIGHT"
        const val ACTION_TOGGLE_MUTE = "com.vario.app.ACTION_TOGGLE_MUTE"
        const val ACTION_RECONNECT_USB = "com.vario.app.ACTION_RECONNECT_USB"
        const val ACTION_DISCONNECT_USB = "com.vario.app.ACTION_DISCONNECT_USB"
        const val ACTION_SET_BAUD_RATE = "com.vario.app.ACTION_SET_BAUD_RATE"
        const val EXTRA_BAUD_RATE = "com.vario.app.EXTRA_BAUD_RATE"
        const val ACTION_USB_PERMISSION = "com.vario.app.USB_PERMISSION"
        const val ACTION_REQUEST_USB_PERMISSION = "com.vario.app.ACTION_REQUEST_USB_PERMISSION"
        const val ACTION_TEST_TONE = "com.vario.app.ACTION_TEST_TONE"
        const val ACTION_STOP_TEST_TONE = "com.vario.app.ACTION_STOP_TEST_TONE"
        const val EXTRA_TEST_VZ = "com.vario.app.EXTRA_TEST_VZ"
        const val USB_SCAN_TIMEOUT_SEC = 30

        val SUPPORTED_BAUD_RATES = intArrayOf(115200, 57600, 38400, 19200, 9600)

        @Volatile
        var instance: VarioService? = null
            private set

        /**
         * Observable data flow for the UI. Lives in the companion so that
         * [MainActivity] can observe it without holding a reference to the service.
         */
        private val _dataFlow = MutableStateFlow(VarioData())
        val dataFlow: StateFlow<VarioData> = _dataFlow.asStateFlow()

        /**
         * Updates GPS coordinates and standby altitude during pre-flight standby.
         */
        fun updateStandbyLocation(loc: Location) {
            val current = _dataFlow.value
            val alt = loc.altitude.toFloat()
            _dataFlow.value = current.copy(
                gpsFixAcquired = true,
                latitude = loc.latitude,
                longitude = loc.longitude,
                gpsAltitudeM = alt,
                altitudeM = if (!current.isUsbConnected) alt else current.altitudeM
            )
        }

        /**
         * Updates USB connection state and sensor mode in dataFlow.
         */
        fun setUsbStatus(connected: Boolean, deviceName: String?) {
            val current = _dataFlow.value
            val mode = if (connected) SensorMode.BARO_AND_GPS else SensorMode.GPS_ONLY
            _dataFlow.value = current.copy(
                isUsbConnected = connected,
                sensorMode = mode,
                usbDeviceName = deviceName,
                usbPortOpen = connected,
                isUsbScanning = if (connected) false else current.isUsbScanning
            )
            instance?.updateNotification()
        }

        /**
         * Resolves the effective pressure in Pascals, converting hPa if in range 300..1200.
         */
        fun resolveEffectivePressurePa(rawPressure: Long): Long {
            return if (rawPressure in 300..1200) rawPressure * 100 else rawPressure
        }

        /**
         * Checks if the given pressure in Pa is within valid atmospheric range (30000..115000 Pa).
         */
        fun isValidPressurePa(pressurePa: Long): Boolean {
            return pressurePa in 30000L..115000L
        }

        /**
         * Pure multi-tier altitude arbitration logic.
         */
        fun arbitrateAltitude(
            pressurePa: Long,
            rawAltitudeM: Long,
            currentQnhPa: Double,
            availableGpsAlt: Float?,
            previousAltitudeM: Float
        ): Float {
            val effectivePressurePa = resolveEffectivePressurePa(pressurePa)
            val isValidPressure = isValidPressurePa(effectivePressurePa)

            var calculatedAlt = if (isValidPressure) {
                VarioMath.pressureToAltitude(effectivePressurePa, currentQnhPa)
            } else if (rawAltitudeM != 99999L && rawAltitudeM > 0L) {
                rawAltitudeM.toFloat()
            } else {
                0f
            }

            if (calculatedAlt <= 0f) {
                val gpsAlt = availableGpsAlt ?: 0f
                if (gpsAlt > 0f) {
                    calculatedAlt = gpsAlt
                } else if (previousAltitudeM > 0f) {
                    calculatedAlt = previousAltitudeM
                }
            }
            return calculatedAlt
        }

        /**
         * Resolves initial altitude/ceiling when starting a flight.
         */
        fun resolveInitialFlightAltitude(
            currentAltitudeM: Float,
            gpsAltitudeM: Float,
            lastLocationAltitudeM: Float?
        ): Float {
            return sequenceOf(
                currentAltitudeM,
                gpsAltitudeM,
                lastLocationAltitudeM ?: 0f
            ).firstOrNull { it > 0f } ?: 0f
        }

        /**
         * Accumulates distance traveled only when a flight is actively in progress.
         */
        fun accumulateDistanceTraveled(
            isFlightActive: Boolean,
            currentTotalM: Float,
            prevLoc: Location?,
            newLoc: Location
        ): Float {
            if (!isFlightActive || prevLoc == null) {
                return currentTotalM
            }
            return currentTotalM + prevLoc.distanceTo(newLoc)
        }

        /**
         * Computes distance to takeoff during active flight, or retains the current value when flight is ended/inactive.
         */
        fun computeDistanceToTakeoff(
            isFlightActive: Boolean,
            currentDistanceToTakeoffM: Float?,
            loc: Location,
            takeoffLocation: Location?
        ): Float? {
            if (!isFlightActive) {
                return currentDistanceToTakeoffM
            }
            return if (takeoffLocation != null) {
                loc.distanceTo(takeoffLocation)
            } else {
                currentDistanceToTakeoffM
            }
        }

        /**
         * Checks whether the active USB serial port is currently open.
         */
        val isPortOpen: Boolean
            get() = instance?.isSerialPortOpen() == true

        /**
         * Computes vertical speed (Vz) from GPS altitude difference over time using an
         * exponential moving average (EMA) filter, with raw speed clamped to [-20f, 20f] m/s.
         */
        fun computeGpsVz(
            currentAlt: Double,
            lastAlt: Double,
            dtSec: Float,
            previousVz: Float
        ): Float {
            if (lastAlt.isNaN() || dtSec !in 0.1f..10.0f) {
                return previousVz
            }
            val rawVz = ((currentAlt - lastAlt) / dtSec).toFloat().coerceIn(-20f, 20f)
            return if (previousVz == 0f) rawVz else (previousVz * 0.65f + rawVz * 0.35f)
        }

        /**
         * Derives vertical speed (Vz) from barometric altitude difference over time
         * when the hardware sensor does not compute Vz (varioCmS == 0L or 99999L),
         * with raw speed clamped to [-20f, 20f] m/s and exponential moving average (EMA) smoothing.
         */
        fun computeBaroVz(
            calculatedAlt: Float,
            lastBaroAltM: Float,
            dtSec: Float,
            previousVz: Float = 0f
        ): Float {
            if (lastBaroAltM.isNaN() || dtSec !in 0.05f..3.0f) {
                return previousVz
            }
            val rawVz = ((calculatedAlt - lastBaroAltM) / dtSec).coerceIn(-20f, 20f)
            return if (previousVz == 0f) rawVz else (previousVz * 0.65f + rawVz * 0.35f)
        }
    }

    // ── Private state ────────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioEngine: VarioAudioEngine? = null
    private var serialPort: UsbSerialPort? = null
    private var usbJob: Job? = null
    private var usbScanJob: Job? = null
    private var serviceScope: CoroutineScope? = null
    private var tickerJob: Job? = null

    // ── GPS & Calibration state ──────────────────────────────────────────────

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var takeoffLocation: Location? = null
    private var lastKnownLocation: Location? = null
    private var initialGpsAltitudeM: Float? = null
    private var currentQnhPa: Double = VarioMath.P0_PA
    private var isCalibrated: Boolean = false
    private var lastRawPressurePa: Long = 0L
    private var totalDistanceTraveledM: Float = 0f

    // ── GPS-only Vz state (zero-allocation primitives) ─────────────────────────
    private var lastGpsAltitudeM: Double = Double.NaN
    private var lastGpsTimeMs: Long = 0L
    private var smoothedGpsVz: Float = 0f
    private var lastGpsFixTimeMs: Long = 0L
    private var gpsAccuracyM: Float = 0f
    private var gpsVerticalAccuracyM: Float = 0f
    private var isGpsAvailable: Boolean = false

    // ── Barometric altitude fallback Vz state ────────────────────────────────
    private var lastBaroAltM: Float = Float.NaN
    private var lastBaroTimeMs: Long = 0L
    private var smoothedBaroVz: Float = 0f
    private var lastDiagLogTimeMs: Long = 0L

    fun isSerialPortOpen(): Boolean = serialPort?.isOpen == true

    // ── Flight session state ─────────────────────────────────────────────────

    private var isFlightActive: Boolean = false
    private var flightDurationSec: Long = 0L
    private var flightStartTimeMs: Long = 0L
    private var maxAltitudeM: Float = 0f

    // ── USB Serial Diagnostics & Fast-Path Counters ──────────────────────────

    private var currentBaudRate: Int = 115200
    private var totalBytesRead: Long = 0L
    private val usbReadBuffer = ByteArray(USB_READ_BUFFER_SIZE)
    private val recentRawBuffer = ByteArray(RECENT_RAW_BUFFER_SIZE)
    private var recentRawIndex = 0

    private val parser = Lk8ex1Parser(this)

    // ── Broadcast Receivers ──────────────────────────────────────────────────

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (granted) {
                    DebugLogger.log(TAG, "USB permission granted for ${device?.deviceName}", DebugLogger.Level.INFO)
                    _dataFlow.value = _dataFlow.value.copy(usbPermissionGranted = true)
                    startUsbScan(timeoutSec = 10)
                } else {
                    DebugLogger.log(TAG, "USB permission denied by user", DebugLogger.Level.WARN)
                    _dataFlow.value = _dataFlow.value.copy(usbPermissionGranted = false, isUsbScanning = false)
                    updateNotification()
                }
            }
        }
    }

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        acquireWakeLock()
        createNotificationChannel()
        startForegroundSafely()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbPermissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val engine = VarioAudioEngine()
        engine.isFlightActive = false
        engine.start()
        audioEngine = engine

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        startTicker()
        startLocationUpdates()
        startUsbScan(timeoutSec = USB_SCAN_TIMEOUT_SEC)

        DebugLogger.log(TAG, "VarioService started", DebugLogger.Level.INFO)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope?.launch {
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val fixAge = if (lastGpsFixTimeMs > 0L) {
                    ((now - lastGpsFixTimeMs) / 1000f).coerceAtLeast(0f)
                } else {
                    -1f
                }

                if (isFlightActive) {
                    flightDurationSec++
                }

                val current = _dataFlow.value
                _dataFlow.value = current.copy(
                    lastGpsFixAgeSec = fixAge,
                    flightDurationSec = flightDurationSec
                )
            }
        }
    }

    private fun startForegroundSafely() {
        try {
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                foregroundServiceType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            DebugLogger.log(TAG, "Foreground start failed: ${e.message}", DebugLogger.Level.ERROR)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FLIGHT, ACTION_RESET_TAKEOFF -> {
                isFlightActive = true
                flightDurationSec = 0L
                flightStartTimeMs = System.currentTimeMillis()
                totalDistanceTraveledM = 0f
                takeoffLocation = lastKnownLocation
                GpxTrackManager.clearCurrentTrack()

                val currentAlt = sequenceOf(
                    _dataFlow.value.altitudeM,
                    _dataFlow.value.gpsAltitudeM,
                    lastKnownLocation?.altitude?.toFloat() ?: 0f
                ).firstOrNull { it > 0f } ?: 0f
                maxAltitudeM = currentAlt

                smoothedGpsVz = 0f
                lastGpsAltitudeM = Double.NaN
                lastGpsTimeMs = 0L
                lastBaroAltM = Float.NaN
                lastBaroTimeMs = 0L
                smoothedBaroVz = 0f
                lastDiagLogTimeMs = 0L

                acquireWakeLock()
                audioEngine?.isFlightActive = true

                val currentLoc = lastKnownLocation
                val dist = if (currentLoc != null && takeoffLocation != null) currentLoc.distanceTo(takeoffLocation!!) else 0f
                _dataFlow.value = _dataFlow.value.copy(
                    isFlightActive = true,
                    flightDurationSec = 0L,
                    maxAltitudeM = maxAltitudeM,
                    altitudeM = if (_dataFlow.value.altitudeM > 0f) _dataFlow.value.altitudeM else maxAltitudeM,
                    totalDistanceTraveledM = 0f,
                    distanceToTakeoffM = dist
                )
                DebugLogger.log(TAG, "Flight session started (initial alt: ${maxAltitudeM}m)", DebugLogger.Level.INFO)
            }
            ACTION_STOP_FLIGHT -> {
                isFlightActive = false
                audioEngine?.isFlightActive = false
                audioEngine?.currentVz = 0f
                smoothedGpsVz = 0f
                smoothedBaroVz = 0f
                _dataFlow.value = _dataFlow.value.copy(
                    isFlightActive = false,
                    vzMs = 0f
                )
                val duration = flightDurationSec
                val maxAlt = maxAltitudeM
                val dist = totalDistanceTraveledM
                val startMs = flightStartTimeMs
                serviceScope?.launch(Dispatchers.IO) {
                    val file = GpxTrackManager.saveCurrentTrack(
                        context = this@VarioService,
                        startTimeMs = startMs,
                        durationSec = duration,
                        maxAltitudeM = maxAlt,
                        totalDistanceM = dist
                    )
                    if (file != null) {
                        DebugLogger.log(TAG, "GPX track automatically saved: ${file.name}", DebugLogger.Level.INFO)
                    }
                }
                DebugLogger.log(TAG, "Flight session stopped", DebugLogger.Level.INFO)
            }
            ACTION_TOGGLE_MUTE -> {
                val engine = audioEngine
                if (engine != null) {
                    engine.isMuted = !engine.isMuted
                    _dataFlow.value = _dataFlow.value.copy(isMuted = engine.isMuted)
                    DebugLogger.log(TAG, "Audio mute toggled: ${engine.isMuted}", DebugLogger.Level.INFO)
                }
            }
            ACTION_RECONNECT_USB -> {
                DebugLogger.log(TAG, "Manual USB reconnection requested", DebugLogger.Level.INFO)
                startUsbScan(timeoutSec = USB_SCAN_TIMEOUT_SEC)
            }
            ACTION_DISCONNECT_USB -> {
                DebugLogger.log(TAG, "USB disconnected", DebugLogger.Level.INFO)
                usbScanJob?.cancel()
                usbScanJob = null
                usbJob?.cancel()
                usbJob = null
                closeUsb()
                setUsbStatus(false, null)
                _dataFlow.value = _dataFlow.value.copy(isUsbScanning = false, usbPortOpen = false)
                updateNotification()
            }
            ACTION_SET_BAUD_RATE -> {
                val newBaud = intent.getIntExtra(EXTRA_BAUD_RATE, 115200)
                if (newBaud in SUPPORTED_BAUD_RATES) {
                    currentBaudRate = newBaud
                    DebugLogger.log(TAG, "Setting baud rate to $newBaud", DebugLogger.Level.INFO)
                    try {
                        serialPort?.setParameters(newBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                        serialPort?.dtr = true
                        serialPort?.rts = true
                    } catch (e: Exception) {
                        DebugLogger.log(TAG, "Failed setting baud rate on active port: ${e.message}", DebugLogger.Level.ERROR)
                    }
                    _dataFlow.value = _dataFlow.value.copy(currentBaudRate = newBaud)
                }
            }
            ACTION_REQUEST_USB_PERMISSION -> {
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val drivers = findSerialDrivers(usbManager)
                if (drivers.isNotEmpty()) {
                    requestUsbPermissionForDevice(drivers[0].device)
                } else {
                    DebugLogger.log(TAG, "No USB device found to request permission", DebugLogger.Level.WARN)
                }
            }
            ACTION_TEST_TONE -> {
                val vz = intent.getFloatExtra(EXTRA_TEST_VZ, 2.5f)
                audioEngine?.testTone(vz)
                DebugLogger.log(TAG, "Audio test tone started ($vz m/s)", DebugLogger.Level.DEBUG)
            }
            ACTION_STOP_TEST_TONE -> {
                audioEngine?.stopTestTone()
                DebugLogger.log(TAG, "Audio test tone stopped", DebugLogger.Level.DEBUG)
            }
        }

        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering usbPermissionReceiver", e)
        }
        stopLocationUpdates()
        tickerJob?.cancel()
        tickerJob = null
        usbScanJob?.cancel()
        usbScanJob = null
        usbJob?.cancel()
        usbJob = null
        isFlightActive = false
        flightDurationSec = 0L
        maxAltitudeM = 0f
        takeoffLocation = null
        lastKnownLocation = null
        initialGpsAltitudeM = null
        isCalibrated = false
        currentQnhPa = VarioMath.P0_PA
        totalDistanceTraveledM = 0f
        lastGpsAltitudeM = Double.NaN
        lastGpsTimeMs = 0L
        smoothedGpsVz = 0f
        lastBaroAltM = Float.NaN
        lastBaroTimeMs = 0L
        serviceScope?.cancel()
        serviceScope = null
        closeUsb()
        audioEngine?.stop()
        audioEngine = null
        releaseWakeLock()
        _dataFlow.value = VarioData()
        DebugLogger.log(TAG, "VarioService destroyed", DebugLogger.Level.INFO)
        super.onDestroy()
    }

    // ── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("WakelockTimeout")
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
                DebugLogger.log(TAG, "Partial WakeLock acquired", DebugLogger.Level.DEBUG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception acquiring WakeLock", e)
            DebugLogger.log(TAG, "WakeLock acquire error: ${e.message}", DebugLogger.Level.ERROR)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLogger.log(TAG, "Partial WakeLock released", DebugLogger.Level.DEBUG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception releasing WakeLock", e)
        }
        wakeLock = null
    }

    // ── USB serial ───────────────────────────────────────────────────────────

    private fun requestUsbPermissionForDevice(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            flags
        )
        DebugLogger.log(TAG, "Requesting USB permission for ${device.deviceName} (VID: 0x${Integer.toHexString(device.vendorId)})", DebugLogger.Level.INFO)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun findSerialDrivers(usbManager: UsbManager): List<UsbSerialDriver> {
        val defaultProber = UsbSerialProber.getDefaultProber()
        val drivers = defaultProber.findAllDrivers(usbManager)
        if (drivers.isNotEmpty()) {
            return drivers
        }
        // Fallback: If device is connected but not recognized by default prober table, probe as CDC-ACM
        val customTable = ProbeTable()
        for (device in usbManager.deviceList.values) {
            val isCdc = device.deviceClass == UsbConstants.USB_CLASS_COMM ||
                (0 until device.interfaceCount).any { i ->
                    val iface = device.getInterface(i)
                    iface.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                        iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
                }
            if (isCdc) {
                customTable.addProduct(device.vendorId, device.productId, CdcAcmSerialDriver::class.java)
            }
        }
        return UsbSerialProber(customTable).findAllDrivers(usbManager)
    }

    /**
     * Scans for USB serial devices for [timeoutSec] seconds.
     * If no device is detected within the window, the scan terminates and the app
     * remains in GPS-only mode until a manual trigger or USB device attachment.
     */
    fun startUsbScan(timeoutSec: Int = USB_SCAN_TIMEOUT_SEC) {
        if (serialPort != null && serialPort?.isOpen == true) {
            DebugLogger.log(TAG, "USB port already open; scan skipped", DebugLogger.Level.DEBUG)
            return
        }
        usbScanJob?.cancel()
        usbJob?.cancel()
        closeUsb()

        _dataFlow.value = _dataFlow.value.copy(isUsbScanning = true)
        updateNotification()

        usbScanJob = serviceScope?.launch {
            DebugLogger.log(TAG, "Starting USB scan (timeout: ${timeoutSec}s)", DebugLogger.Level.INFO)
            val startTimeMs = SystemClock.elapsedRealtime()
            val timeoutMs = timeoutSec * 1000L
            var connected = false

            while (isActive && (SystemClock.elapsedRealtime() - startTimeMs) < timeoutMs) {
                if (serialPort?.isOpen == true) {
                    connected = true
                    break
                }
                try {
                    val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                    val drivers = findSerialDrivers(usbManager)
                    if (drivers.isNotEmpty()) {
                        val driver = drivers[0]
                        val device = driver.device
                        val vid = device.vendorId
                        val pid = device.productId
                        val devName = try {
                            device.productName ?: device.deviceName
                        } catch (e: Exception) {
                            device.deviceName
                        }

                        _dataFlow.value = _dataFlow.value.copy(
                            usbVid = vid,
                            usbPid = pid,
                            usbDeviceName = devName
                        )

                        if (!usbManager.hasPermission(device)) {
                            DebugLogger.log(TAG, "USB device found ($devName), requesting permission...", DebugLogger.Level.INFO)
                            _dataFlow.value = _dataFlow.value.copy(
                                usbPermissionGranted = false,
                                usbPortOpen = false
                            )
                            requestUsbPermissionForDevice(device)
                            delay(1000L)
                            continue
                        }

                        _dataFlow.value = _dataFlow.value.copy(usbPermissionGranted = true)

                        val connection = usbManager.openDevice(device)
                        if (connection != null) {
                            val port = driver.ports[0]
                            port.open(connection)
                            port.setParameters(
                                currentBaudRate,
                                8,
                                UsbSerialPort.STOPBITS_1,
                                UsbSerialPort.PARITY_NONE
                            )
                            try {
                                port.dtr = true
                                port.rts = true
                            } catch (e: Exception) {
                                DebugLogger.log(TAG, "Failed setting DTR/RTS: ${e.message}", DebugLogger.Level.WARN)
                            }
                            serialPort = port
                            setUsbStatus(true, devName)
                            _dataFlow.value = _dataFlow.value.copy(
                                usbPortOpen = true,
                                usbPermissionGranted = true,
                                isUsbScanning = false,
                                currentBaudRate = currentBaudRate,
                                usbVid = vid,
                                usbPid = pid
                            )
                            DebugLogger.log(TAG, "USB port opened on $devName at $currentBaudRate baud", DebugLogger.Level.INFO)
                            updateNotification()
                            connected = true
                            startReadLoop(port)
                            break
                        } else {
                            DebugLogger.log(TAG, "USB openDevice failed", DebugLogger.Level.ERROR)
                        }
                    }
                } catch (e: Exception) {
                    DebugLogger.log(TAG, "Error checking USB drivers: ${e.message}", DebugLogger.Level.WARN)
                }

                delay(1000L)
            }

            _dataFlow.value = _dataFlow.value.copy(isUsbScanning = false)
            updateNotification()

            if (!connected && (serialPort == null || serialPort?.isOpen != true)) {
                DebugLogger.log(TAG, "USB scan stopped: no device detected after ${timeoutSec}s. Tap USB button to retry.", DebugLogger.Level.INFO)
                setUsbStatus(false, null)
                _dataFlow.value = _dataFlow.value.copy(
                    usbPortOpen = false,
                    isUsbScanning = false
                )
            }
        }
    }

    private fun startReadLoop(port: UsbSerialPort) {
        usbJob?.cancel()
        usbJob = serviceScope?.launch {
            readLoop(port)
        }
    }

    /**
     * Continuously read bytes from the serial port and feed them to the
     * zero-allocation parser. Runs on [Dispatchers.IO].
     */
    private suspend fun readLoop(port: UsbSerialPort) {
        var consecutiveErrors = 0
        while (serviceScope?.isActive == true) {
            try {
                val bytesRead = port.read(usbReadBuffer, 100) // 100 ms timeout
                consecutiveErrors = 0
                if (bytesRead > 0) {
                    totalBytesRead += bytesRead
                    for (i in 0 until bytesRead) {
                        recentRawBuffer[recentRawIndex] = usbReadBuffer[i]
                        recentRawIndex = (recentRawIndex + 1) and (RECENT_RAW_BUFFER_SIZE - 1)
                    }
                    parser.parseBytes(usbReadBuffer, bytesRead)
                }
            } catch (e: Exception) {
                consecutiveErrors++
                DebugLogger.log(TAG, "USB read loop error ($consecutiveErrors/5): ${e.message}", DebugLogger.Level.WARN)
                if (consecutiveErrors >= 5) {
                    DebugLogger.log(TAG, "USB read loop error: ${e.message}", DebugLogger.Level.ERROR)
                    closeUsb()
                    setUsbStatus(false, null)
                    _dataFlow.value = _dataFlow.value.copy(usbPortOpen = false)
                    break
                }
                delay(250L)
            }
        }

        if (consecutiveErrors >= 5) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbManager.deviceList.isNotEmpty() && serviceScope?.isActive == true) {
                DebugLogger.log(TAG, "USB device still connected to system; attempting auto-reconnect in 1.5s", DebugLogger.Level.INFO)
                delay(1500L)
                if (serviceScope?.isActive == true && (serialPort == null || serialPort?.isOpen != true)) {
                    startUsbScan(timeoutSec = 10)
                }
            }
        }
    }

    private fun closeUsb() {
        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing USB port", e)
        }
        serialPort = null
    }

    // ── Location & Takeoff distance (1 Hz) ────────────────────────────────────

    private fun startLocationUpdates() {
        // Guard: register only once
        if (locationCallback != null) {
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLogger.log(TAG, "Location permission not granted; GPS disabled", DebugLogger.Level.WARN)
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient = client

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                handleNewLocation(loc)
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                val available = availability.isLocationAvailable
                isGpsAvailable = available
                DebugLogger.log(TAG, "GPS availability updated: $available", DebugLogger.Level.INFO)
                _dataFlow.value = _dataFlow.value.copy(isGpsAvailable = available)
            }
        }
        locationCallback = callback

        try {
            client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
            DebugLogger.log(TAG, "GPS location updates registered (1 Hz interval)", DebugLogger.Level.INFO)
        } catch (e: SecurityException) {
            DebugLogger.log(TAG, "SecurityException requesting location updates: ${e.message}", DebugLogger.Level.ERROR)
            locationCallback = null
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        locationCallback = null
        fusedLocationClient = null
    }

    private fun handleNewLocation(loc: Location) {
        val currentGpsAlt = loc.altitude
        val locAlt = loc.altitude.toFloat()
        lastGpsFixTimeMs = System.currentTimeMillis()
        gpsAccuracyM = loc.accuracy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) {
            gpsVerticalAccuracyM = loc.verticalAccuracyMeters
        }
        isGpsAvailable = true

        val wasFixAcquired = _dataFlow.value.gpsFixAcquired
        if (!wasFixAcquired) {
            DebugLogger.log(
                TAG,
                "GPS fix acquired: lat=${loc.latitude}, lon=${loc.longitude}, alt=${loc.altitude.toInt()}m (acc=±${loc.accuracy}m)",
                DebugLogger.Level.INFO
            )
        }

        // Always update maxAltitudeM
        maxAltitudeM = maxOf(maxAltitudeM, locAlt)

        // Takeoff is established on the first valid GPS fix after service start
        if (takeoffLocation == null) {
            takeoffLocation = loc
            initialGpsAltitudeM = locAlt
            DebugLogger.log(TAG, "Takeoff reference locked at ${locAlt}m GPS alt", DebugLogger.Level.INFO)
        }

        // If not calibrated and valid raw pressure is available, calibrate QNH immediately
        if (!isCalibrated && lastRawPressurePa in 30000L..115000L) {
            currentQnhPa = VarioMath.calculateQnh(lastRawPressurePa, locAlt)
            isCalibrated = true
            DebugLogger.log(TAG, "QNH calibrated from GPS location: $currentQnhPa Pa", DebugLogger.Level.INFO)
        }

        // Accumulate segment distance only when flight is active
        totalDistanceTraveledM = accumulateDistanceTraveled(isFlightActive, totalDistanceTraveledM, lastKnownLocation, loc)
        lastKnownLocation = loc

        if (isFlightActive) {
            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            val effectiveVz = if (_dataFlow.value.isUsbConnected) _dataFlow.value.vzMs else smoothedGpsVz
            GpxTrackManager.addPoint(
                TrackPoint(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeM = locAlt,
                    vzMs = effectiveVz,
                    speedKmh = speedKmh,
                    timeMs = System.currentTimeMillis()
                )
            )
        }

        val distanceToTakeoff = computeDistanceToTakeoff(isFlightActive, _dataFlow.value.distanceToTakeoffM, loc, takeoffLocation)

        // Compute GPS-derived vertical speed (Vz) using monotonic clock and EMA (zero heap allocation)
        // Active only when flight has started
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (isFlightActive) {
            if (!lastGpsAltitudeM.isNaN() && lastGpsTimeMs > 0L) {
                val dtSec = (nowElapsedMs - lastGpsTimeMs) / 1000.0f
                smoothedGpsVz = computeGpsVz(currentGpsAlt, lastGpsAltitudeM, dtSec, smoothedGpsVz)
            }
        } else {
            smoothedGpsVz = 0f
        }
        lastGpsAltitudeM = currentGpsAlt
        lastGpsTimeMs = nowElapsedMs

        val current = _dataFlow.value
        val newAltitudeM = if (current.altitudeM <= 0f) locAlt else current.altitudeM

        if (!current.isUsbConnected) {
            val effectiveVz = if (isFlightActive) smoothedGpsVz else 0f
            // Feed GPS-derived Vz to audio engine only when flight is active
            audioEngine?.currentVz = effectiveVz

            _dataFlow.value = current.copy(
                altitudeM = locAlt,
                vzMs = effectiveVz,
                distanceToTakeoffM = distanceToTakeoff,
                totalDistanceTraveledM = totalDistanceTraveledM,
                gpsFixAcquired = true,
                isCalibrated = isCalibrated,
                latitude = loc.latitude,
                longitude = loc.longitude,
                gpsAltitudeM = locAlt,
                maxAltitudeM = maxAltitudeM,
                isUsbConnected = false,
                sensorMode = SensorMode.GPS_ONLY,
                gpsAccuracyM = gpsAccuracyM,
                gpsVerticalAccuracyM = gpsVerticalAccuracyM,
                lastGpsFixAgeSec = 0f,
                isGpsAvailable = true
            )
        } else {
            _dataFlow.value = current.copy(
                altitudeM = newAltitudeM,
                maxAltitudeM = maxAltitudeM,
                distanceToTakeoffM = distanceToTakeoff,
                totalDistanceTraveledM = totalDistanceTraveledM,
                gpsFixAcquired = true,
                isCalibrated = isCalibrated,
                latitude = loc.latitude,
                longitude = loc.longitude,
                gpsAltitudeM = locAlt,
                gpsAccuracyM = gpsAccuracyM,
                gpsVerticalAccuracyM = gpsVerticalAccuracyM,
                lastGpsFixAgeSec = 0f,
                isGpsAvailable = true
            )
        }
    }

    // ── Lk8ex1Parser.Listener callback ───────────────────────────────────────

    /**
     * Called when a complete LK8EX1 sentence has been parsed.
     *
     * Converts native vario (cm/s) to m/s for instant zero-lag audio feedback,
     * and derives altitude either from field 1 (if provided) or from barometric
     * pressure (field 0 in Pa) calibrated with GPS QNH.
     */
    override fun onSentenceComplete(pressurePa: Long, altitudeM: Long, varioCmS: Long) {
        val effectivePressurePa = if (pressurePa in 300..1200) pressurePa * 100 else pressurePa
        val isValidPressure = effectivePressurePa in 30000L..115000L
        if (isValidPressure) {
            lastRawPressurePa = effectivePressurePa
        }

        var calculatedAlt = if (isValidPressure) {
            val availableGpsAlt = initialGpsAltitudeM ?: lastKnownLocation?.altitude?.toFloat()
            if (!isCalibrated && availableGpsAlt != null) {
                currentQnhPa = VarioMath.calculateQnh(effectivePressurePa, availableGpsAlt)
                isCalibrated = true
                DebugLogger.log(TAG, "QNH calibrated with baro pressure: $currentQnhPa Pa", DebugLogger.Level.INFO)
            }
            VarioMath.pressureToAltitude(effectivePressurePa, currentQnhPa)
        } else if (altitudeM != 99999L && altitudeM > 0L) {
            altitudeM.toFloat()
        } else {
            0f
        }

        if (calculatedAlt <= 0f) {
            val gpsAlt = lastKnownLocation?.altitude?.toFloat() ?: initialGpsAltitudeM ?: _dataFlow.value.gpsAltitudeM
            if (gpsAlt > 0f) {
                calculatedAlt = gpsAlt
            } else if (_dataFlow.value.altitudeM > 0f) {
                calculatedAlt = _dataFlow.value.altitudeM
            }
        }

        if (calculatedAlt > maxAltitudeM) {
            maxAltitudeM = calculatedAlt
        }

        val nowMs = SystemClock.elapsedRealtime()
        val hasNativeVario = varioCmS != 0L && varioCmS != 99999L
        val vz: Float
        if (isFlightActive) {
            if (hasNativeVario) {
                vz = varioCmS / 100f
                lastBaroAltM = calculatedAlt
                lastBaroTimeMs = nowMs
            } else {
                if (!lastBaroAltM.isNaN() && lastBaroTimeMs > 0L) {
                    val dtSec = (nowMs - lastBaroTimeMs) / 1000f
                    if (dtSec in 0.05f..3.0f) {
                        val rawBaroVz = ((calculatedAlt - lastBaroAltM) / dtSec).coerceIn(-20f, 20f)
                        smoothedBaroVz = if (smoothedBaroVz == 0f) rawBaroVz else (smoothedBaroVz * 0.65f + rawBaroVz * 0.35f)
                        lastBaroAltM = calculatedAlt
                        lastBaroTimeMs = nowMs
                    } else if (dtSec > 3.0f) {
                        lastBaroAltM = calculatedAlt
                        lastBaroTimeMs = nowMs
                    }
                } else {
                    lastBaroAltM = calculatedAlt
                    lastBaroTimeMs = nowMs
                }
                vz = smoothedBaroVz
            }
        } else {
            lastBaroAltM = calculatedAlt
            lastBaroTimeMs = nowMs
            smoothedBaroVz = 0f
            vz = 0f
        }

        // ── Fast path: update audio engine immediately (volatile write) ──
        audioEngine?.currentVz = vz

        if (nowMs - lastDiagLogTimeMs >= 800L || kotlin.math.abs(vz) >= 0.3f) {
            lastDiagLogTimeMs = nowMs
            val rawAltStr = if (altitudeM == 99999L) "99999(sentinelle non fournie)" else "${altitudeM}m"
            val rawSentence = parser.getLastRawSentence().trim()
            DebugLogger.log(
                TAG,
                "LK8EX1 raw='$rawSentence' -> P=${pressurePa}Pa, rawAlt=$rawAltStr, varioCmS=$varioCmS -> calcAlt=%.1fm, vz=%+.2fm/s (nativeVario=$hasNativeVario)"
                    .format(Locale.US, calculatedAlt, vz),
                DebugLogger.Level.INFO
            )
        }

        // ── Slow path: update UI StateFlow (allocates VarioData — off audio thread) ──
        val current = _dataFlow.value
        _dataFlow.value = current.copy(
            altitudeM = calculatedAlt,
            vzMs = vz,
            isCalibrated = isCalibrated,
            maxAltitudeM = maxAltitudeM,
            isUsbConnected = true,
            sensorMode = SensorMode.BARO_AND_GPS,
            totalBytesRead = totalBytesRead,
            validFramesCount = parser.validFramesCount,
            crcErrorsCount = parser.errorCount,
            lastRawSentence = parser.getLastRawSentence(),
            lastRawPressurePa = pressurePa,
            lastRawVarioCmS = varioCmS
        )
    }

    // ── Task Management & Lifecycle ─────────────────────────────────────────

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DebugLogger.log(TAG, "Application swiped away from recents; stopping VarioService", DebugLogger.Level.INFO)
        if (isFlightActive) {
            val duration = flightDurationSec
            val maxAlt = maxAltitudeM
            val dist = totalDistanceTraveledM
            val startMs = flightStartTimeMs
            try {
                GpxTrackManager.saveCurrentTrack(
                    context = this,
                    startTimeMs = startMs,
                    durationSec = duration,
                    maxAltitudeM = maxAlt,
                    totalDistanceM = dist
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving GPX track in onTaskRemoved", e)
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Variomètre",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service variomètre en cours d'exécution"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}", e)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val content = when {
            _dataFlow.value.isUsbConnected -> "Module USB connecté (LK8EX1)"
            _dataFlow.value.isUsbScanning -> "Recherche du module USB (30s)…"
            else -> "Mode GPS seul"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Variomètre actif")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
