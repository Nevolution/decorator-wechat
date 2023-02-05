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

import android.app.Notification.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.media.AudioAttributes
import android.net.Uri
import android.os.*
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.os.Build.VERSION_CODES.P
import android.os.Build.VERSION_CODES.Q
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.REASON_APP_CANCEL
import android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED
import android.util.Log
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.oasisfeng.nevo.decorators.wechat.AgentShortcuts.Companion.buildShortcutId
import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation
import com.oasisfeng.nevo.decorators.wechat.IconHelper.convertToAdaptiveIcon
import com.oasisfeng.nevo.sdk.MutableNotification
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification
import com.oasisfeng.nevo.sdk.NevoDecoratorService
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS

const val WECHAT_PACKAGE = "com.tencent.mm"
const val AGENT_PACKAGE = "com.oasisfeng.nevo.agents.v1.wechat"
private const val DESIRED_BUBBLE_EXPANDED_HEIGHT = 512

/**
 * Bring state-of-art notification experience to WeChat.
 *
 * Created by Oasis on 2015/6/1.
 */
class WeChatDecorator : NevoDecoratorService() {

    companion object {
        private const val IGNORE_CAR_EXTENDER = false // For test purpose
        const val CHANNEL_MESSAGE = "message_channel_new_id"            // Channel ID used by WeChat for all message notifications
        private const val MAX_NUM_ARCHIVED = 20
        private const val NID_LOGIN_CONFIRMATION = 38                   // The static notification ID of WeChat login confirmation
        private const val OLD_CHANNEL_MESSAGE = "message"               //   old name for migration
        private const val CHANNEL_MISC = "reminder_channel_id"          // Channel ID used by WeChat for misc. notifications
        private const val OLD_CHANNEL_MISC = "misc"                     //   old name for migration
        private const val CHANNEL_DND = "message_dnd_mode_channel_id"   // Channel ID used by WeChat for its own DND mode
        private const val CHANNEL_VOIP = "voip_notify_channel_new_id"   // Channel ID used by WeChat for VoIP notification
        private const val CHANNEL_GROUP_CONVERSATION = "group"          // WeChat has no separate group for group conversation
        private const val GROUP_GROUP = "nevo.group.wechat.group"
        private const val GROUP_BOT = "nevo.group.wechat.bot"
        private const val GROUP_DIRECT = "nevo.group.wechat"
        private const val GROUP_MISC = "misc" // Not auto-grouped
        private const val KEY_SERVICE_MESSAGE = "notifymessage"         // Virtual WeChat account for service notification messages
        private const val EXTRA_USERNAME = "Main_User"                  // Extra in content intent

        @ColorInt private val PRIMARY_COLOR = -0xcc4cce
        @ColorInt private val LIGHT_COLOR = -0xff0100
        const val ACTION_SETTINGS_CHANGED = "SETTINGS_CHANGED"
        const val PREFERENCES_NAME = "decorators-wechat"
        private const val EXTRA_SILENT_RECAST = "silent_recast"

        private fun getUser(key: String): UserHandle {
            val posPipe = key.indexOf('|')
            if (posPipe > 0)
                try { return userHandleOf(key.substring(0, posPipe).toInt()) } catch (e: NumberFormatException) {}
            Log.e(TAG, "Invalid key: $key")
            return Process.myUserHandle() // Only correct for single user.
        }

        private fun userHandleOf(user: Int): UserHandle {
            val currentUser = Process.myUserHandle()
            if (user == currentUser.hashCode()) return currentUser
            UserHandle.getUserHandleForUid(user * 100000 + 1)
            val parcel = Parcel.obtain()
            try { return UserHandle(parcel.apply { writeInt(user); setDataPosition(0) }) }
            finally { parcel.recycle() }
        }

        private fun buildParcelableWithFileDescriptor(): Parcelable? {
            try { return if (SDK_INT >= Q) SharedMemory.create(null, 1) else ParcelFileDescriptor.createPipe()[0] }
            catch (e: Exception) { Log.e(TAG, "Partially incompatible ROM: " + e.message) }
            return null
        }
    }

