package com.oasisfeng.nevo.decorators.wechat;

import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.regex.Pattern;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.core.app.Person;

import static java.util.Objects.requireNonNull;

/**
 * Manage all conversations.
 *
 * Created by Oasis on 2019-4-11.
 */
class ConversationManager {

	private static final Person SENDER_PLACEHOLDER = new Person.Builder().setName(" ").build();	// Cannot be empty string, or it will be treated as null.

	static class Conversation {

		static final int TYPE_UNKNOWN = 0;
		static final int TYPE_DIRECT_MESSAGE = 1;
		static final int TYPE_GROUP_CHAT = 2;
		static final int TYPE_BOT_MESSAGE = 3;
		@IntDef({ TYPE_UNKNOWN, TYPE_DIRECT_MESSAGE, TYPE_GROUP_CHAT, TYPE_BOT_MESSAGE }) @Retention(RetentionPolicy.SOURCE) @interface ConversationType {}

		private static final String SCHEME_ORIGINAL_NAME = "ON:";
		private static final Pattern pattern = Pattern.compile("^[a-zA-Z0-9\\u4e00-\\u9fa5]");

		final int id;
		@Nullable String key;
		int count;
		CharSequence summary;
		CharSequence ticker;
		long timestamp;
		Person sender = SENDER_PLACEHOLDER;

		int getType() { return mType; }

		/** @return previous type */
		int setType(final int type) {
			if (type == mType) return type;
			final int previous_type = mType;
			mType = type;
			sender = type == TYPE_UNKNOWN || type == TYPE_GROUP_CHAT ? SENDER_PLACEHOLDER
					: sender.toBuilder().setKey(key).setBot(type == TYPE_BOT_MESSAGE).build();	// Always set key as it may change
			if (type != TYPE_GROUP_CHAT) mParticipants.clear();
			return previous_type;
		}

		boolean isGroupChat() { return mType == TYPE_GROUP_CHAT; }

		CharSequence getTitle() { return mTitle; }

		void setTitle(final CharSequence title) {
			if (TextUtils.equals(title, mTitle)) return;
			mTitle = title;
			sender = sender.toBuilder().setName(title).build();		// Rename the sender
		}

		Person getGroupParticipant(final String key, final String name) {
			if (! isGroupChat()) throw new IllegalStateException("Not group chat");
			Person.Builder builder = null;
			Person participant = mParticipants.get(key);
			if (participant == null) builder = new Person.Builder().setKey(key);
			else if (! TextUtils.equals(name, requireNonNull(participant.getUri()).substring(SCHEME_ORIGINAL_NAME.length())))	// Original name is changed
				builder = participant.toBuilder();
			if (builder != null) {
				final CharSequence n = EmojiTranslator.translate(name);
				builder.setUri(SCHEME_ORIGINAL_NAME + name);
				if (pattern.matcher(n).find()) {
					builder.setName(n);
				} else {
					builder.setName("\u200b" + n);
				}
				mParticipants.put(key, participant = builder.build());
			}
			return participant;
		}

		static CharSequence getOriginalName(final Person person) {
			final String uri = person.getUri();
			return uri != null && uri.startsWith(SCHEME_ORIGINAL_NAME) ? uri.substring(SCHEME_ORIGINAL_NAME.length()): person.getName();
		}

		Conversation(final int id) { this.id = id; }

		@ConversationType private int mType;
		private CharSequence mTitle;
		private final Map<String, Person> mParticipants = new ArrayMap<>();
	}

	Conversation getConversation(final int id) {
		Conversation conversation = mConversations.get(id);
		if (conversation == null) mConversations.put(id, conversation = new Conversation(id));
		return conversation;
	}

	private final SparseArray<Conversation> mConversations = new SparseArray<>();
	private static final String TAG = WeChatDecorator.TAG;
}
