package ru.ryadom.backend.requests

import io.ktor.client.call.body
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import ru.ryadom.backend.testing.ApiTestScope
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.auth
import ru.ryadom.backend.testing.testConfig
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.CreateHelpRequest
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.api.UpdateProfileRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Поиск волонтёра: волны, таймаут и фильтры — с поддельными часами и ручным запуском проверки (`tick`). */
class MatchingFlowTest {
    /** По одному волонтёру в волне — чтобы было видно, кого позвали первым. */
    private val oneByOne = testConfig().let { it.copy(matching = it.matching.copy(firstWaveSize = 1, nextWaveSize = 1)) }

    @Test
    fun firstWaveGoesToConnectedVolunteersImmediately() =
        apiTest {
            val first = volunteer("volunteer-1")
            val second = volunteer("volunteer-2")
            val offline = volunteer("volunteer-offline")
            val firstEvents = connect(first)
            val secondEvents = connect(second)

            val request = requestHelp(blind())

            assertEquals(request, firstEvents.nextOf<ServerEvent.RequestIncoming>().request)
            assertEquals(request, secondEvents.nextOf<ServerEvent.RequestIncoming>().request)
            assertFalse(isNotified(request.id, offline), "без WebSocket вызов не доставить (push появится на этапе 5)")
            assertEquals(2, notifiedCount(request.id))
        }

    @Test
    fun wavesFollowSchedule() =
        apiTest {
            repeat(17) { connect(volunteer("volunteer-$it")) }
            val request = requestHelp(blind())
            assertEquals(5, notifiedCount(request.id), "первая волна — 5 человек сразу")

            clock.advance(Duration.ofSeconds(9))
            tick()
            assertEquals(5, notifiedCount(request.id), "следующая волна — только через 10 секунд")

            clock.advance(Duration.ofSeconds(1))
            tick()
            tick()
            assertEquals(15, notifiedCount(request.id), "вторая волна — ещё 10, повторная проверка ничего не добавляет")

            clock.advance(Duration.ofSeconds(10))
            tick()
            assertEquals(17, notifiedCount(request.id), "третья волна — оставшиеся")
            assertEquals(
                listOf(5, 10, 2),
                (0..2).map { TestDatabase.queryInt("SELECT count(*) FROM request_notifications WHERE wave = $it") },
            )
        }

    @Test
    fun volunteerWhoConnectsDuringSearchGetsNextWave() =
        apiTest {
            val request = requestHelp(blind())
            val late = volunteer("volunteer-late")
            val events = connect(late)

            clock.advance(Duration.ofSeconds(10))
            tick()

            assertEquals(request.id, events.nextOf<ServerEvent.RequestIncoming>().request.id)
        }

    @Test
    fun noAnswerAfterSearchTimeout() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            val volunteerEvents = connect(volunteer)
            val blind = blind()
            val blindEvents = connect(blind)
            val request = requestHelp(blind)
            volunteerEvents.nextOf<ServerEvent.RequestIncoming>()

            clock.advance(Duration.ofSeconds(59))
            tick()
            assertEquals(RequestStatus.SEARCHING, getRequest(blind, request.id).body<HelpRequest>().status)

            clock.advance(Duration.ofSeconds(1))
            tick()

