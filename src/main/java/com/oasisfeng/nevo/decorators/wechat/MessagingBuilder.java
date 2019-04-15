package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongSparseArray;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;
import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;
import androidx.core.app.Person;

import static android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY;
import static android.app.Notification.EXTRA_TEXT;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.P;
import static com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation.TYPE_GROUP_CHAT;
import static com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.SENDER_MESSAGE_SEPARATOR;

/**
 * Build the modernized {@link MessagingStyle} for WeChat conversation.
 *
 * Refactored by Oasis on 2018-8-9.
 */
class MessagingBuilder {

	private static final int MAX_NUM_HISTORICAL_LINES = 10;

	private static final String ACTION_REPLY = "REPLY";
	private static final String ACTION_MENTION = "MENTION";
	private static final String SCHEME_KEY = "key";
	private static final String EXTRA_REPLY_ACTION = "pending_intent";
	private static final String EXTRA_RESULT_KEY = "result_key";
	private static final String EXTRA_ORIGINAL_KEY = "original_key";
	private static final String EXTRA_REPLY_PREFIX = "reply_prefix";

	/* From Notification.CarExtender */
	private static final String EXTRA_CAR_EXTENDER = "android.car.EXTENSIONS";
	private static final String EXTRA_CONVERSATION = "car_conversation";
	/* From Notification.CarExtender.UnreadConversation */
	private static final String KEY_MESSAGES = "messages";
	private static final String KEY_AUTHOR = "author";				// In the bundle of KEY_MESSAGES
	private static final String KEY_TEXT = "text";					// In the bundle of KEY_MESSAGES
	private static final String KEY_REMOTE_INPUT = "remote_input";
	private static final String KEY_ON_REPLY = "on_reply";
	private static final String KEY_ON_READ = "on_read";
	private static final String KEY_PARTICIPANTS = "participants";
	private static final String KEY_TIMESTAMP = "timestamp";
	private static final String KEY_USERNAME = "key_username";
	private static final String MENTION_SEPARATOR = " ";			// Separator between @nick and text. It's not a regular white space, but U+2005.

	@Nullable MessagingStyle buildFromArchive(final Conversation conversation, final Notification n, final CharSequence title, final List<StatusBarNotification> archive) {
		// Chat history in big content view
		if (archive.isEmpty()) {
			Log.d(TAG, "No history");
			return null;
		}

		final LongSparseArray<CharSequence> lines = new LongSparseArray<>(MAX_NUM_HISTORICAL_LINES);
		CharSequence text = null;
		int count = 0, num_lines_with_colon = 0;
		final String redundant_prefix = title.toString() + SENDER_MESSAGE_SEPARATOR;
		for (final StatusBarNotification each : archive) {
			final Notification notification = each.getNotification();
			final Bundle its_extras = notification.extras;
			final CharSequence its_title = its_extras.getCharSequence(Notification.EXTRA_TITLE);
			if (! title.equals(its_title)) {
				Log.d(TAG, "Skip other conversation with the same key in archive: " + its_title);	// ID reset by WeChat due to notification removal in previous evolving
				continue;
			}
			final CharSequence its_text = its_extras.getCharSequence(EXTRA_TEXT);
			if (its_text == null) {
				Log.w(TAG, "No text in archived notification.");
				continue;
			}
			final int result = trimAndExtractLeadingCounter(its_text);
			if (result >= 0) {
				count = result & 0xFFFF;
				CharSequence trimmed_text = its_text.subSequence(result >> 16, its_text.length());
				if (trimmed_text.toString().startsWith(redundant_prefix))	// Remove redundant prefix
					trimmed_text = trimmed_text.subSequence(redundant_prefix.length(), trimmed_text.length());
				else if (trimmed_text.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) num_lines_with_colon++;
				lines.put(notification.when, text = trimmed_text);
			} else {
				count = 1;
				lines.put(notification.when, text = its_text);
				if (text.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) num_lines_with_colon++;
			}
		}
		n.number = count;
		if (lines.size() == 0) {
			Log.w(TAG, "No lines extracted, expected " + count);
			return null;
		}

		n.extras.putCharSequence(EXTRA_TEXT, text);	// Latest message text for collapsed layout.

		final MessagingStyle messaging = new MessagingStyle(mUserSelf);
		final boolean sender_inline = num_lines_with_colon == lines.size();
		for (int i = 0, size = lines.size(); i < size; i++)			// All lines have colon in text
			messaging.addMessage(buildMessage(conversation, lines.keyAt(i), n.tickerText, lines.valueAt(i), sender_inline ? null : title.toString(), null));
		return messaging;
	}

