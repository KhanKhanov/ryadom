package ru.ryadom.android.volunteer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ryadom.android.call.CallServiceStatus
import ru.ryadom.android.volunteer.VolunteerScreenTest.Companion.REQUEST_ID
import ru.ryadom.android.volunteer.VolunteerScreenTest.Companion.credentials
import ru.ryadom.android.volunteer.VolunteerScreenTest.Companion.request
import ru.ryadom.android.volunteer.VolunteerScreenTest.Companion.volunteerProfile
import ru.ryadom.shared.api.DoNotDisturb
import ru.ryadom.shared.volunteer.VolunteerCall
import ru.ryadom.shared.volunteer.VolunteerState
import java.time.ZonedDateTime

/** Решения, которые принимаются без экрана: уведомления о вызовах, foreground service, время тишины. */
class VolunteerSideEffectsTest {
    private val ringing = VolunteerState(incoming = listOf(request, request.copy(id = "other")))

    @Test
    fun inBackgroundEveryWaitingCallHasNotification() {
        assertEquals(setOf(REQUEST_ID, "other"), notifications(ringing, appVisible = false))
        assertEquals(emptySet<String>(), notifications(VolunteerState(), appVisible = false))
    }

    @Test
    fun onScreenTheAppRingsItself() {
        assertNull(notifications(ringing, appVisible = true))
    }

    @Test
    fun duringCallNoCallNotifications() {
        assertNull(notifications(ringing.copy(call = VolunteerCall(REQUEST_ID, credentials)), appVisible = false))
    }

    @Test
    fun foregroundServiceOnlyDuringCall() {
        assertNull(serviceStatus(ringing))
        assertEquals(CallServiceStatus.CALL, serviceStatus(VolunteerState(call = VolunteerCall(REQUEST_ID, credentials))))
        // Волонтёр завершает звонок — звук уже выключен.
        assertNull(serviceStatus(VolunteerState(call = VolunteerCall(REQUEST_ID, credentials, ending = true))))
    }

    @Test
    fun quietHoursUseTheProfileTimezone() {
        // 20:30 UTC — 23:30 в Москве: время тишины 22:00–08:00.
        val evening = ZonedDateTime.parse("2026-10-05T20:30:00Z")

        assertTrue(isQuietNow(volunteerProfile, evening))
        assertFalse(isQuietNow(volunteerProfile.copy(timezone = "Europe/London"), evening))
        assertFalse(isQuietNow(volunteerProfile.copy(doNotDisturb = DoNotDisturb("00:00", "00:00")), evening))
        // Пояс, неизвестный телефону, — считаем, что тишины нет.
        assertFalse(isQuietNow(volunteerProfile.copy(timezone = "Mars/Olympus"), evening))
    }
}
