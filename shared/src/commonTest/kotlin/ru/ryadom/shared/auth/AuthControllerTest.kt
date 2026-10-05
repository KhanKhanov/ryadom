package ru.ryadom.shared.auth

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.InMemorySessionStorage
import ru.ryadom.shared.client.SessionEndReason
import ru.ryadom.shared.client.SessionStorage
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.testing.FakeNetworkFailure
import ru.ryadom.shared.testing.FakeResponse
import ru.ryadom.shared.testing.FakeServer
import ru.ryadom.shared.testing.authResponse
import ru.ryadom.shared.testing.profile
import ru.ryadom.shared.testing.signedInStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthControllerTest {
    private class Setup(
        scope: TestScope,
        val storage: SessionStorage,
    ) {
        val server = FakeServer(StandardTestDispatcher(scope.testScheduler))
        val api = ApiClient("http://server", server.engine, storage, now = { scope.testScheduler.currentTime })
        val controller = AuthController(api, scope.backgroundScope, timeZoneId = { "Asia/Yekaterinburg" })
        val screen get() = controller.state.value.screen
        val error get() = controller.state.value.error
    }

    private fun TestScope.signedIn() = Setup(this, signedInStorage())

    private fun TestScope.signedOut() = Setup(this, InMemorySessionStorage())

    @Test
    fun withoutSavedLoginShowsLogin() =
        runTest {
            val s = signedOut()

            s.controller.start()

            assertEquals(AuthScreen.Login(), s.screen)
        }

    @Test
    fun savedLoginLoadsProfile() =
        runTest {
            val s = signedIn()
            s.server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.BLIND)) }

            s.controller.start()
            assertEquals(AuthScreen.Loading, s.screen)
            runCurrent()

            assertEquals(AuthScreen.SignedIn(profile(Role.BLIND)), s.screen)
        }

    @Test
    fun profileChangedElsewhereIsShown() =
        runTest {
            val s = signedIn()
            s.server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.VOLUNTEER)) }
            s.controller.start()
            runCurrent()
            val changed = profile(Role.VOLUNTEER).copy(notificationsEnabled = false)

            s.controller.profileChanged(changed)
            assertEquals(AuthScreen.SignedIn(changed), s.screen)

            // Профиль другого пользователя (опоздавший ответ после смены входа) не показывается.
            s.controller.profileChanged(changed.copy(id = "someone-else", role = Role.BLIND))
            assertEquals(AuthScreen.SignedIn(changed), s.screen)
        }

    @Test
    fun offlineAtStartCanBeRetried() =
        runTest {
            val s = signedIn()
            s.server.on("GET", "/me") { throw FakeNetworkFailure() }
            s.controller.start()
            runCurrent()
            assertEquals(AuthScreen.Offline, s.screen)
            assertEquals(UserError.NETWORK, s.error)

            s.server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.BLIND)) }
            s.controller.retry()
            runCurrent()

            assertEquals(AuthScreen.SignedIn(profile(Role.BLIND)), s.screen)
        }

    @Test
    fun retryDoesNothingOutsideOfflineScreen() =
        runTest {
            val s = signedIn()
            s.server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.BLIND)) }
            s.controller.start()
            runCurrent()

            // Платформа вызывает retry() при каждом появлении сети.
            s.controller.retry()
            runCurrent()

            assertEquals(AuthScreen.SignedIn(profile(Role.BLIND)), s.screen)
            assertEquals(1, s.server.requestsTo("GET", "/me").size)
        }

    @Test
    fun unknownTimeZoneDoesNotBlockChoosingRole() =
        runTest {
            val s = signedOut()
            s.server.on("POST", "/auth/dev") { FakeServer.ok(AuthResponse.serializer(), authResponse(role = null)) }
            s.server.on("PATCH", "/me") { request ->
                if ("timezone" in request.body.orEmpty()) {
                    FakeServer.error(400, "invalid_request")
                } else {
                    FakeServer.ok(UserProfile.serializer(), profile(Role.BLIND))
                }
            }
            s.controller.start()
            s.controller.loginDev("blind-1")
            runCurrent()

            s.controller.chooseRole(SelectableRole.BLIND)
            runCurrent()

            assertEquals(AuthScreen.SignedIn(profile(Role.BLIND)), s.screen)
            assertNull(s.error)
            assertEquals(
                listOf("""{"role":"blind","timezone":"Asia/Yekaterinburg"}""", """{"role":"blind"}"""),
                s.server.requestsTo("PATCH", "/me").map { it.body },
            )
        }

    @Test
    fun otherErrorsOfChoosingRoleAreNotRetriedWithoutTimeZone() =
        runTest {
            val s = signedOut()
            s.server.on("POST", "/auth/dev") { FakeServer.ok(AuthResponse.serializer(), authResponse(role = null)) }
            s.server.on("PATCH", "/me") { throw FakeNetworkFailure() }
            s.controller.start()
            s.controller.loginDev("blind-1")
            runCurrent()

            s.controller.chooseRole(SelectableRole.BLIND)
            runCurrent()

            assertEquals(AuthScreen.ChooseRole(profile(role = null)), s.screen)
            assertEquals(UserError.NETWORK, s.error)
            assertEquals(1, s.server.requestsTo("PATCH", "/me").size)
        }

    @Test
    fun firstLoginAsksForRoleAndSavesTimeZone() =
        runTest {
            val s = signedOut()
            s.server.on("POST", "/auth/dev") { FakeServer.ok(AuthResponse.serializer(), authResponse(role = null)) }
            s.server.on("PATCH", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.BLIND)) }
            s.controller.start()

            s.controller.loginDev("blind-1")
            runCurrent()
            assertEquals(AuthScreen.ChooseRole(profile(role = null)), s.screen)

            s.controller.chooseRole(SelectableRole.BLIND)
            runCurrent()

            assertEquals(AuthScreen.SignedIn(profile(Role.BLIND)), s.screen)
            assertEquals(
                """{"role":"blind","timezone":"Asia/Yekaterinburg"}""",
                s.server
                    .requestsTo("PATCH", "/me")
                    .single()
                    .body,
            )
        }

    @Test
    fun volunteerByMistakeCanSwitchToAskingForHelp() =
        runTest {
            val s = signedIn()
            s.server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.VOLUNTEER)) }
            s.server.on("PATCH", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.BLIND)) }
            s.controller.start()
            runCurrent()

            s.controller.changeRole(SelectableRole.BLIND)
            runCurrent()

            assertEquals(AuthScreen.SignedIn(profile(Role.BLIND)), s.screen)
            // Часовой пояс сохранил первый выбор роли — второй раз его не трогаем.
            assertEquals(
                """{"role":"blind"}""",
                s.server
                    .requestsTo("PATCH", "/me")
                    .single()
                    .body,
            )
        }

    @Test
    fun roleIsNotChangedDuringRequestOrCall() =
        runTest {
            val s = signedIn()
            s.server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.VOLUNTEER)) }
            s.server.on("PATCH", "/me") { FakeServer.error(409, "active_request_exists") }
            s.controller.start()
            runCurrent()

            s.controller.changeRole(SelectableRole.BLIND)
            runCurrent()

            assertEquals(AuthScreen.SignedIn(profile(Role.VOLUNTEER)), s.screen)
            assertEquals(UserError.ACTIVE_REQUEST, s.error)
        }

    @Test
    fun yandexLoginUsesProviderToken() =
        runTest {
            val s = signedOut()
            s.server.on("POST", "/auth/oauth/yandex") { FakeServer.ok(AuthResponse.serializer(), authResponse()) }
            s.controller.start()

            s.controller.loginYandex("yandex-token")
            runCurrent()

            assertEquals(AuthScreen.SignedIn(profile(Role.BLIND)), s.screen)
        }

    @Test
    fun loginErrorsAreExplained() =
        runTest {
            val s = signedOut()
            s.controller.start()

            s.server.on("POST", "/auth/oauth/yandex") { FakeServer.error(401, "oauth_failed") }
            s.controller.loginYandex("bad")
            runCurrent()
            assertEquals(UserError.LOGIN_FAILED, s.error)

            // Вход без OAuth выключен на этом сервере.
            s.controller.loginDev("blind-1")
            runCurrent()
            assertEquals(UserError.LOGIN_UNAVAILABLE, s.error)
            assertEquals(AuthScreen.Login(), s.screen)
        }

    @Test
    fun bannedUserSeesWhy() =
        runTest {
            val s = signedOut()
            s.server.on("POST", "/auth/dev") { FakeServer.error(403, "user_banned") }
            s.controller.start()

            s.controller.loginDev("blind-1")
            runCurrent()

            assertEquals(AuthScreen.Login(SessionEndReason.BANNED), s.screen)
        }

    @Test
    fun expiredSessionReturnsToLoginWithReason() =
        runTest {
            val s = signedIn()
            s.server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.BLIND)) }
            s.controller.start()
            runCurrent()

            // Сеанс закончился где-то ещё (например, в контроллере незрячего).
            s.api.endSession(SessionEndReason.EXPIRED)
            runCurrent()

            assertEquals(AuthScreen.Login(SessionEndReason.EXPIRED), s.screen)
        }

    @Test
    fun logoutReturnsToLoginWithoutReason() =
        runTest {
            val s = signedIn()
            s.server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile(Role.BLIND)) }
            s.server.on("POST", "/auth/logout") { FakeResponse(204) }
            s.controller.start()
            runCurrent()

            s.controller.logout()
            runCurrent()

            assertEquals(AuthScreen.Login(), s.screen)
            assertNull(s.storage.load())
        }
}
