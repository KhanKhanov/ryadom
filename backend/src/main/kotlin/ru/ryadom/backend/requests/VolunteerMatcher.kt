package ru.ryadom.backend.requests

import ru.ryadom.backend.ProfileDefaults
import ru.ryadom.backend.users.UserRecord
import ru.ryadom.shared.api.Gender
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.Role
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.uuid.Uuid

/** Волонтёр, которого можно уведомить о запросе, и всё, что о нём нужно знать для подбора. */
data class VolunteerCandidate(
    val user: UserRecord,
    /**
     * Когда волонтёра в последний раз беспокоили: пришёл вызов (по любому запросу) или закончился
     * его звонок; `null` — никогда.
     */
    val lastDisturbedAt: Instant?,
    val inCall: Boolean,
    /** Уведомление об этом запросе уже отправлено. */
    val alreadyNotified: Boolean,
)

/**
 * Правила подбора волонтёров (docs/ARCHITECTURE.md, раздел 4). Чистая функция без базы и сети:
 * на вход — кандидаты и время, на выход — кого уведомить, в порядке очереди.
 *
 * Жёсткой паузы между вызовами нет: недавно побеспокоенный волонтёр просто стоит в конце очереди
 * и попадает в волну, только если остальных подходящих не хватает. Иначе, пока волонтёров мало,
 * повторный запрос незрячего не дошёл бы ни до кого.
 *
 * @param random порядок среди волонтёров, которых не беспокоили одинаково давно (в тестах — с зерном).
 */
class VolunteerMatcher(
    private val defaults: ProfileDefaults,
    private val random: Random = Random.Default,
) {
    /**
     * Выбирает до [limit] волонтёров для запроса на языке [language] с пожеланием [genderPreference].
     * Сначала те, кого дольше всего не беспокоили (никогда не беспокоенные — первыми).
     */
    fun select(
        candidates: List<VolunteerCandidate>,
        language: Language,
        genderPreference: GenderPreference,
        now: Instant,
        limit: Int,
    ): List<Uuid> =
        candidates
            .filter { it.isSuitable(language, genderPreference, now) }
            // Сначала перемешиваем, потом стабильно сортируем: при равном времени порядок случайный.
            .shuffled(random)
            .sortedWith(compareBy(nullsFirst()) { it.lastDisturbedAt })
            .take(limit)
            .map { it.user.id }

    private fun VolunteerCandidate.isSuitable(
        language: Language,
        genderPreference: GenderPreference,
        now: Instant,
    ): Boolean =
        user.role == Role.VOLUNTEER &&
            !user.isBanned &&
            user.notificationsEnabled &&
            language in user.languages &&
            user.gender.matches(genderPreference) &&
            !isQuietTime(user, now) &&
            !inCall &&
            !alreadyNotified

    /** Сейчас у волонтёра окно «не беспокоить» по его местному времени. */
    private fun isQuietTime(
        user: UserRecord,
        now: Instant,
    ): Boolean {
        val zone =
            try {
                ZoneId.of(user.timezone)
            } catch (e: DateTimeException) {
                // Часовой пояс проверяется при сохранении профиля, но если он всё же неизвестен — берём пояс по умолчанию.
                defaults.timezone
            }
        val (from, to) = user.doNotDisturb ?: (defaults.doNotDisturbFrom to defaults.doNotDisturbTo)
        return isInWindow(now.atZone(zone).toLocalTime(), from, to)
    }
}

/** Пол волонтёра подходит под пожелание. Если пол не указан, волонтёр подходит только при пожелании «любой». */
private fun Gender.matches(preference: GenderPreference): Boolean =
    when (preference) {
        GenderPreference.ANY -> true
        GenderPreference.MALE -> this == Gender.MALE
        GenderPreference.FEMALE -> this == Gender.FEMALE
    }

/**
 * Время [time] внутри окна [from]–[to] (начало включительно, конец — нет).
 * Окно может переходить через полночь (22:00–08:00); если [from] равно [to], окно пустое.
 */
internal fun isInWindow(
    time: LocalTime,
    from: LocalTime,
    to: LocalTime,
): Boolean =
    when {
        from == to -> false
        from < to -> time >= from && time < to
        else -> time >= from || time < to
    }
