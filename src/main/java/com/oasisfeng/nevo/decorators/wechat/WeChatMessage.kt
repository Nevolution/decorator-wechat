package com.oasisfeng.nevo.decorators.wechat

import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation
import kotlin.math.min

/**
 * Parse various fields collected from notification to build a structural message.
 *
 * Known cases
 * -------------
 * Direct message   1 unread  Ticker: "Oasis: Hello",         Title: "Oasis",  Summary: "Hello"                    CarExt: "Hello"
 * Direct message  >1 unread  Ticker: "Oasis: [Link] WTF",    Title: "Oasis",  Summary: "[2]Oasis: [Link] WTF"
 * Service message  1 unread  Ticker: "FedEx: [Link] Status", Title: "FedEx",  Summary: "[Link] Status"			CarExt: "[Link] Status"
 * Service message >1 unread  Ticker: "FedEx: Delivered",     Title: "FedEx",  Summary: "[2]FedEx: Delivered"		CarExt: "[Link] Delivered"
 * Group chat with  1 unread  Ticker: "GroupNick: Hello",     Title: "Group",  Summary: "GroupNick: Hello"			CarExt: "GroupNick: Hello"
 * Group chat with >1 unread  Ticker: "GroupNick: [Link] Mm", Title: "Group",  Summary: "[2]GroupNick: [Link] Mm"	CarExt: "GroupNick: [Link] Mm"
 *
 * Created by Oasis on 2019-4-19.
 */

const val SENDER_MESSAGE_SEPARATOR = ": "
private const val SELF = ""

class WeChatMessage constructor(private val conversation: Conversation, sender: CharSequence?,
                                val text: CharSequence?, private val time: Long) {
    private val sender = sender?.toString()   // Nick defaults to sender
    private val nick = this.sender

    fun toMessage(): NotificationCompat.MessagingStyle.Message {
        val person = if (sender == SELF) null else if (conversation.isGroupChat()) conversation.getGroupParticipant(
            sender!!, nick!!
        ) else conversation.sender().build()
        return NotificationCompat.MessagingStyle.Message(text, time, person)
    }
}

fun buildMessages(conversation: Conversation): List<NotificationCompat.MessagingStyle.Message> {
    val extMessages = conversation.ext?.messages?.takeIf { it.isNotEmpty() } // Sometimes extender messages are empty, for unknown cause.
        ?: return listOf(buildFromBasicFields(conversation).toMessage()) // No messages in car conversation
    val basicMessage = buildFromBasicFields(conversation)
    val messages = ArrayList<NotificationCompat.MessagingStyle.Message>(extMessages.size)
    var endOfPeers = -1
    if (! conversation.isGroupChat()) {
        endOfPeers = extMessages.size - 1
        while (endOfPeers >= -1) {
            if (endOfPeers >= 0 && TextUtils.equals(basicMessage.text, extMessages[endOfPeers])) break // Find the actual end line which matches basic fields, in case extra lines are sent by self
            endOfPeers-- }}
    var i = 0
    val count = extMessages.size
    while (i < count) {
        messages.add(buildFromCarMessage(conversation, extMessages[i], endOfPeers in 0 until i).toMessage())
        i++ }
    return messages
}

private fun buildFromBasicFields(conversation: Conversation): WeChatMessage {
    // Trim the possible trailing white spaces in ticker.
    var ticker = conversation.ticker
    var tickerLength = ticker!!.length
    var tickerEnd = tickerLength
    while (tickerEnd > 0 && ticker[tickerEnd - 1] == ' ') tickerEnd--
    if (tickerEnd != tickerLength) {
        ticker = ticker.subSequence(0, tickerEnd)
        tickerLength = tickerEnd
    }
    var sender: CharSequence? = null
    var text: CharSequence?
    var pos = TextUtils.indexOf(ticker, SENDER_MESSAGE_SEPARATOR)
    var unreadCount = 0
    if (pos > 0) {
        sender = ticker.subSequence(0, pos)
        text = ticker.subSequence(pos + SENDER_MESSAGE_SEPARATOR.length, tickerLength)
    } else text = ticker
    val summary = conversation.summary
    val contentLength = summary!!.length
    var contentWithoutPrefix = summary
    if (contentLength > 3 && summary[0] == '[' && TextUtils.indexOf(summary, ']', 1).also { pos = it } > 0) {
        unreadCount = parsePrefixAsUnreadCount(summary.subSequence(1, pos))
        if (unreadCount > 0) {
            conversation.count = unreadCount
            contentWithoutPrefix = summary.subSequence(pos + 1, contentLength)
        } else if (TextUtils.equals(summary.subSequence(pos + 1, contentLength), text)) conversation.setType(
            Conversation.TYPE_BOT_MESSAGE
        ) // Only bot message omits prefix (e.g. "[Link]")
    }
    if (sender == null) {    // No sender in ticker, blindly trust the sender in summary text.
        pos = TextUtils.indexOf(contentWithoutPrefix, SENDER_MESSAGE_SEPARATOR)
        if (pos > 0) {
            sender = contentWithoutPrefix.subSequence(0, pos)
            text = contentWithoutPrefix.subSequence(pos + 1, contentWithoutPrefix.length)
        } else text = contentWithoutPrefix
    } else if (! startsWith(contentWithoutPrefix, sender, SENDER_MESSAGE_SEPARATOR)) {    // Ensure sender matches (in ticker and summary)
        if (unreadCount > 0) // When unread count prefix is present, sender should also be included in summary.
            Log.e(TAG, "Sender mismatch: \"$sender\" in ticker, summary: " + summary.subSequence(0, min(10, contentLength)))
        if (startsWith(ticker, sender, SENDER_MESSAGE_SEPARATOR)) // Normal case for single unread message
            return WeChatMessage(conversation, sender, contentWithoutPrefix, conversation.timestamp)
    }
    return WeChatMessage(conversation, sender, text, conversation.timestamp)
}

