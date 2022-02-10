/*
 * Copyright (C) 2015 The Nevolution Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.nevo.decorators.wechat

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.N
import android.os.Build.VERSION_CODES.O
import android.os.Build.VERSION_CODES.P
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.ArrayMap
import android.util.Log
import android.util.LongSparseArray
import android.util.Pair
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification
import java.util.*

/**
 * Build the modernized [MessagingStyle] for WeChat conversation.
 *
 * Refactored by Oasis on 2018-8-9.
 */
internal class MessagingBuilder(private val mContext: Context, private val mController: Controller) {

    fun buildFromArchive(conversation: Conversation, n: Notification, title: CharSequence, archive: List<StatusBarNotification>): NotificationCompat.MessagingStyle? {
        // Chat history in big content view
        if (archive.isEmpty())
            return null.also { Log.d(TAG, "No history") }
        val lines = LongSparseArray<Pair<CharSequence, CharSequence>>(MAX_NUM_HISTORICAL_LINES)
        var count = 0
        var numLinesWithColon = 0
        val redundantPrefix = title.toString() + SENDER_MESSAGE_SEPARATOR
        for (each in archive) {
            val notification = each.notification
            val itsExtras = notification.extras
            val itsTitle = EmojiTranslator.translate(itsExtras.getCharSequence(Notification.EXTRA_TITLE))
            if (title != itsTitle) {
                Log.d(TAG, "Skip other conversation with the same key in archive: $itsTitle") // ID reset by WeChat due to notification removal in previous evolving
                continue
            }
            val itsText = itsExtras.getCharSequence(Notification.EXTRA_TEXT)
            if (itsText == null) {
                Log.w(TAG, "No text in archived notification.")
                continue
            }
            val result = trimAndExtractLeadingCounter(itsText)
            if (result >= 0) {
                count = result and 0xFFFF
                var trimmedText = itsText.subSequence(result shr 16, itsText.length)
                if (trimmedText.toString().startsWith(redundantPrefix)) // Remove redundant prefix
                    trimmedText = trimmedText.subSequence(
                        redundantPrefix.length,
                        trimmedText.length
                    ) else if (trimmedText.toString().indexOf(
                        SENDER_MESSAGE_SEPARATOR
                    ) > 0
                ) numLinesWithColon++
                lines.put(notification.`when`, Pair(trimmedText, notification.tickerText))
            } else {
                count = 1
                lines.put(notification.`when`, Pair(itsText, n.tickerText))
                if (itsText.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) numLinesWithColon++
            }
        }
        n.number = count
        if (lines.size() == 0) {
            Log.w(TAG, "No lines extracted, expected $count")
            return null
        }
        val messaging = NotificationCompat.MessagingStyle(mUserSelf)
        val senderInline = numLinesWithColon == lines.size()
        var i = 0
        val size = lines.size()
        while (i < size) { // All lines have colon in text
            val line = lines.valueAt(i)
            messaging.addMessage(buildMessage(conversation, lines.keyAt(i), line.second, line.first,
                if (senderInline) null else title.toString()))
            i++
        }
        Log.i(TAG, "Built from archive.")
        return messaging
    }

    fun buildFromConversation(conversation: Conversation, sbn: MutableStatusBarNotification): NotificationCompat.MessagingStyle? {
        val ext = conversation.ext ?: return null
        val n = sbn.notification
        val latestTimestamp = ext.latestTimestamp
        if (latestTimestamp > 0) {
            conversation.timestamp = latestTimestamp
            n.`when` = latestTimestamp
        }
        val onReply = ext.replyPendingIntent
        val onRead = ext.readPendingIntent
        if (onRead != null) mMarkReadPendingIntents[sbn.key] = onRead // Mapped by evolved key,
        val messages = buildMessages(conversation)
        val remoteInput = ext.remoteInput
        if (SDK_INT >= N && onReply != null && remoteInput != null && conversation.isChat()) {
            val inputHistory = n.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            val proxy = proxyDirectReply(conversation.nid, sbn, onReply, remoteInput, inputHistory)
            val replyRemoteInput = RemoteInput.Builder(remoteInput.resultKey).addExtras(remoteInput.extras)
                .setAllowFreeFormInput(true).setChoices(SmartReply.generateChoices(messages))
            val participant =
                ext.participant // No need to getParticipants() due to actually only one participant at most, see CarExtender.Builder().
            if (BuildConfig.DEBUG && conversation.id != null)
                replyRemoteInput.setLabel(conversation.id)
            else if (participant != null)
                replyRemoteInput.setLabel(participant)
            val replyAction = Notification.Action.Builder(null, mContext.getString(R.string.action_reply), proxy)
                .addRemoteInput(replyRemoteInput.build()).setAllowGeneratedReplies(true)
            if (SDK_INT >= P) replyAction.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            n.addAction(replyAction.build())
        }
        val messaging = NotificationCompat.MessagingStyle(mUserSelf)
        for (message in messages) messaging.addMessage(message)
        return messaging
    }

