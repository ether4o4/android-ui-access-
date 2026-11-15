package com.ether4o4.odcm.agent

import android.content.Context

/**
 * Sends a reply back to the chat that gave the task, through the stored notification reply action.
 * A null target means the task came from the app's own UI; the reply then shows only in the overlay/log.
 */
object Replies {
    fun send(context: Context, replyKey: String?, text: String) {
        if (replyKey == null) return
        com.ether4o4.odcm.inbox.InboxListener.reply(replyKey, text)
    }
}