	@Nullable MessagingStyle buildFromExtender(final Conversation conversation, final MutableStatusBarNotification sbn) {
		final MutableNotification n = sbn.getNotification();
		final Bundle car_extender = n.extras.getBundle(EXTRA_CAR_EXTENDER);
		if (car_extender == null) return null;
		final Bundle convs = car_extender.getBundle(EXTRA_CONVERSATION);
		if (convs == null) {
			Log.w(TAG, EXTRA_CONVERSATION + " is missing");
			return null;
		}
		final Parcelable[] parcelable_messages = convs.getParcelableArray(KEY_MESSAGES);
		if (parcelable_messages == null) {
			Log.w(TAG, KEY_MESSAGES + " is missing");
			return null;
		}
		final PendingIntent on_reply = convs.getParcelable(KEY_ON_REPLY);
		final MessagingStyle messaging = new MessagingStyle(mUserSelf);
		if (parcelable_messages.length == 0) {		// When only one message in this conversation
			final Message message = buildMessage(conversation, n.when, n.tickerText, n.extras.getCharSequence(EXTRA_TEXT), null, on_reply);
			messaging.addMessage(message);
		} else for (int i = 0, num_messages = parcelable_messages.length; i < num_messages; i ++) {
			final Parcelable parcelable = parcelable_messages[i];
			if (! (parcelable instanceof Bundle)) return null;
			final Bundle car_message = (Bundle) parcelable;
			final String text = car_message.getString(KEY_TEXT);
			if (text == null) continue;
			final long timestamp = car_message.getLong(KEY_TIMESTAMP);
			final @Nullable String author = car_message.getString(KEY_AUTHOR);    // Apparently always null (not yet implemented by WeChat)
			final Message message = buildMessage(conversation, timestamp, i == num_messages - 1 ? n.tickerText : null, text, author, on_reply);
			messaging.addMessage(message);
		}

		final PendingIntent on_read = convs.getParcelable(KEY_ON_READ);
		if (on_read != null) mMarkReadPendingIntents.put(sbn.getKey(), on_read);	// Mapped by evolved key,

		final RemoteInput remote_input;
		if (SDK_INT >= N && on_reply != null && (remote_input = convs.getParcelable(KEY_REMOTE_INPUT)) != null) {
			final CharSequence[] input_history = n.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
			final PendingIntent proxy = proxyDirectReply(sbn, on_reply, remote_input, input_history, null);
			final RemoteInput.Builder reply_remote_input = new RemoteInput.Builder(remote_input.getResultKey()).addExtras(remote_input.getExtras())
					.setAllowFreeFormInput(true).setChoices(SmartReply.generateChoices(messaging));
			final String[] participants = convs.getStringArray(KEY_PARTICIPANTS);
			if (participants != null && participants.length > 0) {
				final StringBuilder label = new StringBuilder();
				for (final String participant : participants) label.append(',').append(participant);
				reply_remote_input.setLabel(label.subSequence(1, label.length()));
			} else reply_remote_input.setLabel(remote_input.getResultKey());

			final Action.Builder reply_action = new Action.Builder(null, mContext.getString(R.string.action_reply), proxy)
					.addRemoteInput(reply_remote_input.build()).setAllowGeneratedReplies(true);
			if (SDK_INT >= P) reply_action.setSemanticAction(Action.SEMANTIC_ACTION_REPLY);
			n.addAction(reply_action.build());

			if (conversation.getType() == TYPE_GROUP_CHAT) {
				final List<Message> messages = messaging.getMessages();
				final Person last_sender = messages.get(messages.size() - 1).getPerson();
				if (last_sender != null && last_sender != mUserSelf) {
					final String label = "@" + last_sender.getName(), prefix = "@" + Conversation.getOriginalName(last_sender) + MENTION_SEPARATOR;
					n.addAction(new Action.Builder(null, label, proxyDirectReply(sbn, on_reply, remote_input, input_history, prefix))
							.addRemoteInput(reply_remote_input.setLabel(label).build()).setAllowGeneratedReplies(true).build());
				}
			}
		}
		return messaging;
	}

