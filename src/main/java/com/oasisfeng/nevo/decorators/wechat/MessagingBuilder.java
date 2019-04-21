package com.oasisfeng.nevo.decorators.wechat;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.Notification.CarExtender;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Profile;
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
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import static android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY;
import static android.app.Notification.EXTRA_TEXT;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;
import static androidx.core.app.NotificationCompat.EXTRA_CONVERSATION_TITLE;
import static androidx.core.app.NotificationCompat.EXTRA_IS_GROUP_CONVERSATION;
import static androidx.core.app.NotificationCompat.EXTRA_MESSAGES;
import static androidx.core.app.NotificationCompat.EXTRA_SELF_DISPLAY_NAME;
import static com.oasisfeng.nevo.decorators.wechat.WeChatMessage.SENDER_MESSAGE_SEPARATOR;

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

	private static final String KEY_TEXT = "text";
	private static final String KEY_TIMESTAMP = "time";
	private static final String KEY_SENDER = "sender";
	@RequiresApi(P) private static final String KEY_SENDER_PERSON = "sender_person";
	private static final String KEY_DATA_MIME_TYPE = "type";
	private static final String KEY_DATA_URI= "uri";
	private static final String KEY_EXTRAS_BUNDLE = "extras";

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
			messaging.addMessage(buildMessage(conversation, lines.keyAt(i), n.tickerText, lines.valueAt(i), sender_inline ? null : title.toString()));
		return messaging;
	}

	@Nullable MessagingStyle buildFromExtender(final Conversation conversation, final MutableStatusBarNotification sbn) {
		final MutableNotification n = sbn.getNotification();
		final Notification.CarExtender extender = new Notification.CarExtender(n);
		final CarExtender.UnreadConversation convs = extender.getUnreadConversation();
		if (convs == null) return null;

		final PendingIntent on_reply = convs.getReplyPendingIntent();
		if (conversation.key == null) try {
			if (on_reply != null) on_reply.send(mContext, 0, null, (p, intent, r, d, b) -> {
				final String key = conversation.key = intent.getStringExtra(KEY_USERNAME);	// setType() below will trigger rebuilding of conversation sender.
				final int detected_type = key.endsWith("@chatroom") || key.endsWith("@im.chatroom"/* WeWork */)
						? Conversation.TYPE_GROUP_CHAT : key.startsWith("gh_") ? Conversation.TYPE_BOT_MESSAGE : Conversation.TYPE_DIRECT_MESSAGE;
				final int previous_type = conversation.setType(detected_type);
				if (BuildConfig.DEBUG && SDK_INT >= O && previous_type != Conversation.TYPE_UNKNOWN && detected_type != previous_type) {
					final Notification clone = sbn.getNotification().clone();
					final Notification.Builder dn = Notification.Builder.recoverBuilder(mContext, clone).setStyle(null).setSubText(clone.tickerText);
					mContext.getSystemService(NotificationManager.class).notify(sbn.getTag(), sbn.getId(), dn.setChannelId("guide").build());
				}
			}, null);
		} catch (final PendingIntent.CanceledException e) {
			Log.e(TAG, "Error parsing reply intent.", e);
		}

		final MessagingStyle messaging = new MessagingStyle(mUserSelf);
		final String[] car_messages = convs.getMessages();
		if (car_messages.length == 0) {		// When only one message in this conversation
			final Message message = buildMessage(conversation, n.when, n.tickerText, n.extras.getCharSequence(EXTRA_TEXT), null);
			messaging.addMessage(message);
		} else for (int i = 0, num_messages = car_messages.length; i < num_messages; i ++) {
			final String text = car_messages[i];
			final CharSequence n_text = n.extras.getCharSequence(EXTRA_TEXT);
			if (conversation.getType() == Conversation.TYPE_UNKNOWN && num_messages == 1 && TextUtils.equals(text, n_text))
				conversation.setType(Conversation.TYPE_DIRECT_MESSAGE);        // Extra chance to detect direct message indistinguishable from bot message.
			if (i == num_messages - 1 && TextUtils.indexOf(n.tickerText, n_text) >= 0 && TextUtils.indexOf(n.tickerText, text) < 0
					&& TextUtils.indexOf(text, n_text) < 0) {    // The last check for case: text="[Link] ABC", n_text="ABC" (commonly seen in bot messages)
				// The last message inside car extender is inconsistent with the outer ticker and content text, it should be a reply sent by the user.
				messaging.addMessage(buildMessage(conversation, 0, n.tickerText, n_text, null));
				messaging.addMessage(buildMessage(conversation, 0, null, text, ""/* special mark for "self" */));
			} else messaging.addMessage(buildMessage(conversation, 0, i == num_messages - 1 ? n.tickerText : null, text, null));
		}

		final PendingIntent on_read = convs.getReadPendingIntent();
		if (on_read != null) mMarkReadPendingIntents.put(sbn.getKey(), on_read);	// Mapped by evolved key,

		final RemoteInput remote_input;
		if (SDK_INT >= N && on_reply != null && (remote_input = convs.getRemoteInput()) != null) {
			final CharSequence[] input_history = n.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
			final PendingIntent proxy = proxyDirectReply(sbn, on_reply, remote_input, input_history, null);
			final RemoteInput.Builder reply_remote_input = new RemoteInput.Builder(remote_input.getResultKey()).addExtras(remote_input.getExtras())
					.setAllowFreeFormInput(true).setChoices(SmartReply.generateChoices(messaging));
			final String[] participants = convs.getParticipants();
			if (participants != null && participants.length > 0) {
				final StringBuilder label = new StringBuilder();
				for (final String participant : participants) label.append(',').append(participant);
				reply_remote_input.setLabel(label.subSequence(1, label.length()));
			} else reply_remote_input.setLabel(remote_input.getResultKey());

			final Action.Builder reply_action = new Action.Builder(null, mContext.getString(R.string.action_reply), proxy)
					.addRemoteInput(reply_remote_input.build()).setAllowGeneratedReplies(true);
			if (SDK_INT >= P) reply_action.setSemanticAction(Action.SEMANTIC_ACTION_REPLY);
			n.addAction(reply_action.build());

			if (conversation.getType() == Conversation.TYPE_GROUP_CHAT) {
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

	private static Message buildMessage(final Conversation conversation, final long when, final @Nullable CharSequence ticker,
										final CharSequence text, @Nullable String sender) {
		CharSequence actual_text = text;
		if (sender == null) {
			sender = extractSenderFromText(text);
			if (sender != null) {
				actual_text = text.subSequence(sender.length() + SENDER_MESSAGE_SEPARATOR.length(), text.length());
				if (TextUtils.equals(conversation.getTitle(), sender)) sender = null;		// In this case, the actual sender is user itself.
			}
		}
		actual_text = EmojiTranslator.translate(actual_text);

		final Person person;
		if (sender != null && sender.isEmpty()) person = null;		// Empty string as a special mark for "self"
		else if (conversation.getType() == Conversation.TYPE_GROUP_CHAT) {
			final String ticker_sender = ticker != null ? extractSenderFromText(ticker) : null;	// Group nick is used in ticker and content text, while original nick in sender.
			person = sender == null ? null : conversation.getGroupParticipant(sender, ticker_sender != null ? ticker_sender : sender);
		} else person = conversation.sender;
		return new Message(actual_text, when, person);
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

	static void flatIntoExtras(final MessagingStyle messaging, final Bundle extras) {
		final Person user = messaging.getUser();
		if (user != null) {
			extras.putCharSequence(EXTRA_SELF_DISPLAY_NAME, user.getName());
			if (SDK_INT >= P) extras.putParcelable(Notification.EXTRA_MESSAGING_PERSON, toAndroidPerson(user));	// Not included in NotificationCompat
		}
		if (messaging.getConversationTitle() != null) extras.putCharSequence(EXTRA_CONVERSATION_TITLE, messaging.getConversationTitle());
		final List<Message> messages = messaging.getMessages();
		if (! messages.isEmpty()) extras.putParcelableArray(EXTRA_MESSAGES, getBundleArrayForMessages(messages));
		//if (! mHistoricMessages.isEmpty()) extras.putParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES, MessagingBuilder.getBundleArrayForMessages(mHistoricMessages));
		extras.putBoolean(EXTRA_IS_GROUP_CONVERSATION, messaging.isGroupConversation());
	}

	private static Bundle[] getBundleArrayForMessages(final List<Message> messages) {
		final int N = messages.size();
		final Bundle[] bundles = new Bundle[N];
		for (int i = 0; i < N; i ++) bundles[i] = toBundle(messages.get(i));
		return bundles;
	}

	private static Bundle toBundle(final Message message) {
		final Bundle bundle = new Bundle();
		bundle.putCharSequence(KEY_TEXT, message.getText());
		bundle.putLong(KEY_TIMESTAMP, message.getTimestamp());		// Must be included even for 0
		final Person sender = message.getPerson();
		if (sender != null) {
			bundle.putCharSequence(KEY_SENDER, sender.getName());	// Legacy listeners need this
			if (SDK_INT >= P) bundle.putParcelable(KEY_SENDER_PERSON, toAndroidPerson(sender));
		}
		if (message.getDataMimeType() != null) bundle.putString(KEY_DATA_MIME_TYPE, message.getDataMimeType());
		if (message.getDataUri() != null) bundle.putParcelable(KEY_DATA_URI, message.getDataUri());
		if (SDK_INT >= O && ! message.getExtras().isEmpty()) bundle.putBundle(KEY_EXTRAS_BUNDLE, message.getExtras());
		//if (message.isRemoteInputHistory()) bundle.putBoolean(KEY_REMOTE_INPUT_HISTORY, message.isRemoteInputHistory());
		return bundle;
	}

	@RequiresApi(P) @SuppressLint("RestrictedApi") private static android.app.Person toAndroidPerson(final Person user) {
		return user.toAndroidPerson();
	}

	interface Controller { void recastNotification(String key, Bundle addition); }

	MessagingBuilder(final Context context, final Controller controller) {
		mContext = context;
		mController = controller;
		mUserSelf = buildPersonFromProfile(context);

		final IntentFilter filter = new IntentFilter(ACTION_REPLY); filter.addAction(ACTION_MENTION); filter.addDataScheme(SCHEME_KEY);
		context.registerReceiver(mReplyReceiver, filter);
	}

	private static Person buildPersonFromProfile(final Context context) {
		final Person.Builder self = new Person.Builder().setName(context.getString(R.string.self_display_name));
		try (final Cursor cursor = context.getContentResolver().query(Profile.CONTENT_URI,
				new String[] { Contacts._ID, Contacts.LOOKUP_KEY, Contacts.PHOTO_THUMBNAIL_URI }, null, null, null)) {
			if (cursor == null || ! cursor.moveToFirst()) return self.build();
			final long id = cursor.getLong(0); final String lookup_key = cursor.getString(1);
			final String photo = cursor.getString(2);
			final Uri lookup = lookup_key == null ? null : Contacts.getLookupUri(id, lookup_key);
			return self.setUri(lookup != null ? lookup.toString() : null).setIcon(photo == null ? null : IconCompat.createWithContentUri(photo)).build();
		}
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
