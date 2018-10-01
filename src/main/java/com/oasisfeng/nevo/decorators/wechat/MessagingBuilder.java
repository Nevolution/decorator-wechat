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
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongSparseArray;

import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.Person;

import static android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.P;
import static com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.SENDER_MESSAGE_SEPARATOR;

/**
 * Build the modernized {@link MessagingStyle} for WeChat conversation.
 *
 * Refactored by Oasis on 2018-8-9.
 */
public class MessagingBuilder {

	private static final int MAX_NUM_HISTORICAL_LINES = 10;
	private static final Person SENDER_PLACEHOLDER = new Person.Builder().setName(" ").build();	// Cannot be empty string, or it will be treated as null.

	private static final String ACTION_REPLY = "REPLY";
	private static final String SCHEME_KEY = "key";
	private static final String EXTRA_REPLY_ACTION = "pending_intent";
	private static final String EXTRA_RESULT_KEY = "result_key";
	private static final String EXTRA_ORIGINAL_KEY = "original_key";

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

	@Nullable MessagingStyle buildFromArchive(final Notification n, final CharSequence title, final boolean group_chat, final List<StatusBarNotification> archive) {
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
			final CharSequence its_text = its_extras.getCharSequence(Notification.EXTRA_TEXT);
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

		n.extras.putCharSequence(Notification.EXTRA_TEXT, text);	// Latest message text for collapsed layout.

		final MessagingStyle messaging = new MessagingStyle(mSelf);
		final boolean sender_inline = num_lines_with_colon == lines.size();
		for (int i = 0, size = lines.size(); i < size; i++)			// All lines have colon in text
			messaging.addMessage(buildMessage(lines.keyAt(i), title, lines.valueAt(i), sender_inline ? null : title, group_chat));
		return messaging;
	}

	@Nullable MessagingStyle buildFromExtender(final MutableStatusBarNotification sbn, final CharSequence title, final boolean group_chat) {
		final MutableNotification n = sbn.getNotification();
		final Bundle car_extender = n.extras.getBundle(EXTRA_CAR_EXTENDER);
		if (car_extender == null) return null;
		final Bundle conversation = car_extender.getBundle(EXTRA_CONVERSATION);
		if (conversation == null) {
			Log.w(TAG, EXTRA_CONVERSATION + " is missing");
			return null;
		}
		final Parcelable[] parcelable_messages = conversation.getParcelableArray(KEY_MESSAGES);
		if (parcelable_messages == null) {
			Log.w(TAG, KEY_MESSAGES + " is missing");
			return null;
		}
		final MessagingStyle messaging = new MessagingStyle(mSelf);
		if (parcelable_messages.length == 0) {		// When only one message in this conversation
			messaging.addMessage(buildMessage(n.when, title, n.extras.getCharSequence(Notification.EXTRA_TEXT), null, group_chat));
		} else for (final Parcelable parcelable_message : parcelable_messages) {
			if (! (parcelable_message instanceof Bundle)) return null;
			final Bundle message = (Bundle) parcelable_message;
			final String text = message.getString(KEY_TEXT);
			if (text == null) continue;
			final long timestamp = message.getLong(KEY_TIMESTAMP);
			final CharSequence author = message.getString(KEY_AUTHOR);		// Apparently always null (not yet implemented by WeChat)
			messaging.addMessage(buildMessage(timestamp, title, text, author, group_chat));
		}

		final PendingIntent on_read = conversation.getParcelable(KEY_ON_READ);
		if (on_read != null) mMarkReadPendingIntents.put(sbn.getKey(), on_read);	// Mapped by evolved key,

		final PendingIntent on_reply; final RemoteInput remote_input;
		if (SDK_INT >= N && (on_reply = conversation.getParcelable(KEY_ON_REPLY)) != null && (remote_input = conversation.getParcelable(KEY_REMOTE_INPUT)) != null) {
			final CharSequence[] input_history = n.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
			final PendingIntent proxy = proxyDirectReply(sbn, on_reply, remote_input, input_history);
			final RemoteInput.Builder tweaked = new RemoteInput.Builder(remote_input.getResultKey()).addExtras(remote_input.getExtras())
					.setAllowFreeFormInput(true).setChoices(SmartReply.generateChoices(messaging));
			final String[] participants = conversation.getStringArray(KEY_PARTICIPANTS);
			if (participants != null && participants.length > 0) {
				final StringBuilder label = new StringBuilder();
				for (final String participant : participants) label.append(',').append(participant);
				tweaked.setLabel(label.subSequence(1, label.length()));
			} else tweaked.setLabel(remote_input.getResultKey());

			final Action.Builder action = new Action.Builder(null, mContext.getString(R.string.action_reply), proxy)
					.addRemoteInput(tweaked.build()).setAllowGeneratedReplies(true);
			if (SDK_INT >= P) action.setSemanticAction(Action.SEMANTIC_ACTION_REPLY);
			n.addAction(action.build());
		}
		return messaging;
	}

