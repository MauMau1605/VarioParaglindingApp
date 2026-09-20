package com.vario.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VarioDataTest {

    @Test
    fun defaultConstructor_hasZeroValues() {
        val data = VarioData()
        assertEquals(0f, data.altitudeM)
        assertEquals(0f, data.vzMs)
    }

    @Test
    fun customConstructor_holdsGivenValues() {
        val data = VarioData(altitudeM = 1450.5f, vzMs = 2.4f)
        assertEquals(1450.5f, data.altitudeM)
        assertEquals(2.4f, data.vzMs)
    }

    @Test
    fun copy_createsIndependentInstance() {
        val data1 = VarioData(altitudeM = 1000f, vzMs = 1.2f)
        val data2 = data1.copy(vzMs = 3.0f)

        assertEquals(1000f, data2.altitudeM)
        assertEquals(3.0f, data2.vzMs)
        assertEquals(1.2f, data1.vzMs)
    }

    @Test
    fun equalsAndHashCode_behaveCorrectly() {
        val a = VarioData(altitudeM = 500f, vzMs = -1.5f)
        val b = VarioData(altitudeM = 500f, vzMs = -1.5f)
        val c = VarioData(altitudeM = 501f, vzMs = -1.5f)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun gpsAndTakeoffFields_defaultAndCustomValues() {
        val defaultData = VarioData()
        assertEquals(null, defaultData.distanceToTakeoffM)
        assertEquals(false, defaultData.gpsFixAcquired)
        assertEquals(false, defaultData.isCalibrated)

        val customData = VarioData(
            altitudeM = 1200f,
            vzMs = 1.5f,
            distanceToTakeoffM = 340.5f,
            gpsFixAcquired = true,
            isCalibrated = true,
            latitude = 45.1885,
            longitude = 5.7245,
            gpsAltitudeM = 1205f
        )
        assertEquals(340.5f, customData.distanceToTakeoffM)
        assertEquals(true, customData.gpsFixAcquired)
        assertEquals(true, customData.isCalibrated)
        assertEquals(45.1885, customData.latitude)
        assertEquals(5.7245, customData.longitude)
        assertEquals(1205f, customData.gpsAltitudeM)
    }

    @Test
    fun sensorModeAndUsbFields_defaultAndCustomValues() {
        val defaultData = VarioData()
        assertEquals(false, defaultData.isUsbConnected)
        assertEquals(false, defaultData.isUsbScanning)
        assertEquals(SensorMode.GPS_ONLY, defaultData.sensorMode)
        assertEquals(null, defaultData.usbDeviceName)

        val customData = VarioData(
            isUsbConnected = true,
            isUsbScanning = false,
            sensorMode = SensorMode.BARO_AND_GPS,
            usbDeviceName = "SAMD21 Vario Dongle"
        )
        assertEquals(true, customData.isUsbConnected)
        assertEquals(false, customData.isUsbScanning)
        assertEquals(SensorMode.BARO_AND_GPS, customData.sensorMode)
        assertEquals("SAMD21 Vario Dongle", customData.usbDeviceName)

        val scanningData = defaultData.copy(isUsbScanning = true)
        assertEquals(true, scanningData.isUsbScanning)
        assertEquals(false, scanningData.isUsbConnected)
    }

    @Test
    fun sensorModeEnum_containsExpectedValues() {
        val values = SensorMode.values()
        assertEquals(2, values.size)
        assertEquals(SensorMode.BARO_AND_GPS, SensorMode.valueOf("BARO_AND_GPS"))
        assertEquals(SensorMode.GPS_ONLY, SensorMode.valueOf("GPS_ONLY"))
    }

    @Test
    fun debugDiagnosticsFields_defaultsAndCustomValues() {
        val defaultData = VarioData()
        assertEquals(0f, defaultData.gpsAccuracyM)
        assertEquals(0f, defaultData.gpsVerticalAccuracyM)
        assertEquals(-1f, defaultData.lastGpsFixAgeSec)
        assertEquals(false, defaultData.isGpsAvailable)
        assertEquals(false, defaultData.usbPermissionGranted)
        assertEquals(false, defaultData.usbPortOpen)
        assertEquals(115200, defaultData.currentBaudRate)
        assertEquals(0, defaultData.usbVid)
        assertEquals(0, defaultData.usbPid)
        assertEquals(0L, defaultData.totalBytesRead)
        assertEquals(0L, defaultData.validFramesCount)
        assertEquals(0L, defaultData.crcErrorsCount)
        assertEquals("", defaultData.lastRawSentence)
        assertEquals(0L, defaultData.lastRawPressurePa)
        assertEquals(0L, defaultData.lastRawVarioCmS)

        val customData = defaultData.copy(
            gpsAccuracyM = 3.2f,
            gpsVerticalAccuracyM = 4.5f,
            lastGpsFixAgeSec = 0.5f,
            isGpsAvailable = true,
            usbPermissionGranted = true,
            usbPortOpen = true,
            currentBaudRate = 57600,
            usbVid = 0x1A86,
            usbPid = 0x7523,
            totalBytesRead = 4096L,
            validFramesCount = 120L,
            crcErrorsCount = 2L,
            lastRawSentence = "\$LK8EX1,101325,99999,150,220,999,*32\r\n",
            lastRawPressurePa = 101325L,
            lastRawVarioCmS = 150L
        )
        assertEquals(3.2f, customData.gpsAccuracyM)
        assertEquals(4.5f, customData.gpsVerticalAccuracyM)
        assertEquals(0.5f, customData.lastGpsFixAgeSec)
        assertEquals(true, customData.isGpsAvailable)
        assertEquals(true, customData.usbPermissionGranted)
        assertEquals(true, customData.usbPortOpen)
        assertEquals(57600, customData.currentBaudRate)
        assertEquals(0x1A86, customData.usbVid)
        assertEquals(0x7523, customData.usbPid)
        assertEquals(4096L, customData.totalBytesRead)
        assertEquals(120L, customData.validFramesCount)
        assertEquals(2L, customData.crcErrorsCount)
        assertEquals("\$LK8EX1,101325,99999,150,220,999,*32\r\n", customData.lastRawSentence)
        assertEquals(101325L, customData.lastRawPressurePa)
        assertEquals(150L, customData.lastRawVarioCmS)
    }

    @Test
    fun flightModeAndSessionPhase_defaultsAndCustomValues() {
        val defaultData = VarioData()
        assertEquals(FlightMode.NORMAL, defaultData.flightMode)
        assertEquals(SessionPhase.IDLE, defaultData.sessionPhase)
        assertEquals(0f, defaultData.elevationGainM)
        assertEquals(0f, defaultData.hikeStartAltitudeM)

        val customData = defaultData.copy(
            flightMode = FlightMode.HIKE_AND_FLY,
            sessionPhase = SessionPhase.HIKING,
            elevationGainM = 350.5f,
            hikeStartAltitudeM = 1100f
        )
        assertEquals(FlightMode.HIKE_AND_FLY, customData.flightMode)
        assertEquals(SessionPhase.HIKING, customData.sessionPhase)
        assertEquals(350.5f, customData.elevationGainM)
        assertEquals(1100f, customData.hikeStartAltitudeM)

        val flyingPhase = customData.copy(sessionPhase = SessionPhase.FLYING)
        assertEquals(SessionPhase.FLYING, flyingPhase.sessionPhase)
    }

    @Test
    fun flightModeEnum_containsExpectedValues() {
        val values = FlightMode.values()
        assertEquals(2, values.size)
        assertEquals(FlightMode.NORMAL, FlightMode.valueOf("NORMAL"))
        assertEquals(FlightMode.HIKE_AND_FLY, FlightMode.valueOf("HIKE_AND_FLY"))
    }

    @Test
    fun sessionPhaseEnum_containsExpectedValues() {
        val values = SessionPhase.values()
        assertEquals(3, values.size)
        assertEquals(SessionPhase.IDLE, SessionPhase.valueOf("IDLE"))
        assertEquals(SessionPhase.HIKING, SessionPhase.valueOf("HIKING"))
        assertEquals(SessionPhase.FLYING, SessionPhase.valueOf("FLYING"))
    }
}

