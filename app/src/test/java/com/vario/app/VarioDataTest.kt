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
}
