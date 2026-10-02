package ru.ryadom.backend.db

import ru.ryadom.shared.api.Gender
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.Role

// Перевод значений между Kotlin и базой. В базе — те же строки, что в API (`blind`, `ru`, `male`, `in_call`),
// а «не указано» хранится как NULL.

internal val Role.dbValue: String get() = name.lowercase()
internal val Language.dbValue: String get() = name.lowercase()
internal val RequestStatus.dbValue: String get() = name.lowercase()

internal fun Gender.toDb(): String? = if (this == Gender.UNSPECIFIED) null else name.lowercase()

internal fun GenderPreference.toDb(): String? = if (this == GenderPreference.ANY) null else name.lowercase()

internal fun roleFromDb(value: String?): Role? = value?.let { Role.valueOf(it.uppercase()) }

internal fun languageFromDb(value: String): Language = Language.valueOf(value.uppercase())

internal fun genderFromDb(value: String?): Gender = value?.let { Gender.valueOf(it.uppercase()) } ?: Gender.UNSPECIFIED

internal fun genderPreferenceFromDb(value: String?): GenderPreference =
    value?.let { GenderPreference.valueOf(it.uppercase()) } ?: GenderPreference.ANY

internal fun requestStatusFromDb(value: String): RequestStatus = RequestStatus.valueOf(value.uppercase())