    public override fun apply(evolving: MutableStatusBarNotification): Boolean {
        val n = evolving.notification
        val flags = n.flags
        if (flags and FLAG_GROUP_SUMMARY != 0) {
            n.extras.putCharSequence(EXTRA_SUB_TEXT, getText(when(n.group) {
                GROUP_GROUP -> R.string.header_group_chat
                GROUP_BOT   -> R.string.header_bot_message
                else        -> return false }))
            return true
        }
        val extras = n.extras
        val title = extras.getCharSequence(EXTRA_TITLE)
        val channel = n.channelId
        if (title.isNullOrEmpty()) return false.also { Log.e(TAG, "Title is missing: $evolving") }
        if (flags and FLAG_ONGOING_EVENT != 0 && channel == CHANNEL_VOIP) return false

        n.color = PRIMARY_COLOR // Tint the small icon
        extras.putBoolean(EXTRA_SHOW_WHEN, true)
        if (isEnabled(mPrefKeyWear)) n.flags = n.flags and FLAG_LOCAL_ONLY.inv()   // Remove FLAG_LOCAL_ONLY
        if (n.tickerText == null /* Legacy misc. notifications */ || channel == CHANNEL_MISC) {
            if (channel == null) n.channelId = CHANNEL_MISC
            n.group = GROUP_MISC // Avoid being auto-grouped
            if (evolving.id == NID_LOGIN_CONFIRMATION)
                n.timeoutAfter = (5 * 60000).toLong() // The actual timeout for login confirmation is a little shorter than 5 minutes.
            Log.d(TAG, "Skip further process for non-conversation notification: $title") // E.g. web login confirmation notification.
            return flags and FLAG_FOREGROUND_SERVICE == 0
        }
        val contentText = extras.getCharSequence(EXTRA_TEXT) ?: return true

        val inputHistory = extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY)
        if (inputHistory != null || extras.getBoolean(EXTRA_SILENT_RECAST)) n.flags = n.flags or FLAG_ONLY_ALERT_ONCE

        val profile = evolving.user
        val conversation = mConversationManager.getOrCreateConversation(profile, evolving.originalId).also {
            it.icon = IconCompat.createFromIcon(this, n.getLargeIcon() ?: n.smallIcon)
            it.title = title; it.summary = contentText; it.ticker = n.tickerText; it.timestamp = n.`when`
            it.ext = if (IGNORE_CAR_EXTENDER) null else CarExtender(n).unreadConversation
        }

