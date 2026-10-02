package ru.ryadom.backend.requests

import ru.ryadom.backend.ProfileDefaults
import ru.ryadom.backend.users.UserRecord
import ru.ryadom.shared.api.Gender
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.Role
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Правила подбора из docs/ARCHITECTURE.md, раздел 4 — без базы, с фиксированным временем. */
class VolunteerMatcherTest {
    private val defaults =
        ProfileDefaults(
            language = Language.RU,
            timezone = ZoneId.of("Europe/Moscow"),
            doNotDisturbFrom = LocalTime.of(22, 0),
            doNotDisturbTo = LocalTime.of(8, 0),
        )
    private val matcher = VolunteerMatcher(defaults, Random(42))

    // 10:00 UTC = 13:00 по Москве: дневное время.
    private val now = Instant.parse("2026-09-30T10:00:00Z")

    private fun volunteer(
        languages: List<Language> = listOf(Language.RU),
        gender: Gender = Gender.UNSPECIFIED,
        timezone: String = "Europe/Moscow",
        doNotDisturb: Pair<LocalTime, LocalTime>? = null,
        role: Role? = Role.VOLUNTEER,
        notificationsEnabled: Boolean = true,
        bannedAt: Instant? = null,
        lastDisturbedAt: Instant? = null,
        inCall: Boolean = false,
        alreadyNotified: Boolean = false,
    ) = VolunteerCandidate(
        user =
            UserRecord(
                id = Uuid.random(),
                role = role,
                displayName = null,
                languages = languages,
                gender = gender,
                genderPreference = GenderPreference.ANY,
                timezone = timezone,
                doNotDisturb = doNotDisturb,
                notificationsEnabled = notificationsEnabled,
                bannedAt = bannedAt,
                createdAt = now,
            ),
        lastDisturbedAt = lastDisturbedAt,
        inCall = inCall,
        alreadyNotified = alreadyNotified,
    )

    private fun select(
        vararg candidates: VolunteerCandidate,
        language: Language = Language.RU,
        genderPreference: GenderPreference = GenderPreference.ANY,
        limit: Int = 10,
    ) = matcher.select(candidates.toList(), language, genderPreference, now, limit)

    private fun VolunteerCandidate.isSelected(
        language: Language = Language.RU,
        genderPreference: GenderPreference = GenderPreference.ANY,
    ) = select(this, language = language, genderPreference = genderPreference) == listOf(user.id)

    @Test
    fun suitableVolunteerIsSelected() {
        assertTrue(volunteer().isSelected())
    }

    @Test
    fun volunteerMustSpeakRequestLanguage() {
        assertFalse(volunteer(languages = listOf(Language.RU)).isSelected(language = Language.EN))
        assertTrue(volunteer(languages = listOf(Language.RU, Language.EN)).isSelected(language = Language.EN))
    }

    @Test
    fun genderPreferenceIsRespected() {
        val female = volunteer(gender = Gender.FEMALE)
        val unspecified = volunteer(gender = Gender.UNSPECIFIED)

        assertTrue(female.isSelected(genderPreference = GenderPreference.FEMALE))
        assertFalse(female.isSelected(genderPreference = GenderPreference.MALE))
        assertFalse(unspecified.isSelected(genderPreference = GenderPreference.FEMALE), "пол не указан — подходит только «любой»")
        assertTrue(unspecified.isSelected(genderPreference = GenderPreference.ANY))
    }

    @Test
    fun defaultQuietHoursAreNightByVolunteerTimezone() {
        // 10:00 UTC: в Москве 13:00, во Владивостоке 20:00, в Петропавловске-Камчатском 22:00, в Нью-Йорке 06:00.
        assertTrue(volunteer(timezone = "Asia/Vladivostok").isSelected())
        assertFalse(volunteer(timezone = "Asia/Kamchatka").isSelected(), "22:00 — начало окна «не беспокоить»")
        assertFalse(volunteer(timezone = "America/New_York").isSelected())
    }