    /** Intercept the PendingIntent in RemoteInput to update the notification with replied message upon success.  */
    private fun proxyDirectReply(cid: Int, sbn: MutableStatusBarNotification, onReply: PendingIntent,
                                 remoteInput: RemoteInput, inputHistory: Array<CharSequence>?): PendingIntent {
        val proxy = Intent(ACTION_REPLY) // Separate action to avoid PendingIntent overwrite.
            .setData(Uri.fromParts(SCHEME_KEY, sbn.key, null))
            .putExtra(EXTRA_REPLY_ACTION, onReply).putExtra(EXTRA_RESULT_KEY, remoteInput.resultKey)
            .putExtra(EXTRA_ORIGINAL_KEY, sbn.originalKey).putExtra(EXTRA_CONVERSATION_ID, cid)
            .putExtra(Intent.EXTRA_USER, sbn.user)
        if (SDK_INT >= N && inputHistory != null)
            proxy.putCharSequenceArrayListExtra(Notification.EXTRA_REMOTE_INPUT_HISTORY, arrayListOf(*inputHistory))
        return PendingIntent.getBroadcast(mContext, 0, proxy.setPackage(mContext.packageName), PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private val mReplyReceiver: BroadcastReceiver = object : BroadcastReceiver() { override fun onReceive(context: Context, proxy: Intent) {
        val replyAction = proxy.getParcelableExtra<PendingIntent>(EXTRA_REPLY_ACTION)
        val resultKey = proxy.getStringExtra(EXTRA_RESULT_KEY)
        val replyPrefix = proxy.getStringExtra(EXTRA_REPLY_PREFIX)
        val data = proxy.data
        val results = RemoteInput.getResultsFromIntent(proxy)
        val user = proxy.getParcelableExtra<UserHandle>(Intent.EXTRA_USER)
        val input = results?.getCharSequence(resultKey)
        if (data == null || replyAction == null || resultKey == null || input == null || user == null)
            return  // Should never happen
        val key = data.schemeSpecificPart
        val originalKey = proxy.getStringExtra(EXTRA_ORIGINAL_KEY)
        if (BuildConfig.DEBUG && input.toString() == "debug") {
            val conversation = mController.getConversation(user, proxy.getIntExtra(EXTRA_CONVERSATION_ID, 0))
            if (conversation != null)
                showDebugNotification(mContext, conversation, "Type: " + conversation.typeToString())
            return mController.recastNotification(originalKey ?: key, null)
        }
        val text: CharSequence = replyPrefix?.plus(input)?.also {
            results.putCharSequence(resultKey, it)
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(resultKey).build()), proxy, results)
        } ?: input

        val history = if (SDK_INT >= N) proxy.getCharSequenceArrayListExtra(Notification.EXTRA_REMOTE_INPUT_HISTORY) else null
        try {
            val inputData = addTargetPackageAndWakeUp(replyAction)
            inputData.clipData = proxy.clipData
            replyAction.send(mContext, 0, inputData, PendingIntent.OnFinished { pendingIntent: PendingIntent, intent: Intent, _: Int, _: String?, _: Bundle? ->
                if (BuildConfig.DEBUG) Log.d(TAG, "Reply sent: " + intent.toUri(0))
                if (SDK_INT >= N) {
                    val addition = Bundle()
                    val inputs: Array<CharSequence>
                    val toCurrentUser = Process.myUserHandle() == pendingIntent.creatorUserHandle
                    inputs = if (toCurrentUser && context.packageManager.queryBroadcastReceivers(intent, 0).isEmpty())
                        arrayOf(context.getString(R.string.wechat_with_no_reply_receiver))
                    else history?.apply { add(0, text)  }?.toTypedArray() ?: arrayOf(text)
                    addition.putCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY, inputs)
                    mController.recastNotification(originalKey ?: key, addition)
                }
                markRead(key)
            }, null)
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply action is already cancelled: $key")
            abortBroadcast()
        }
    }}

    /** @param key the evolved key */
    fun markRead(key: String) {
        val action = mMarkReadPendingIntents.remove(key) ?: return
        try { action.send(mContext, 0, addTargetPackageAndWakeUp(action)) }
        catch (e: PendingIntent.CanceledException) { Log.w(TAG, "Mark-read action is already cancelled: $key") }
    }

    internal interface Controller {
        fun recastNotification(key: String, addition: Bundle?)
        fun getConversation(user: UserHandle, id: Int): Conversation?
    }

    fun close() {
        try { mContext.unregisterReceiver(mReplyReceiver) } catch (e: RuntimeException) {}
    }

    init {
        mContext.registerReceiver(mReplyReceiver, IntentFilter(ACTION_REPLY).apply { addDataScheme(SCHEME_KEY) })
    }

    private val mUserSelf: Person = buildPersonFromProfile(mContext)
    private val mMarkReadPendingIntents: MutableMap<String, PendingIntent> = ArrayMap()

    companion object {
        private const val MAX_NUM_HISTORICAL_LINES = 10
        private const val ACTION_REPLY = "REPLY"
        private const val SCHEME_KEY = "key"
        private const val EXTRA_REPLY_ACTION = "pending_intent"
        private const val EXTRA_RESULT_KEY = "result_key"
        private const val EXTRA_ORIGINAL_KEY = "original_key"
        private const val EXTRA_REPLY_PREFIX = "reply_prefix"
        private const val EXTRA_CONVERSATION_ID = "cid"
        private const val KEY_TEXT = "text"
        private const val KEY_TIMESTAMP = "time"
        private const val KEY_SENDER = "sender"

        @RequiresApi(P) private val KEY_SENDER_PERSON = "sender_person"
        private const val KEY_DATA_MIME_TYPE = "type"
        private const val KEY_DATA_URI = "uri"
        private const val KEY_EXTRAS_BUNDLE = "extras"

        fun showDebugNotification(context: Context, convs: Conversation, summary: String?) {
            val bigText = StringBuilder().append(convs.summary).append("\nT:").append(convs.ticker)
            val messages = if (convs.ext != null) convs.ext!!.messages else null
            if (messages != null) for (msg in messages) bigText.append("\n").append(msg)
            val n = Notification.Builder(context).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(convs.id).setContentText(convs.ticker).setSubText(summary).setShowWhen(true)
                .setStyle(Notification.BigTextStyle().setBigContentTitle(convs.title).bigText(bigText.toString()))
            if (SDK_INT >= O) n.setChannelId("Debug")
            context.getSystemService(NotificationManager::class.java)
                .notify(if (convs.id != null) convs.id.hashCode() else convs.title.hashCode(), n.build())
        }

        private fun buildMessage(conversation: Conversation, `when`: Long, ticker: CharSequence?, text: CharSequence,
                                 sender: String?): NotificationCompat.MessagingStyle.Message {
            var sender = sender
            var actualText: CharSequence? = text
            if (sender == null) {
                sender = text.extractSenderFromText()
                if (sender != null) {
                    actualText =
                        text.subSequence(sender.length + SENDER_MESSAGE_SEPARATOR.length, text.length)
                    if (TextUtils.equals(conversation.title, sender)) sender =
                        null // In this case, the actual sender is user itself.
                }
            }
            actualText = EmojiTranslator.translate(actualText)
            val person = when {
                sender != null && sender.isEmpty() -> null // Empty string as a special mark for "self"
                conversation.isGroupChat() -> // Group nick is used in ticker and content text, while original nick in sender.
                    sender?.let { conversation.getGroupParticipant(it, ticker?.extractSenderFromText() ?: it) }
                else -> conversation.sender().build()
            }
            return NotificationCompat.MessagingStyle.Message(actualText, `when`, person)
        }

        private fun CharSequence.extractSenderFromText(): String? {
            val posColon = TextUtils.indexOf(this, SENDER_MESSAGE_SEPARATOR)
            return if (posColon > 0) toString().substring(0, posColon) else null
        }

        /** @return the extracted count in 0xFF range and start position in 0xFF00 range */
        private fun trimAndExtractLeadingCounter(text: CharSequence?): Int {
            // Parse and remove the leading "[n]" or [n条/則/…]
            if (text == null || text.length < 4 || text[0] != '[') return -1
            var textStart = 2
            var countEnd: Int
            while (text[textStart++] != ']') if (textStart >= text.length) return -1
            try {
                val num = text.subSequence(1, textStart - 1).toString() // may contain the suffix "条/則"
                countEnd = 0
                while (countEnd < num.length) {
                    if (! Character.isDigit(num[countEnd])) break
                    countEnd++
                }
                if (countEnd == 0) return -1 // Not the expected "unread count"
                val count = num.substring(0, countEnd).toInt()
                if (count < 2) return -1
                return if (count < 0xFFFF) count and 0xFFFF or (textStart shl 16 and -0x10000)
                else 0xFFFF or (textStart shl 16 and 0xFF00)
            } catch (ignored: NumberFormatException) {
                Log.d(TAG, "Failed to parse: $text")
                return -1
            }
        }

        /** Ensure the PendingIntent works even if WeChat is stopped or background-restricted.  */
        private fun addTargetPackageAndWakeUp(action: PendingIntent): Intent {
            return Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setPackage(action.creatorPackage)
        }

        fun flatIntoExtras(messaging: NotificationCompat.MessagingStyle, extras: Bundle) {
            val user = messaging.user
            if (user != null) {
                extras.putCharSequence(NotificationCompat.EXTRA_SELF_DISPLAY_NAME, user.name)
                if (SDK_INT >= P) extras.putParcelable(Notification.EXTRA_MESSAGING_PERSON, user.toNative()) // Not included in NotificationCompat
            }
            if (messaging.conversationTitle != null)
                extras.putCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE, messaging.conversationTitle)
            val messages = messaging.messages
            if (messages.isNotEmpty())
                extras.putParcelableArray(NotificationCompat.EXTRA_MESSAGES, getBundleArrayForMessages(messages))
            //if (! mHistoricMessages.isEmpty()) extras.putParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES, MessagingBuilder.getBundleArrayForMessages(mHistoricMessages));
            extras.putBoolean(NotificationCompat.EXTRA_IS_GROUP_CONVERSATION, messaging.isGroupConversation)
        }

        private fun getBundleArrayForMessages(messages: List<NotificationCompat.MessagingStyle.Message>) =
            messages.map { toBundle(it) }.toTypedArray()

        private fun toBundle(message: NotificationCompat.MessagingStyle.Message) = Bundle().apply {
            putCharSequence(KEY_TEXT, message.text)
            putLong(KEY_TIMESTAMP, message.timestamp) // Must be included even for 0
            message.person?.also { sender ->
                putCharSequence(KEY_SENDER, sender.name) // Legacy listeners need this
                if (SDK_INT >= P) putParcelable(KEY_SENDER_PERSON, sender.toNative())
            }
            if (message.dataMimeType != null) putString(KEY_DATA_MIME_TYPE, message.dataMimeType)
            if (message.dataUri != null) putParcelable(KEY_DATA_URI, message.dataUri)
            if (SDK_INT >= O && !message.extras.isEmpty) putBundle(KEY_EXTRAS_BUNDLE, message.extras)
            //if (message.isRemoteInputHistory()) putBoolean(KEY_REMOTE_INPUT_HISTORY, message.isRemoteInputHistory());
        }

        private fun buildPersonFromProfile(context: Context): Person {
            return Person.Builder().setName(context.getString(R.string.self_display_name)).build()
        }
    }
}

@RequiresApi(P) @SuppressLint("RestrictedApi") fun Person.toNative() = toAndroidPerson()
