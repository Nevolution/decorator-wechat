package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.SparseArray;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Manage all conversations.
 *
 * Created by Oasis on 2019-4-11.
 */
public class ConversationManager {

	private static final Person SENDER_PLACEHOLDER = new Person.Builder().setName(" ").build();	// Cannot be empty string, or it will be treated as null.

	public static class Conversation {

		static final int TYPE_UNKNOWN = 0;
		static final int TYPE_DIRECT_MESSAGE = 1;
		static final int TYPE_GROUP_CHAT = 2;
		static final int TYPE_BOT_MESSAGE = 3;

		@IntDef({ TYPE_UNKNOWN, TYPE_DIRECT_MESSAGE, TYPE_GROUP_CHAT, TYPE_BOT_MESSAGE }) @Retention(RetentionPolicy.SOURCE) @interface ConversationType {}

		private static final String SCHEME_ORIGINAL_NAME = "ON:";

		final int nid;                  // The notification ID of conversation (hash code of "id" below)
		volatile @Nullable String id;   // The unique ID of conversation in WeChat
		int count;
		CharSequence title;
		CharSequence summary;
		CharSequence ticker;
		long timestamp;
		IconCompat icon;
		private @Nullable Person.Builder sender;
		@Nullable Notification.CarExtender.UnreadConversation ext;    // Of the latest notification

		/** @return previous type */
		int setType(final int type) {
			if (type == mType) return type;
			final int previous_type = mType;
			mType = type;
			sender = type == TYPE_UNKNOWN || type == TYPE_GROUP_CHAT ? null : sender().setKey(id).setBot(type == TYPE_BOT_MESSAGE);	// Always set key as it may change
			if (type != TYPE_GROUP_CHAT) mParticipants.clear();
			return previous_type;
		}

		@NonNull Person.Builder sender() {
			return sender != null ? sender : new Person.Builder() { @NonNull @Override public Person build() {
				switch (mType) {
				case TYPE_GROUP_CHAT:
					return SENDER_PLACEHOLDER;
				case TYPE_BOT_MESSAGE:
					setBot(true);	// Fall-through
				case TYPE_UNKNOWN:
				case TYPE_DIRECT_MESSAGE:
					setIcon(icon).setName(title != null ? title : " ");		// Cannot be empty string, or it will be treated as null.
					break;
				}
				return super.build();
			}};
		}

		boolean isGroupChat() { return mType == TYPE_GROUP_CHAT; }
		boolean isBotMessage() { return mType == TYPE_BOT_MESSAGE; }
		boolean isTypeUnknown() { return mType == TYPE_UNKNOWN; }
		String typeToString() { return Integer.toString(mType); }

		boolean isChat() { return ticker != null && TextUtils.indexOf(ticker, ':') > 0; }

		Person getGroupParticipant(final String key, final String name) {
			if (! isGroupChat()) throw new IllegalStateException("Not group chat");
			Person.Builder builder = null;
			Person participant = mParticipants.get(key);
			if (participant == null) builder = new Person.Builder().setKey(key);
			else if (! TextUtils.equals(name, requireNonNull(participant.getUri()).substring(SCHEME_ORIGINAL_NAME.length())))	// Original name is changed
				builder = participant.toBuilder();
			if (builder != null) mParticipants.put(key, participant = builder.setUri(SCHEME_ORIGINAL_NAME + name).setName(EmojiTranslator.translate(name)).build());
			return participant;
		}

		static CharSequence getOriginalName(final Person person) {
			final String uri = person.getUri();
			return uri != null && uri.startsWith(SCHEME_ORIGINAL_NAME) ? uri.substring(SCHEME_ORIGINAL_NAME.length()): person.getName();
		}

		Conversation(final int nid) { this.nid = nid; }

		@ConversationType private int mType;
		private final Map<String, Person> mParticipants = new ArrayMap<>();
	}

	Conversation getOrCreateConversation(final UserHandle profile, final int id) {
		final int profileId = profile.hashCode();
		SparseArray<Conversation> conversations = mConversations.get(profileId);
		if (conversations == null) mConversations.put(profileId, conversations = new SparseArray<>());
		Conversation conversation = conversations.get(id);
		if (conversation == null) conversations.put(id, conversation = new Conversation(id));
		return conversation;
	}

	@Nullable Conversation getConversation(final UserHandle user, final int id) {
		final SparseArray<Conversation> conversations = mConversations.get(user.hashCode());
		return conversations == null ? null : conversations.get(id);
	}

	private final SparseArray<SparseArray/* profile ID */<Conversation>> mConversations = new SparseArray<>();
}