	private Message buildMessage(final Conversation conversation, final long when, final @Nullable CharSequence ticker,
			final CharSequence text, @Nullable String sender, final @Nullable PendingIntent on_reply) {
		CharSequence actual_text = text;
		if (sender == null) {
			sender = extractSenderFromText(text);
			if (sender != null) {
				actual_text = text.subSequence(sender.length() + SENDER_MESSAGE_SEPARATOR.length(), text.length());
				if (TextUtils.equals(conversation.getTitle(), sender)) sender = null;		// In this case, the actual sender is user itself.
			}
		}
		actual_text = EmojiTranslator.translate(actual_text);

		if (conversation.key == null) try {
			if (on_reply != null) on_reply.send(mContext, 0, null, (p, intent, r, d, b) -> {
				final String key = conversation.key = intent.getStringExtra(KEY_USERNAME);	// setType() below will trigger rebuilding of conversation sender.
				conversation.setType(key.endsWith("@chatroom") || key.endsWith("@im.chatroom"/* WeWork */) ? TYPE_GROUP_CHAT
						: key.startsWith("gh_") ? Conversation.TYPE_BOT_MESSAGE : Conversation.TYPE_DIRECT_MESSAGE);
			}, null);
		} catch (final PendingIntent.CanceledException e) {
			Log.e(TAG, "Error parsing reply intent.", e);
		}

		if (conversation.getType() == TYPE_GROUP_CHAT) {
			final String ticker_sender = ticker != null ? extractSenderFromText(ticker) : null;	// Group nick is used in ticker while original nick in sender.
			final Person person = sender == null ? null : conversation.getGroupParticipant(sender, ticker_sender != null ? ticker_sender : sender);
			return new Message(actual_text, when, person);
		} else return new Message(actual_text, when, conversation.sender);
	}

	private static @Nullable String extractSenderFromText(final CharSequence text) {
		final int pos_colon = TextUtils.indexOf(text, SENDER_MESSAGE_SEPARATOR);
		return pos_colon > 0 ? text.toString().substring(0, pos_colon) : null;
	}

	/** @return the extracted count in 0xFF range and start position in 0xFF00 range */
	private static int trimAndExtractLeadingCounter(final CharSequence text) {
		// Parse and remove the leading "[n]" or [n条/則/…]
		if (text == null || text.length() < 4 || text.charAt(0) != '[') return - 1;
		int text_start = 2, count_end;
		while (text.charAt(text_start++) != ']') if (text_start >= text.length()) return - 1;

		try {
			final String num = text.subSequence(1, text_start - 1).toString();	// may contain the suffix "条/則"
			for (count_end = 0; count_end < num.length(); count_end++) if (! Character.isDigit(num.charAt(count_end))) break;
			if (count_end == 0) return - 1;			// Not the expected "unread count"
			final int count = Integer.parseInt(num.substring(0, count_end));
			if (count < 2) return - 1;

			return count < 0xFFFF ? (count & 0xFFFF) | ((text_start << 16) & 0xFFFF0000) : 0xFFFF | ((text_start << 16) & 0xFF00);
		} catch (final NumberFormatException ignored) {
			Log.d(TAG, "Failed to parse: " + text);
			return - 1;
		}
	}