        val originalKey = evolving.originalKey
        var messaging = mMessagingBuilder.buildFromConversation(conversation, evolving)
        if (messaging == null) // EXTRA_TEXT will be written in buildFromArchive()
            messaging = mMessagingBuilder.buildFromArchive(conversation, n, title,
                getArchivedNotifications(originalKey, MAX_NUM_ARCHIVED))
        if (messaging == null) return true
        val messages = messaging.messages
        if (messages.isEmpty()) return true
        if (conversation.id == null && mActivityBlocker != null) try {
            val latch = CountDownLatch(1)
            n.contentIntent.send(this, 0, Intent().putExtra("", mActivityBlocker), { _: PendingIntent?, intent: Intent, _: Int, _: String?, _: Bundle? ->
                val id = intent.getStringExtra(EXTRA_USERNAME) ?: return@send Unit.also {
                    Log.e(TAG, "Unexpected null ID received for conversation: " + conversation.title) }
                conversation.id = id // setType() below will trigger rebuilding of conversation sender.
                latch.countDown()
                if (BuildConfig.DEBUG && id.hashCode() != conversation.nid) Log.e(TAG, "NID is not hash code of CID")
            }, null)
            try {
                if (latch.await(100, MILLISECONDS)) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Conversation ID retrieved: " + conversation.id)
                } else Log.w(TAG, "Timeout retrieving conversation ID")
            } catch (_: InterruptedException) {}
        } catch (_: PendingIntent.CanceledException) {}

        val cid = conversation.id
        if (cid == null) {
            if (conversation.isTypeUnknown())
                conversation.setType(guessConversationType(conversation))
        } else conversation.setType(when {
            cid.endsWith("@chatroom") || cid.endsWith("@im.chatroom") -> Conversation.TYPE_GROUP_CHAT   // @im.chatroom is WeWork
            cid.startsWith("gh_") || cid == KEY_SERVICE_MESSAGE -> Conversation.TYPE_BOT_MESSAGE
            cid.endsWith("@openim") -> Conversation.TYPE_DIRECT_MESSAGE
            else -> Conversation.TYPE_UNKNOWN
        })
        if (SDK_INT >= VERSION_CODES.R && inputHistory != null) { // EXTRA_REMOTE_INPUT_HISTORY is no longer supported on Android R.
            for (i in inputHistory.indices.reversed())  // Append them to messages in MessagingStyle.
                messages.add(NotificationCompat.MessagingStyle.Message(inputHistory[i], 0L, null as Person?))
            extras.remove(EXTRA_REMOTE_INPUT_HISTORY)
        }
        val isGroupChat = conversation.isGroupChat()
        if (SDK_INT >= P && KEY_SERVICE_MESSAGE == cid) {  // Setting conversation title before Android P will make it a group chat.
            messaging.conversationTitle = getString(R.string.header_service_message) // A special header for this non-group conversation with multiple senders
            n.group = GROUP_BOT
        } else n.group = if (isGroupChat) GROUP_GROUP else if (conversation.isBotMessage()) GROUP_BOT else GROUP_DIRECT
        if (isGroupChat && mUseExtraChannels && channel != CHANNEL_DND) n.channelId = CHANNEL_GROUP_CONVERSATION
        else if (channel == null) n.channelId = CHANNEL_MESSAGE // WeChat versions targeting O+ have its own channel for message     }
        if (isGroupChat) messaging.setGroupConversation(true).conversationTitle = title
        MessagingBuilder.flatIntoExtras(messaging, extras)
        extras.putString(EXTRA_TEMPLATE, TEMPLATE_MESSAGING)

        if (SDK_INT > Q) maybeAddBubbleMetadata(n, conversation, profile)
        return true
    }

    @RequiresApi(VERSION_CODES.R)
    private fun maybeAddBubbleMetadata(n: MutableNotification, conversation: Conversation, profile: UserHandle) {
        if (! conversation.isChat() || conversation.isBotMessage()) return
        val cid = conversation.id ?: return
        val shortcutId = buildShortcutId(cid)
        val shortcutReady = mAgentShortcuts.updateShortcutIfNeeded(shortcutId, conversation, profile)
        if (shortcutReady) n.shortcutId = shortcutId
        n.locusId = LocusId(shortcutId)
        val builder = if (shortcutReady) BubbleMetadata.Builder(shortcutId)
                      else BubbleMetadata.Builder(n.contentIntent, convertToAdaptiveIcon(this, conversation.icon!!))
        n.bubbleMetadata = builder.setDesiredHeight(DESIRED_BUBBLE_EXPANDED_HEIGHT).build()
    }

    private fun isEnabled(mPrefKeyCallTweak: String) = mPreferences.getBoolean(mPrefKeyCallTweak, false)

    override fun onNotificationRemoved(key: String, reason: Int): Boolean {
        if (reason == REASON_APP_CANCEL) {  // For ongoing notification, or if "Removal-Aware" of Nevolution is activated
            Log.d(TAG, "Cancel notification: $key")
        } else if (reason == REASON_CHANNEL_BANNED && ! isChannelAvailable(getUser(key))) {
            Log.w(TAG, "Channel lost, disable extra channels from now on.")
            mUseExtraChannels = false
            mHandler.post { recastNotification(key, null) }
        } else if (reason == NotificationListenerService.REASON_CANCEL)  // Exclude the removal request by us in above case. (Removal-Aware is only supported on Android 8+)
            mMessagingBuilder.markRead(key)
        return false
    }

    private fun isChannelAvailable(user: UserHandle) =
        getNotificationChannel(WECHAT_PACKAGE, user, CHANNEL_GROUP_CONVERSATION) != null

    override fun onConnected() {
        val channels = ArrayList<NotificationChannel>()
        channels.add(makeChannel(CHANNEL_GROUP_CONVERSATION, R.string.channel_group_message, false))
        // WeChat versions targeting O+ have its own channels for message and misc
        channels.add(migrate(OLD_CHANNEL_MESSAGE, CHANNEL_MESSAGE, R.string.channel_message, false))
        channels.add(migrate(OLD_CHANNEL_MISC, CHANNEL_MISC, R.string.channel_misc, true))
        createNotificationChannels(WECHAT_PACKAGE, Process.myUserHandle(), channels)
    }

    private fun migrate(old_id: String, new_id: String, @StringRes new_name: Int, silent: Boolean): NotificationChannel {
        val channelMessage = getNotificationChannel(WECHAT_PACKAGE, Process.myUserHandle(), old_id)
        deleteNotificationChannel(WECHAT_PACKAGE, Process.myUserHandle(), old_id)
        return channelMessage?.let { cloneChannel(it, new_id, new_name) } ?: makeChannel(new_id, new_name, silent)
    }

    private fun makeChannel(channel_id: String, @StringRes name: Int, silent: Boolean): NotificationChannel {
        val channel = NotificationChannel(channel_id, getString(name), NotificationManager.IMPORTANCE_HIGH /* Allow heads-up (by default) */)
        if (! silent) {
            val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build()
            channel.setSound(getDefaultSound(), attributes)
        } else channel.setSound(null, null)
        channel.enableLights(true)
        channel.lightColor = LIGHT_COLOR
        return channel
    }

    private fun cloneChannel(channel: NotificationChannel, id: String, new_name: Int) =
        NotificationChannel(id, getString(new_name), channel.importance).apply {
            group = channel.group
            description = channel.description
            lockscreenVisibility = channel.lockscreenVisibility
            setSound(Optional.ofNullable(channel.sound).orElse(getDefaultSound()), channel.audioAttributes)
            setBypassDnd(channel.canBypassDnd())
            lightColor = channel.lightColor
            setShowBadge(channel.canShowBadge())
            vibrationPattern = channel.vibrationPattern
        }

    // Before targeting O, WeChat actually plays sound by itself (not via Notification).
    private fun getDefaultSound(): Uri? = Settings.System.DEFAULT_NOTIFICATION_URI

    override fun onCreate() {
        val context = createDeviceProtectedStorageContext()
        mPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_MULTI_PROCESS)
        mPrefKeyWear = getString(R.string.pref_wear)
        mMessagingBuilder = MessagingBuilder(this, object : MessagingBuilder.Controller {
            override fun recastNotification(key: String, addition: Bundle?) = this@WeChatDecorator.recastNotification(key, addition)
            override fun getConversation(user: UserHandle, id: Int) = mConversationManager.getConversation(user, id)
        }) // Must be called after loadPreferences().
        mAgentShortcuts = AgentShortcuts(this)
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED)
        filter.addDataScheme("package")
        registerReceiver(mSettingsChangedReceiver, IntentFilter(ACTION_SETTINGS_CHANGED))
    }

    override fun onDestroy() {
        unregisterReceiver(mSettingsChangedReceiver)
        mAgentShortcuts.close()
        mMessagingBuilder.close()
    }

    private val mSettingsChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras
            val keys = if (extras != null) extras.keySet() else emptySet()
            if (keys.isEmpty()) return
            val editor = mPreferences.edit()
            for (key in keys) editor.putBoolean(key, extras!!.getBoolean(key)).apply()
            editor.apply()
        }
    }
    private val mConversationManager = ConversationManager()
    private lateinit var mMessagingBuilder: MessagingBuilder
    private lateinit var mAgentShortcuts: AgentShortcuts
    private var mUseExtraChannels = true // Extra channels should not be used in Insider mode, as WeChat always removes channels not maintained by itself.
    private lateinit var mPreferences: SharedPreferences
    private lateinit var mPrefKeyWear: String
    private val mHandler = Handler(Looper.myLooper()!!)
    private val mActivityBlocker = buildParcelableWithFileDescriptor()
}

const val TAG = "Nevo.Decorator[WeChat]"
