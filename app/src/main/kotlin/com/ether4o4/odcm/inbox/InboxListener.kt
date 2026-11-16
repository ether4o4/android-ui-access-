package com.ether4o4.odcm.inbox

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ether4o4.odcm.agent.AgentService
import com.ether4o4.odcm.core.Deduper
import com.ether4o4.odcm.core.InboxMatcher
import com.ether4o4.odcm.data.Settings

/**
 * Watches messaging notifications for a whitelisted chat and turns a matching message into a task.
 * The system delivers these, so ODCM does no polling and nothing runs while idle.
 */
class InboxListener : NotificationListenerService() {

    private val deduper = Deduper()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val settings = Settings(this)
        val matcher = InboxMatcher(
            allowedPackages = InboxMatcher.TELEGRAM_PACKAGES,
            chatName = settings.inboxChat,
            passphrase = settings.inboxPassphrase,
        )
        if (!matcher.enabled) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()

        val task = matcher.match(sbn.packageName, title, text) ?: return
        if (!deduper.firstTime(sbn.key + "|" + text)) return

        val replyKey = storeReply(sbn)
        AgentService.start(this, task, replyKey)
    }

    /** Captures the notification's inline-reply action so the agent can answer in the same chat. */
    private fun storeReply(sbn: StatusBarNotification): String? {
        val action = sbn.notification.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.resultKey != null } == true
        } ?: return null
        val remoteInput = action.remoteInputs?.firstOrNull { it.resultKey != null } ?: return null
        val key = sbn.key
        pending[key] = PendingReply(action.actionIntent, remoteInput)
        return key
    }

    override fun onListenerConnected() { self = this }
    override fun onListenerDisconnected() { if (self === this) self = null }
    override fun onDestroy() { if (self === this) self = null; super.onDestroy() }

    private data class PendingReply(val intent: android.app.PendingIntent, val remoteInput: RemoteInput)

    companion object {
        @Volatile private var self: InboxListener? = null
        private val pending = HashMap<String, PendingReply>()

        fun reply(key: String, text: String) {
            val p = pending[key] ?: return
            val local = Intent()
            val bundle = Bundle().apply { putCharSequence(p.remoteInput.resultKey, text) }
            RemoteInput.addResultsToIntent(arrayOf(p.remoteInput), local, bundle)
            runCatching { p.intent.send(self, 0, local) }
        }
    }
}
