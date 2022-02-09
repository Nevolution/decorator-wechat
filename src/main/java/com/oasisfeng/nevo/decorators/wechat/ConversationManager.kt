package com.oasisfeng.nevo.decorators.wechat

import android.app.Notification
import android.os.UserHandle
import android.text.TextUtils
import android.util.ArrayMap
import android.util.SparseArray
import androidx.annotation.IntDef
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

/**
 * Manage all conversations.
 *
 * Created by Oasis on 2019-4-11.
 */
class ConversationManager {

    class Conversation internal constructor(val nid: Int) {  // The notification ID of conversation (hash code of "id" below)

        @IntDef(TYPE_UNKNOWN, TYPE_DIRECT_MESSAGE, TYPE_GROUP_CHAT, TYPE_BOT_MESSAGE)
        @Retention(AnnotationRetention.SOURCE) internal annotation class ConversationType

        @Volatile var id: String? = null // The unique ID of conversation in WeChat
        var count = 0
        var title: CharSequence? = null
        var summary: CharSequence? = null
        var ticker: CharSequence? = null
        var timestamp: Long = 0
        var icon: IconCompat? = null
        private var sender: Person.Builder? = null
        var ext: Notification.CarExtender.UnreadConversation? = null // Of the latest notification

        /** @return previous type */
        fun setType(type: Int): Int {
            if (type == mType) return type
            val previousType = mType
            mType = type
            sender = if (type == TYPE_UNKNOWN || type == TYPE_GROUP_CHAT) null
                     else sender().setKey(id).setBot(type == TYPE_BOT_MESSAGE) // Always set key as it may change
            if (type != TYPE_GROUP_CHAT) mParticipants.clear()
            return previousType
        }

        fun sender(): Person.Builder = sender ?: object : Person.Builder() { override fun build(): Person {
            if (mType == TYPE_GROUP_CHAT) return SENDER_PLACEHOLDER
            val isBot = mType == TYPE_BOT_MESSAGE
            setBot(isBot)
            if (isBot || mType == TYPE_UNKNOWN || mType == TYPE_DIRECT_MESSAGE)
                setIcon(icon).setName(if (title != null) title else " ") // Cannot be empty string, or it will be treated as null.
            return super.build()
        }}

        fun isGroupChat() = mType == TYPE_GROUP_CHAT
        fun isBotMessage() = mType == TYPE_BOT_MESSAGE
        fun isTypeUnknown() = mType == TYPE_UNKNOWN
        fun isChat() = ticker != null && TextUtils.indexOf(ticker, ':') > 0
        fun typeToString() = mType.toString()

        fun getGroupParticipant(key: String, name: String): Person? {
            check(isGroupChat()) { "Not group chat" }
            var builder: Person.Builder? = null
            var participant = mParticipants[key]
            if (participant == null) builder = Person.Builder().setKey(key)
            else if (name != participant.uri!!.substring(SCHEME_ORIGINAL_NAME.length)) // Original name is changed
                builder = participant.toBuilder()
            if (builder != null)
                mParticipants[key] = builder.setUri(SCHEME_ORIGINAL_NAME + name).setName(EmojiTranslator.translate(name))
                    .build().also { participant = it }
            return participant
        }

        @ConversationType private var mType = 0
        private val mParticipants: MutableMap<String, Person> = ArrayMap()

        companion object {
            const val TYPE_UNKNOWN = 0
            const val TYPE_DIRECT_MESSAGE = 1
            const val TYPE_GROUP_CHAT = 2
            const val TYPE_BOT_MESSAGE = 3
            private const val SCHEME_ORIGINAL_NAME = "ON:"

            fun getOriginalName(person: Person) = person.uri?.takeIf { it.startsWith(SCHEME_ORIGINAL_NAME) }
                ?.substring(SCHEME_ORIGINAL_NAME.length) ?: person.name
        }
    }

    fun getOrCreateConversation(profile: UserHandle, id: Int): Conversation {
        val profileId = profile.hashCode()
        val conversations = mConversations[profileId]
            ?: SparseArray<Conversation>().also { mConversations.put(profileId, it) }
        return conversations[id] ?: Conversation(id).also { conversations.put(id, it) }
    }

    fun getConversation(user: UserHandle, id: Int) = mConversations[user.hashCode()]?.get(id)

    private val mConversations = SparseArray<SparseArray<Conversation>>()

    companion object {
        private val SENDER_PLACEHOLDER = Person.Builder().setName(" ").build() // Cannot be empty string, or it will be treated as null.
    }
}