    @Test
    fun ownQuietHoursReplaceDefault() {
        // В Москве 13:00.
        assertFalse(volunteer(doNotDisturb = LocalTime.of(12, 0) to LocalTime.of(14, 0)).isSelected())
        assertTrue(volunteer(doNotDisturb = LocalTime.of(14, 0) to LocalTime.of(12, 0)).isSelected(), "окно через полночь: 14:00–12:00")
        assertTrue(
            volunteer(doNotDisturb = LocalTime.of(13, 0) to LocalTime.of(13, 0)).isSelected(),
            "пустое окно — беспокоить можно всегда",
        )
        assertTrue(volunteer(timezone = "America/New_York", doNotDisturb = LocalTime.of(0, 0) to LocalTime.of(5, 0)).isSelected())
    }

    @Test
    fun windowHandlesMidnightAndBounds() {
        val night = LocalTime.of(22, 0) to LocalTime.of(8, 0)

        assertTrue(isInWindow(LocalTime.of(23, 30), night.first, night.second))
        assertTrue(isInWindow(LocalTime.of(3, 0), night.first, night.second))
        assertTrue(isInWindow(LocalTime.of(22, 0), night.first, night.second), "начало включительно")
        assertFalse(isInWindow(LocalTime.of(8, 0), night.first, night.second), "конец не включается")
        assertFalse(isInWindow(LocalTime.of(12, 0), night.first, night.second))
        assertFalse(isInWindow(LocalTime.of(12, 0), LocalTime.of(12, 0), LocalTime.of(12, 0)))
    }

    @Test
    fun unknownTimezoneFallsBackToDefault() {
        assertTrue(volunteer(timezone = "Mars/Olympus").isSelected())
    }

    @Test
    fun recentlyDisturbedVolunteerIsCalledIfNobodyElse() {
        // Жёсткой паузы нет: пока волонтёров мало, повторный запрос должен дойти до тех же людей.
        assertTrue(volunteer(lastDisturbedAt = now.minus(Duration.ofSeconds(5))).isSelected())
    }

    @Test
    fun restedVolunteersFillWaveBeforeRecentlyDisturbed() {
        val justCalled = volunteer(lastDisturbedAt = now.minus(Duration.ofSeconds(30)))
        val rested = volunteer(lastDisturbedAt = now.minus(Duration.ofHours(2)))
        val never = volunteer()

        assertEquals(listOf(never, rested).map { it.user.id }, select(justCalled, rested, never, limit = 2))
    }

    @Test
    fun busyOrAlreadyNotifiedVolunteerIsSkipped() {
        assertFalse(volunteer(inCall = true).isSelected())
        assertFalse(volunteer(alreadyNotified = true).isSelected())
    }

    @Test
    fun onlyActiveVolunteersWithNotificationsAreSelected() {
        assertFalse(volunteer(role = Role.BLIND).isSelected())
        assertFalse(volunteer(role = null).isSelected())
        assertFalse(volunteer(notificationsEnabled = false).isSelected())
        assertFalse(volunteer(bannedAt = now).isSelected())
    }

    @Test
    fun leastRecentlyDisturbedGoFirst() {
        val never = volunteer()
        val hourAgo = volunteer(lastDisturbedAt = now.minus(Duration.ofHours(1)))
        val dayAgo = volunteer(lastDisturbedAt = now.minus(Duration.ofDays(1)))

        val order = select(hourAgo, never, dayAgo)

        assertEquals(listOf(never, dayAgo, hourAgo).map { it.user.id }, order)
    }

    @Test
    fun limitIsWaveSize() {
        val candidates = List(7) { volunteer() }.toTypedArray()

        assertEquals(5, select(*candidates, limit = 5).size)
    }

    @Test
    fun tiesAreShuffled() {
        // У всех одинаковое время: порядок случайный, но с одним и тем же зерном — одинаковый.
        val candidates = List(10) { volunteer() }

        fun selectWithSeed(seed: Int) =
            VolunteerMatcher(defaults, Random(seed)).select(candidates, Language.RU, GenderPreference.ANY, now, 10)

        assertEquals(selectWithSeed(1), selectWithSeed(1))
        assertEquals(candidates.map { it.user.id }.toSet(), selectWithSeed(1).toSet())
        assertNotEquals(candidates.map { it.user.id }, selectWithSeed(1), "порядок перемешан")
    }
}
