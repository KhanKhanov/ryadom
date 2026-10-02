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
