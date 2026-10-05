package ru.ryadom.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Те же случаи, что у сайта (web/src/volunteer/quietHours.test.ts) и сервера (VolunteerMatcher).
class DoNotDisturbTest {
    @Test
    fun windowAcrossMidnight() {
        val night = DoNotDisturb("22:00", "08:00")

        assertEquals(listOf(true, true, true, false, false), listOf("22:00", "23:59", "07:59", "08:00", "12:00").map(night::contains))
        assertTrue(night.isSet)
    }

    @Test
    fun windowWithinDay() {
        val lunch = DoNotDisturb("13:00", "14:00")

        assertEquals(listOf(false, true, false), listOf("12:59", "13:30", "14:00").map(lunch::contains))
    }

    @Test
    fun equalStartAndEndMeansNoQuietHours() {
        val none = DoNotDisturb("00:00", "00:00")

        assertFalse(none.isSet)
        assertFalse(none.contains("00:00"))
    }
}
