package ru.ryadom.android.volunteer

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Приложение, которому уведомление передаёт «Пропустить». */
class RecordingHostApp :
    Application(),
    VolunteerHost {
    val skipped = mutableListOf<String>()

    override fun skipIncomingCall(requestId: String) {
        skipped += requestId
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru", application = RecordingHostApp::class)
class IncomingCallNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication() as RecordingHostApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Уведомление открывает главный экран приложения; в тесте модуля его нет — регистрируем.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val main =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = app.packageName
                        name = "ru.ryadom.android.MainActivity"
                    }
            }
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, main)
    }

    private fun notification(requestId: String): Notification? = shadowOf(manager).getNotification(requestId, NOTIFICATION_ID)

    @Test
    fun callNotificationRingsAndOpensFullScreen() {
        IncomingCallNotifications.show(app, "request-1")

        val shown = requireNotNull(notification("request-1")) { "уведомления нет" }
        assertEquals(Notification.CATEGORY_CALL, shown.category)
        assertNotNull("на заблокированном телефоне вызов открывается на весь экран", shown.fullScreenIntent)
        assertTrue("звонит, пока не уберут", shown.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, shown.visibility)
        // Оформление звонка: подписи кнопок — системные («Отклонить», «Ответить»), как у звонилки.
        assertEquals(listOf("Отклонить", "Ответить"), shown.actions.map { it.title.toString() })
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(shown.channelId).importance)
        assertEquals(setOf("request-1"), IncomingCallNotifications.shown(app))
    }

    @Test
    fun notificationIntentsCarryTheRequest() {
        IncomingCallNotifications.show(app, "request-1")

        val open = shadowOf(requireNotNull(notification("request-1")).contentIntent).savedIntent
        assertEquals(IncomingCallNotifications.ACTION_OPEN, open.action)
        assertEquals("request-1", open.getStringExtra(IncomingCallNotifications.EXTRA_REQUEST_ID))
        val accept = shadowOf(notification("request-1")!!.actions[1].actionIntent).savedIntent
        assertEquals(IncomingCallNotifications.ACTION_ACCEPT, accept.action)
    }

    @Test
    fun closedCallsAreRemoved() {
        IncomingCallNotifications.show(app, "request-1")
        IncomingCallNotifications.show(app, "request-2")
        IncomingCallNotifications.show(app, "request-3")

        IncomingCallNotifications.cancel(app, "request-1")
        IncomingCallNotifications.cancelAllExcept(app, keep = setOf("request-3"))

        assertEquals(setOf("request-3"), IncomingCallNotifications.shown(app))
    }

    @Test
    fun withoutPermissionNothingIsShown() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        IncomingCallNotifications.show(app, "request-1")

        assertEquals(emptySet<String>(), IncomingCallNotifications.shown(app))
    }

    @Test
    fun skipFromNotificationRemovesItAndTellsTheApp() {
        IncomingCallNotifications.show(app, "request-1")
        val skip = shadowOf(notification("request-1")!!.actions[0].actionIntent).savedIntent

        IncomingCallActionReceiver().onReceive(app, skip)

        assertEquals(listOf("request-1"), app.skipped)
        assertEquals(emptySet<String>(), IncomingCallNotifications.shown(app))
    }

    private companion object {
        /** Как в IncomingCallNotifications. */
        const val NOTIFICATION_ID = 2
    }
}