	private static MessagingStyle.Message buildMessage(final long when, final CharSequence title, final CharSequence text,
													   @Nullable CharSequence sender, final boolean group_chat) {
		CharSequence display_text = text;
		if (sender == null) {
			final int pos_colon = display_text.toString().indexOf(SENDER_MESSAGE_SEPARATOR);
			if (pos_colon > 0) {
				sender = EmojiTranslator.translate(display_text.subSequence(0, pos_colon));
				display_text = display_text.subSequence(pos_colon + 2, display_text.length());
				if (TextUtils.equals(title, sender)) sender = null;		// In this case, the actual sender is user itself.
			} else sender = title;
		}

		final Person person = group_chat ? (sender != null ? new Person.Builder().setName(sender).build() : null) : SENDER_PLACEHOLDER;
		return new MessagingStyle.Message(EmojiTranslator.translate(display_text), when, person);
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
										   final @Nullable CharSequence[] input_history) {
		final Intent proxy = new Intent(ACTION_REPLY).putExtra(EXTRA_REPLY_ACTION, on_reply).putExtra(EXTRA_RESULT_KEY, remote_input.getResultKey())
				.setData(Uri.fromParts(SCHEME_KEY, sbn.getKey(), null)).putExtra(EXTRA_ORIGINAL_KEY, sbn.getOriginalKey());
		if (SDK_INT >= N && input_history != null)
			proxy.putCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY, new ArrayList<>(Arrays.asList(input_history)));
		return PendingIntent.getBroadcast(mContext, 0, proxy.setPackage(mContext.getPackageName()), FLAG_UPDATE_CURRENT);
	}

	private final BroadcastReceiver mReplyReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent proxy_intent) {
		final PendingIntent reply_action = proxy_intent.getParcelableExtra(EXTRA_REPLY_ACTION);
		final String result_key = proxy_intent.getStringExtra(EXTRA_RESULT_KEY);
		final Uri data = proxy_intent.getData();
		if (data == null || reply_action == null || result_key == null) return;        // Should never happen
		final String key = data.getSchemeSpecificPart(), original_key = proxy_intent.getStringExtra(EXTRA_ORIGINAL_KEY);
		final ArrayList<CharSequence> input_history = SDK_INT >= N ? proxy_intent.getCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY) : null;
		try {
			final Intent input_data = addTargetPackageAndWakeUp(reply_action);
			input_data.setClipData(proxy_intent.getClipData());
			reply_action.send(mContext, 0, input_data, (pendingIntent, intent, _result_code, _result_data, _result_extras) -> {
				final Bundle input = RemoteInput.getResultsFromIntent(input_data);
				if (input == null) return;
				final CharSequence text = input.getCharSequence(result_key);
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
		final Person.Builder self = new Person.Builder().setName(context.getString(R.string.self_display_name));
		// TODO: The following line is not working
		//if (SDK_INT > O_MR1) self.setUri(ContactsContract.Profile.CONTENT_URI.toString());
		mSelf = self.build();

		final IntentFilter filter = new IntentFilter(ACTION_REPLY);
		filter.addDataScheme(SCHEME_KEY);
		context.registerReceiver(mReplyReceiver, filter);
	}

	void close() {
		try { mContext.unregisterReceiver(mReplyReceiver); } catch (final RuntimeException ignored) {}
	}

	private final Context mContext;
	private final Controller mController;
	private final Person mSelf;
	private final Map<String/* evolved key */, PendingIntent> mMarkReadPendingIntents = new ArrayMap<>();
	private static final String TAG = WeChatDecorator.TAG;
}
