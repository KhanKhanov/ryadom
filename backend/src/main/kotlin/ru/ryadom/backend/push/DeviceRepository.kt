package ru.ryadom.backend.push

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import ru.ryadom.backend.db.AppDatabase
import ru.ryadom.backend.db.DevicesTable
import ru.ryadom.backend.db.dbValue
import ru.ryadom.backend.db.pushProviderFromDb
import ru.ryadom.backend.db.toDb
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.WebPushKeys
import java.time.Instant
import kotlin.uuid.Uuid

/** Устройство для push-уведомлений, как оно хранится в базе. */
data class DeviceRecord(
    val id: Uuid,
    val userId: Uuid,
    val provider: PushProvider,
    /** Токен FCM или RuStore; у Web Push — адрес подписки. */
    val token: String,
    /** Ключи подписки — только у Web Push. */
    val webPush: WebPushKeys?,
) {
    // По токену push-сервис доставит уведомление — в логи он не попадает.
    override fun toString() = "DeviceRecord(id=$id, userId=$userId, provider=$provider)"
}

/** Устройства пользователей для push-уведомлений (таблица `devices`, миграция V3). */
class DeviceRepository(
    private val db: AppDatabase,
) {
    /**
     * Регистрирует устройство и возвращает его id. Тот же токен — та же запись (и тот же id),
     * даже если раньше устройство принадлежало другому пользователю: теперь оно его.
     * У пользователя остаётся не больше [maxPerUser] устройств — лишние, самые давние, удаляются.
     */
    suspend fun register(
        userId: Uuid,
        provider: PushProvider,
        token: String,
        webPush: WebPushKeys?,
        now: Instant,
        maxPerUser: Int,
    ): Uuid =
        db.query {
            // INSERT ... ON CONFLICT (push_provider, token) DO UPDATE: id и время создания у старой записи не меняются.
            DevicesTable.upsert(
                DevicesTable.provider,
                DevicesTable.token,
                onUpdateExclude = listOf(DevicesTable.id, DevicesTable.createdAt),
            ) {
                it[id] = Uuid.random()
                it[this.userId] = userId
                it[this.provider] = provider.dbValue
                it[this.token] = token
                it[webPushP256dh] = webPush?.p256dh
                it[webPushAuth] = webPush?.auth
                it[createdAt] = now.toDb()
                it[updatedAt] = now.toDb()
            }
            val id =
                DevicesTable
                    .select(DevicesTable.id)
                    .where { (DevicesTable.provider eq provider.dbValue) and (DevicesTable.token eq token) }
                    .single()[DevicesTable.id]
            val excess =
                DevicesTable
                    .select(DevicesTable.id)
                    .where { DevicesTable.userId eq userId }
                    .orderBy(DevicesTable.updatedAt to SortOrder.DESC)
                    .map { it[DevicesTable.id] }
                    // Только что зарегистрированное устройство остаётся, даже если время совпало с другим.
                    .filter { it != id }
                    .drop(maxPerUser - 1)
            if (excess.isNotEmpty()) DevicesTable.deleteWhere { DevicesTable.id inList excess }
            id
        }

    /** Удаляет устройство пользователя; чужое или несуществующее не трогает. */
    suspend fun delete(
        userId: Uuid,
        deviceId: Uuid,
    ) {
        db.query { DevicesTable.deleteWhere { (DevicesTable.id eq deviceId) and (DevicesTable.userId eq userId) } }
    }

    /** Удаляет устройство, которое push-сервис больше не знает. */
    suspend fun deleteGone(deviceId: Uuid) {
        db.query { DevicesTable.deleteWhere { DevicesTable.id eq deviceId } }
    }

    /** Устройства пользователей [userIds] в каналах [providers]. */
    suspend fun findByUsers(
        userIds: Collection<Uuid>,
        providers: Set<PushProvider>,
    ): List<DeviceRecord> {
        if (userIds.isEmpty() || providers.isEmpty()) return emptyList()
        return db.query {
            DevicesTable
                .selectAll()
                .where { (DevicesTable.userId inList userIds) and (DevicesTable.provider inList providers.map { it.dbValue }) }
                .map { it.toRecord() }
        }
    }
}

private fun ResultRow.toRecord(): DeviceRecord {
    val p256dh = this[DevicesTable.webPushP256dh]
    val auth = this[DevicesTable.webPushAuth]
    return DeviceRecord(
        id = this[DevicesTable.id],
        userId = this[DevicesTable.userId],
        provider = pushProviderFromDb(this[DevicesTable.provider]),
        token = this[DevicesTable.token],
        webPush = if (p256dh != null && auth != null) WebPushKeys(p256dh, auth) else null,
    )
}
