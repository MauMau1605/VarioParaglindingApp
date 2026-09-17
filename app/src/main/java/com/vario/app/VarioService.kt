package com.vario.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
 * 2. Opens the USB serial port (CDC/ACM) via `usb-serial-for-android` at 115200 baud.
 * 3. Reads incoming bytes and parses **LK8EX1** sentences with a zero-allocation state machine.
 * 4. Extracts native Vz from field 2 (cm/s → m/s) and computes calibrated barometric altitude.
 * 5. Immediately updates [VarioAudioEngine.currentVz] (volatile write — no lock).
 * 6. Publishes a [VarioData] snapshot on [dataFlow] for the UI layer.
 * 7. Requests GPS location updates (1 Hz) to calibrate QNH at takeoff and track takeoff distance.
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
        private const val BAUD_RATE = 115200
        private const val USB_READ_BUFFER_SIZE = 256
        private const val WAKELOCK_TAG = "VarioAppli::VarioService"
        const val ACTION_RESET_TAKEOFF = "com.vario.app.ACTION_RESET_TAKEOFF"
        const val ACTION_START_FLIGHT = "com.vario.app.ACTION_START_FLIGHT"
        const val ACTION_STOP_FLIGHT = "com.vario.app.ACTION_STOP_FLIGHT"
        const val ACTION_TOGGLE_MUTE = "com.vario.app.ACTION_TOGGLE_MUTE"

        /**
         * Observable data flow for the UI. Lives in the companion so that
         * [MainActivity] can observe it without holding a reference to the service.
         */
        private val _dataFlow = MutableStateFlow(VarioData())
        val dataFlow: StateFlow<VarioData> = _dataFlow.asStateFlow()
    }

    // ── Private state ────────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioEngine: VarioAudioEngine? = null
    private var serialPort: UsbSerialPort? = null
    private var serviceScope: CoroutineScope? = null

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

    // ── Flight session state ─────────────────────────────────────────────────

    private var isFlightActive: Boolean = false
    private var flightDurationSec: Long = 0L
    private var maxAltitudeM: Float = 0f
    private var flightTimerJob: Job? = null

    // ── Pre-allocated USB read buffer & zero-alloc parser ────────────────────

    private val usbReadBuffer = ByteArray(USB_READ_BUFFER_SIZE)
    private val parser = Lk8ex1Parser(this)

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val engine = VarioAudioEngine()
        engine.start()
        audioEngine = engine

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FLIGHT, ACTION_RESET_TAKEOFF -> {
                isFlightActive = true
                flightDurationSec = 0L
                totalDistanceTraveledM = 0f
                takeoffLocation = lastKnownLocation
                maxAltitudeM = _dataFlow.value.altitudeM

                flightTimerJob?.cancel()
                flightTimerJob = serviceScope?.launch {
                    while (isActive && isFlightActive) {
                        delay(1000L)
                        flightDurationSec++
                        _dataFlow.value = _dataFlow.value.copy(flightDurationSec = flightDurationSec)
                    }
                }

                val currentLoc = lastKnownLocation
                val dist = if (currentLoc != null && takeoffLocation != null) currentLoc.distanceTo(takeoffLocation!!) else 0f
                _dataFlow.value = _dataFlow.value.copy(
                    isFlightActive = true,
                    flightDurationSec = 0L,
                    maxAltitudeM = maxAltitudeM,
                    totalDistanceTraveledM = 0f,
                    distanceToTakeoffM = dist
                )
            }
            ACTION_STOP_FLIGHT -> {
                isFlightActive = false
                flightTimerJob?.cancel()
                flightTimerJob = null
                _dataFlow.value = _dataFlow.value.copy(isFlightActive = false)
            }
            ACTION_TOGGLE_MUTE -> {
                val engine = audioEngine
                if (engine != null) {
                    engine.isMuted = !engine.isMuted
                    _dataFlow.value = _dataFlow.value.copy(isMuted = engine.isMuted)
                }
            }
        }

        startLocationUpdates()
        openUsbAndStartReading()
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        flightTimerJob?.cancel()
        flightTimerJob = null
        isFlightActive = false
        flightDurationSec = 0L
        maxAltitudeM = 0f
        takeoffLocation = null
        lastKnownLocation = null
        initialGpsAltitudeM = null
        isCalibrated = false
        currentQnhPa = VarioMath.P0_PA
        totalDistanceTraveledM = 0f
        serviceScope?.cancel()
        serviceScope = null
        closeUsb()
        audioEngine?.stop()
        audioEngine = null
        releaseWakeLock()
        _dataFlow.value = VarioData()
        super.onDestroy()
    }

    // ── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout") // Intentional: runs for the entire flight duration
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── USB serial ───────────────────────────────────────────────────────────

    /**
     * Detect and open the first available USB serial device, then launch
     * the read loop coroutine on [Dispatchers.IO].
     */
    private fun openUsbAndStartReading() {
        serviceScope?.launch {
            try {
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                if (drivers.isEmpty()) {
                    Log.e(TAG, "No USB serial device found")
                    return@launch
                }

                val driver = drivers[0]
                val connection = usbManager.openDevice(driver.device)
                if (connection == null) {
                    Log.e(TAG, "USB permission denied or connection failed")
                    return@launch
                }

                val port = driver.ports[0]
                port.open(connection)
                port.setParameters(
                    BAUD_RATE,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                serialPort = port

                Log.i(TAG, "USB serial port opened on ${driver.device.deviceName}")
                readLoop(port)
            } catch (e: Exception) {
                Log.e(TAG, "USB setup error", e)
            }
        }
    }

    /**
     * Continuously read bytes from the serial port and feed them to the
     * zero-allocation parser. Runs on [Dispatchers.IO].
     */
    private suspend fun readLoop(port: UsbSerialPort) {
        while (serviceScope?.isActive == true) {
            try {
                val bytesRead = port.read(usbReadBuffer, 100) // 100 ms timeout
                if (bytesRead > 0) {
                    parser.parseBytes(usbReadBuffer, bytesRead)
                }
            } catch (e: Exception) {
                Log.e(TAG, "USB read error", e)
                delay(500) // back-off before retry
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permissions not granted; GPS tracking disabled")
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
        }
        locationCallback = callback

        try {
            client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
            Log.i(TAG, "GPS location updates requested (1s interval)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting location updates", e)
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
        // Takeoff is established on the first valid GPS fix after service start
        if (takeoffLocation == null) {
            takeoffLocation = loc
            val alt = loc.altitude.toFloat()
            initialGpsAltitudeM = alt
            Log.i(TAG, "Takeoff location locked: (${loc.latitude}, ${loc.longitude}) at ${alt}m GPS alt")

            // If raw pressure was already received, calibrate QNH immediately
            if (lastRawPressurePa > 0L && !isCalibrated) {
                currentQnhPa = VarioMath.calculateQnh(lastRawPressurePa, alt)
                isCalibrated = true
                Log.i(TAG, "QNH calibrated with initial pressure: $currentQnhPa Pa")
            }
        }

        // Accumulate segment distance to compute total path traveled
        val prev = lastKnownLocation
        if (prev != null) {
            totalDistanceTraveledM += prev.distanceTo(loc)
        }
        lastKnownLocation = loc

        val takeoff = takeoffLocation
        val distanceToTakeoff = if (takeoff != null) loc.distanceTo(takeoff) else null

        val current = _dataFlow.value
        _dataFlow.value = current.copy(
            distanceToTakeoffM = distanceToTakeoff,
            totalDistanceTraveledM = totalDistanceTraveledM,
            gpsFixAcquired = true,
            isCalibrated = isCalibrated,
            latitude = loc.latitude,
            longitude = loc.longitude,
            gpsAltitudeM = loc.altitude.toFloat()
        )
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
        val vz = varioCmS / 100f
        lastRawPressurePa = pressurePa

        // If GPS fix arrived before pressure, calibrate QNH now
        val initialGpsAlt = initialGpsAltitudeM
        if (!isCalibrated && initialGpsAlt != null && pressurePa > 0L) {
            currentQnhPa = VarioMath.calculateQnh(pressurePa, initialGpsAlt)
            isCalibrated = true
            Log.i(TAG, "QNH calibrated upon receiving first pressure: $currentQnhPa Pa")
        }

        val altitude = if (pressurePa > 0L) {
            VarioMath.pressureToAltitude(pressurePa, currentQnhPa)
        } else if (altitudeM != 99999L && altitudeM != 0L) {
            altitudeM.toFloat()
        } else {
            0f
        }

        if (altitude > maxAltitudeM) {
            maxAltitudeM = altitude
        }

        // ── Fast path: update audio engine immediately (volatile write) ──
        audioEngine?.currentVz = vz

        // ── Slow path: update UI StateFlow (allocates VarioData — off audio thread) ──
        val current = _dataFlow.value
        _dataFlow.value = current.copy(
            altitudeM = altitude,
            vzMs = vz,
            isCalibrated = isCalibrated,
            maxAltitudeM = maxAltitudeM
        )
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

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Variomètre actif")
            .setContentText("Lecture USB en cours…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