            val closed = getRequest(blind, request.id).body<HelpRequest>()
            assertEquals(RequestStatus.NO_ANSWER, closed.status)
            assertEquals("2026-09-30T10:01:00Z", closed.endedAt)
            assertEquals(closed, blindEvents.nextOf<ServerEvent.RequestNoAnswer>().request)
            assertEquals(closed, volunteerEvents.nextOf<ServerEvent.RequestNoAnswer>().request, "у волонтёра перестаёт «звонить»")
            assertEquals(HttpStatusCode.NoContent, currentRequest(blind).status)
            requestHelp(blind)
        }

    @Test
    fun unsuitableVolunteersAreSkipped() =
        apiTest {
            val suitable = volunteer("suitable")
            val englishOnly = volunteer("english-only")
            val notificationsOff = volunteer("notifications-off")
            val banned = volunteer("banned")
            val nightOwl = volunteer("night-owl")
            for (volunteer in listOf(suitable, englishOnly, notificationsOff, banned, nightOwl)) connect(volunteer)
            patchProfile(englishOnly, UpdateProfileRequest(languages = listOf(Language.EN)))
            patchProfile(notificationsOff, UpdateProfileRequest(notificationsEnabled = false))
            // 10:00 UTC = 06:00 в Нью-Йорке: у волонтёра ночь по его часовому поясу.
            patchProfile(nightOwl, UpdateProfileRequest(timezone = "America/New_York"))
            TestDatabase.execute("UPDATE users SET banned_at = now() WHERE id = '${banned.user.id}'")

            val request = requestHelp(blind())

            assertTrue(isNotified(request.id, suitable))
            assertEquals(1, notifiedCount(request.id))

            val english = requestHelp(blind("blind-2"), CreateHelpRequest(language = Language.EN))
            assertTrue(isNotified(english.id, englishOnly))
            assertEquals(1, notifiedCount(english.id))
        }

    @Test
    fun retriedRequestReachesSameVolunteer() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val blind = blind()
            val first = requestHelp(blind)
            clock.advance(Duration.ofSeconds(60))
            tick()
            assertEquals(RequestStatus.NO_ANSWER, getRequest(blind, first.id).body<HelpRequest>().status)

            val retry = requestHelp(blind)

            assertTrue(isNotified(retry.id, volunteer), "волонтёров мало — повторный запрос должен дойти до того же человека")
        }

    @Test
    fun volunteerWhoJustFinishedCallGoesLast() =
        apiTest(oneByOne) {
            val volunteers = listOf(volunteer("volunteer-1"), volunteer("volunteer-2"))
            volunteers.forEach { connect(it) }
            val firstBlind = blind("blind-1")
            val first = requestHelp(firstBlind)
            // Волна 0 — один из двоих, волна 1 через 10 секунд — второй.
            val talker = volunteers.single { isNotified(first.id, it) }
            val other = volunteers.single { it != talker }
            clock.advance(Duration.ofSeconds(10))
            tick()
            assertTrue(isNotified(first.id, other))
            assertEquals(HttpStatusCode.OK, acceptRequest(talker, first.id).status)
            clock.advance(Duration.ofMinutes(5))
            cancelRequest(firstBlind, first.id)

            val second = requestHelp(blind("blind-2"))

            // Вызов первому пришёл раньше, чем второму, но первый только что договорил — он в конце очереди.
            assertTrue(isNotified(second.id, other))
            assertFalse(isNotified(second.id, talker))
        }

    @Test
    fun requesterIsNotNotifiedAboutOwnRequest() =
        apiTest {
            val blind = blind()
            val request = requestHelp(blind)
            // Через API роль посреди запроса не сменить (ProfileTest) — проверяем вторую защиту, в подборе.
            TestDatabase.execute("UPDATE users SET role = 'volunteer' WHERE id = '${blind.user.id}'")
            connect(blind)
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)

            clock.advance(Duration.ofSeconds(10))
            tick()

            assertTrue(isNotified(request.id, volunteer))
            assertFalse(isNotified(request.id, blind))
        }

    @Test
    fun disconnectedVolunteerIsNotNotified() =
        apiTest {
            val volunteer = volunteer("volunteer-1")
            connect(volunteer).close()
            // Сервер узнаёт о закрытии асинхронно — даём ему немного времени.
            delay(300.milliseconds)

            val request = requestHelp(blind())

            assertFalse(isNotified(request.id, volunteer))
        }

    private suspend fun ApiTestScope.patchProfile(
        user: AuthResponse,
        changes: UpdateProfileRequest,
    ) {
        val response =
            client.patch(ApiPaths.ME) {
                auth(user)
                contentType(ContentType.Application.Json)
                setBody(changes)
            }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