	/** Intercept the PendingIntent in RemoteInput to update the notification with replied message upon success. */
	private PendingIntent proxyDirectReply(final MutableStatusBarNotification sbn, final PendingIntent on_reply, final RemoteInput remote_input,
										   final @Nullable CharSequence[] input_history, final @Nullable String mention_prefix) {
		final Intent proxy = new Intent(mention_prefix != null ? ACTION_MENTION : ACTION_REPLY)		// Separate action to avoid PendingIntent overwrite.
				.putExtra(EXTRA_REPLY_ACTION, on_reply).putExtra(EXTRA_RESULT_KEY, remote_input.getResultKey())
				.setData(Uri.fromParts(SCHEME_KEY, sbn.getKey(), null)).putExtra(EXTRA_ORIGINAL_KEY, sbn.getOriginalKey());
		if (mention_prefix != null) proxy.putExtra(EXTRA_REPLY_PREFIX, mention_prefix);
		if (SDK_INT >= N && input_history != null)
			proxy.putCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY, new ArrayList<>(Arrays.asList(input_history)));
		return PendingIntent.getBroadcast(mContext, 0, proxy.setPackage(mContext.getPackageName()), FLAG_UPDATE_CURRENT);
	}

	private final BroadcastReceiver mReplyReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent proxy_intent) {
		final PendingIntent reply_action = proxy_intent.getParcelableExtra(EXTRA_REPLY_ACTION);
		final String result_key = proxy_intent.getStringExtra(EXTRA_RESULT_KEY), reply_prefix = proxy_intent.getStringExtra(EXTRA_REPLY_PREFIX);
		final Uri data = proxy_intent.getData(); final Bundle results = RemoteInput.getResultsFromIntent(proxy_intent);
		final CharSequence input = results != null ? results.getCharSequence(result_key) : null;
		if (data == null || reply_action == null || result_key == null || input == null) return;	// Should never happen
		final CharSequence text;
		if (reply_prefix != null) {
			text = reply_prefix + input;
			results.putCharSequence(result_key, text);
			RemoteInput.addResultsToIntent(new RemoteInput[]{ new RemoteInput.Builder(result_key).build() }, proxy_intent, results);
		} else text = input;
		final ArrayList<CharSequence> input_history = SDK_INT >= N ? proxy_intent.getCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY) : null;
		final String key = data.getSchemeSpecificPart(), original_key = proxy_intent.getStringExtra(EXTRA_ORIGINAL_KEY);
		try {
			final Intent input_data = addTargetPackageAndWakeUp(reply_action);
			input_data.setClipData(proxy_intent.getClipData());

			reply_action.send(mContext, 0, input_data, (pendingIntent, intent, _result_code, _result_data, _result_extras) -> {
				if (BuildConfig.DEBUG) Log.d(TAG, "Reply sent: " + intent.toUri(0));
				if (SDK_INT >= N) {
					final Bundle addition = new Bundle();
					final CharSequence[] inputs;
					if (input_history != null) {
						input_history.add(0, text);
						inputs = input_history.toArray(new CharSequence[0]);
					} else inputs = new CharSequence[] { text };
					addition.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, inputs);
					mController.recastNotification(original_key != null ? original_key : key, addition);
					markRead(key);
				}
			}, null);
		} catch (final PendingIntent.CanceledException e) {
			Log.w(TAG, "Reply action is already cancelled: " + key);
			abortBroadcast();
		}
	}};

	/** @param key the evolved key */
	void markRead(final String key) {
		final PendingIntent action = mMarkReadPendingIntents.remove(key);
		if (action == null) return;
		try {
			action.send(mContext, 0, addTargetPackageAndWakeUp(action));
		} catch (final PendingIntent.CanceledException e) {
			Log.w(TAG, "Mark-read action is already cancelled: " + key);
		}
	}

	/** Ensure the PendingIntent works even if WeChat is stopped or background-restricted. */
	@NonNull private static Intent addTargetPackageAndWakeUp(final PendingIntent action) {
		return new Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setPackage(action.getCreatorPackage());
	}

	interface Controller { void recastNotification(String key, Bundle addition); }

	MessagingBuilder(final Context context, final Controller controller) {
		mContext = context;
		mController = controller;
		final Uri profile_lookup = ContactsContract.Contacts.getLookupUri(context.getContentResolver(), ContactsContract.Profile.CONTENT_URI);
		mUserSelf = new Person.Builder().setUri(profile_lookup != null ? profile_lookup.toString() : null).setName(context.getString(R.string.self_display_name)).build();

		final IntentFilter filter = new IntentFilter(ACTION_REPLY); filter.addAction(ACTION_MENTION); filter.addDataScheme(SCHEME_KEY);
		context.registerReceiver(mReplyReceiver, filter);
	}

	void close() {
		try { mContext.unregisterReceiver(mReplyReceiver); } catch (final RuntimeException ignored) {}
	}

	private final Context mContext;
	private final Controller mController;
	private final Person mUserSelf;
	private final Map<String/* evolved key */, PendingIntent> mMarkReadPendingIntents = new ArrayMap<>();
	private static final String TAG = WeChatDecorator.TAG;
}
