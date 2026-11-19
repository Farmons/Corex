package com.insane.island

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

// Через этот сервис Android даёт доступ к музыке. Заодно запоминаем кнопки
// из уведомления плеера (там у VK может быть кнопка лайка).
class MediaListener : NotificationListenerService() {

    companion object {
        val acts = ConcurrentHashMap<String, List<Notification.Action>>()
        @Volatile var onChanged: (() -> Unit)? = null
    }

    private fun key(s: StatusBarNotification) = "${s.packageName}/${s.id}"

    private fun store(s: StatusBarNotification?) {
        if (s == null) return
        val n = s.notification ?: return
        val isMedia = n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            n.category == Notification.CATEGORY_TRANSPORT
        if (isMedia) {
            acts[key(s)] = n.actions?.toList() ?: emptyList()
            onChanged?.invoke()
        }
    }

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { store(it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { store(sbn) }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) acts.remove(key(sbn))
    }
}