/**
 * Parse unread count prefix in the form of "n" or "n条/則/…".
 * @return unread count, or 0 if unrecognized as unread count
 */
private fun parsePrefixAsUnreadCount(prefix: CharSequence): Int {
    val length = prefix.length
    if (length < 1) return 0
    val count =
        if (length > 1 && !Character.isDigit(prefix[length - 1])) prefix.subSequence(0, length - 1) else prefix
    return try {
        count.toString().toInt()
    } catch (ignored: NumberFormatException) {     // Probably just emoji like "[Cry]"
        Log.d(TAG, "Failed to parse as int: $prefix")
        0
    }
}

fun guessConversationType(conversation: Conversation): Int {
    val ext = conversation.ext
    val messages = ext?.messages
    val numMessages = messages?.size ?: 0
    val lastMessage = if (numMessages > 0) messages!![numMessages - 1] else null
    if (numMessages > 1) {  // Car extender messages with multiple senders are strong evidence for group chat.
        var sender: String? = null
        for (message in messages!!) {
            val splits = message.split(':', limit = 2).toTypedArray()
            if (splits.size < 2) continue
            if (sender == null) sender =
                splits[0] else if (sender != splits[0]) return Conversation.TYPE_GROUP_CHAT // More than one sender
        }
    }
    val content = conversation.summary ?: return Conversation.TYPE_UNKNOWN
    val ticker = conversation.ticker.toString()
        .trim { it <= ' ' } // Ticker text (may contain trailing spaces) always starts with sender (same as title for direct message, but not for group chat).
    // Content text includes sender for group and service messages, but not for direct messages.
    val pos = TextUtils.indexOf(content, ticker) // Seek for the ticker text in content.
    return if (pos in 0..6) {        // Max length (up to 999 unread): [999t]
        // The content without unread count prefix, may or may not start with sender nick
        val contentWithoutCount = if (pos > 0 && content[0] == '[') content.subSequence(pos, content.length) else content
        // content_wo_count.startsWith(title + SENDER_MESSAGE_SEPARATOR)
        if (startsWith(contentWithoutCount, conversation.title, SENDER_MESSAGE_SEPARATOR)) {   // The title of group chat is group name, not the message sender
            val text = contentWithoutCount.subSequence(conversation.title!!.length + SENDER_MESSAGE_SEPARATOR.length,
                contentWithoutCount.length)
            if (startWithBracketedPrefixAndOneSpace(lastMessage, text)) // Ticker: "Bot name: Text", Content: "[2] Bot name: Text", Message: "[Link] Text"
                return Conversation.TYPE_BOT_MESSAGE else if (isBracketedPrefixOnly(lastMessage)) return Conversation.TYPE_BOT_MESSAGE
            return Conversation.TYPE_DIRECT_MESSAGE // Most probably a direct message with more than 1 unread
        }
        Conversation.TYPE_GROUP_CHAT
    } else if (TextUtils.indexOf(ticker, content) >= 0) {
        if (startWithBracketedPrefixAndOneSpace(lastMessage, content)) Conversation.TYPE_BOT_MESSAGE
        else Conversation.TYPE_UNKNOWN // Indistinguishable (direct message with 1 unread, or a service text message without link)
    } else Conversation.TYPE_BOT_MESSAGE // Most probably a service message with link
}

private fun startWithBracketedPrefixAndOneSpace(text: String?, needle: CharSequence): Boolean {
    if (text == null) return false
    val start = text.indexOf(needle.toString())
    return start > 3 && text[0] == '[' && text[start - 1] == ' ' && text[start - 2] == ']'
}

private fun isBracketedPrefixOnly(text: String?): Boolean {
    val length = (text ?: return false).length
    return length in 3..4 && text[0] == '[' && text[length - 1] == ']'
}

private fun startsWith(text: CharSequence?, needle1: CharSequence?, needle2: String): Boolean {
    val needle1Length = needle1!!.length
    val needle2Length = needle2.length
    return (text!!.length > needle1Length + needle2Length && TextUtils.regionMatches(text, 0, needle1, 0, needle1Length)
            && TextUtils.regionMatches(text, needle1Length, needle2, 0, needle2Length))
}

private fun buildFromCarMessage(conversation: Conversation, message: String, from_self: Boolean): WeChatMessage {
    var text: String? = message
    var sender: String? = null
    val pos = if (from_self) 0 else TextUtils.indexOf(message, SENDER_MESSAGE_SEPARATOR)
    if (pos > 0) {
        sender = message.substring(0, pos)
        val titleAsSender = TextUtils.equals(sender, conversation.title)
        if (conversation.isGroupChat() || titleAsSender) {    // Verify the sender with title for non-group conversation
            text = message.substring(pos + SENDER_MESSAGE_SEPARATOR.length)
            if (conversation.isGroupChat() && titleAsSender) sender =
                SELF // WeChat incorrectly use group chat title as sender for self-sent messages.
        } else sender = null // Not really the sender name, revert the parsing result.
    }
    return WeChatMessage(conversation, if (from_self) SELF else sender, EmojiTranslator.translate(text), 0)
}
