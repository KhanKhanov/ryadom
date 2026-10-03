package ru.ryadom.backend.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Описание таблиц для Exposed. Сама схема создаётся миграциями Flyway (resources/db/migration);
// при изменении схемы меняйте и миграцию, и этот файл.

object UsersTable : Table("users") {
    val id = uuid("id")
    val role = text("role").nullable()
    val displayName = text("display_name").nullable()
    val languages = array<String>("languages", TextColumnType())
    val gender = text("gender").nullable()
    val genderPreference = text("gender_preference").nullable()
    val timezone = text("timezone")
    val dndFrom = time("dnd_from").nullable()
    val dndTo = time("dnd_to").nullable()
    val notificationsEnabled = bool("notifications_enabled")
    val bannedAt = timestampWithTimeZone("banned_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object AuthIdentitiesTable : Table("auth_identities") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id)
    val provider = text("provider")
    val subject = text("subject")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object RefreshTokensTable : Table("refresh_tokens") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id)
    val tokenHash = binary("token_hash")
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object HelpRequestsTable : Table("help_requests") {
    val id = uuid("id")
    val blindUserId = uuid("blind_user_id").references(UsersTable.id)
    val language = text("language")
    val genderPreference = text("gender_preference").nullable()
    val status = text("status")
    val acceptedBy = uuid("accepted_by").references(UsersTable.id).nullable()
    val nextWave = integer("next_wave")
    val createdAt = timestampWithTimeZone("created_at")
    val acceptedAt = timestampWithTimeZone("accepted_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object RequestNotificationsTable : Table("request_notifications") {
    val requestId = uuid("request_id").references(HelpRequestsTable.id)
    val volunteerId = uuid("volunteer_id").references(UsersTable.id)
    val wave = integer("wave")
    val sentAt = timestampWithTimeZone("sent_at")
    val result = text("result").nullable()

    override val primaryKey = PrimaryKey(requestId, volunteerId)
}

object DevicesTable : Table("devices") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id)
    val provider = text("push_provider")
    val token = text("token")
    val webPushP256dh = text("webpush_p256dh").nullable()
    val webPushAuth = text("webpush_auth").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object RatingsTable : Table("ratings") {
    val requestId = uuid("request_id").references(HelpRequestsTable.id)
    val fromUserId = uuid("from_user_id").references(UsersTable.id)
    val helped = bool("helped")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(requestId, fromUserId)
}
