package com.oasisfeng.nevo.decorators.wechat

import android.text.TextUtils
import androidx.core.app.NotificationCompat.MessagingStyle.Message

/**
 * A no-smart implementation for Smart Reply
 *
 * Created by Oasis on 2018-8-10.
 */
internal object SmartReply {
    private val REPLIES_FOR_QUESTION =
        arrayOf(arrayOf<CharSequence>("👌", "好", "对", "没问题"), arrayOf<CharSequence>("👌", "OK", "Ye"))

    fun generateChoices(messages: List<Message>): Array<CharSequence>? {
        if (messages.isEmpty()) return null
        val text = messages[messages.size - 1].text
        val chinese = TextUtils.indexOf(text, '？') >= 0
        return if (chinese || TextUtils.indexOf(text, '?') >= 0) REPLIES_FOR_QUESTION[if (chinese) 0 else 1] else null
    }
}