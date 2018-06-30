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

package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.LongSparseArray;

import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;
import androidx.core.app.Person;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.KITKAT_WATCH;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;

/**
 * Bring state-of-art notification experience to WeChat.
 *
 * Created by Oasis on 2015/6/1.
 */
public class WeChatDecorator extends NevoDecoratorService {

	private static final long GROUP_CHAT_SORT_KEY_SHIFT = 24 * 60 * 60 * 1000L;		// Sort group chat as one day older message.
	private static final int MAX_NUM_LINES = 10;
	private static final int NID_CONVERSATION_START = 4096;
	private static final String EXTRA_CAR_EXTENDER = "android.car.EXTENSIONS";
	private static final String EXTRA_CONVERSATION = "car_conversation";
	/* Following keys are originally defined in Notification.CarExtender.UnreadConversation */
	private static final String KEY_MESSAGES = "messages";
	private static final String KEY_AUTHOR = "author";		// In the bundle of KEY_MESSAGES
	private static final String KEY_TEXT = "text";			// In the bundle of KEY_MESSAGES
	private static final String KEY_REMOTE_INPUT = "remote_input";
	private static final String KEY_ON_REPLY = "on_reply";
	private static final String KEY_ON_READ = "on_read";
	private static final String KEY_PARTICIPANTS = "participants";
	private static final String KEY_TIMESTAMP = "timestamp";

	//private static final String KEY_TIMESTAMP = "timestamp";
	private static final String KEY_CONVERSATION_ID = "key_username";	// The internal conversation ID in WeChat.

	private static final String ACTION_REPLY = "REPLY";
	private static final String SCHEME_KEY = "key";
	private static final String EXTRA_REPLY_ACTION = "pending_intent";
	private static final String EXTRA_RESULT_KEY = "result_key";
	private static final String EXTRA_MARK_READ_ACTION = "mark_read";

	private static final @ColorInt int PRIMARY_COLOR = 0xFF33B332;
	private static final String SENDER_MESSAGE_SEPARATOR = ": ";
	private static final Person SENDER_PLACEHOLDER = new Person.Builder().setName(" ").build();	// Cannot be empty string, or it will be treated as null.

	@Override public void apply(final MutableStatusBarNotification evolving) {
		final MutableNotification n = evolving.getNotification();
		final Bundle extras = n.extras;

		final CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
		if (title == null || title.length() == 0) {
			Log.e(TAG, "Title is missing: " + evolving);
			return;
		}
		final int original_id = evolving.getOriginalId();
		if (BuildConfig.DEBUG) extras.putString("nevo.debug", "ID:" + original_id + ",t:" + n.tickerText);

		n.color = PRIMARY_COLOR;		// Tint the small icon

		// WeChat uses dynamic counter as notification ID, which unfortunately will be reset upon evolving (removal, to be exact) by us,
		// causing all messages combined into one notification. So we split them by re-coding the notification ID by title.
		if (original_id < NID_CONVERSATION_START) {
			if (SDK_INT >= O) n.setChannelId(CHANNEL_MISC);
			Log.d(TAG, "Skip further process for non-conversation notification. ID: " + original_id);	// E.g. web login confirmation notification.
			return;
		}
		evolving.setId(title.hashCode());
		extras.putBoolean(Notification.EXTRA_SHOW_WHEN, true);

		final CharSequence content_text = extras.getCharSequence(Notification.EXTRA_TEXT);
		final boolean group_chat = isGroupChat(n.tickerText, title.toString(), content_text);
		n.setSortKey(String.valueOf(Long.MAX_VALUE - n.when + (group_chat ? GROUP_CHAT_SORT_KEY_SHIFT : 0)));	// Place group chat below other messages
		if (SDK_INT >= O) n.setChannelId(group_chat ? CHANNEL_GROUP_CONVERSATION : CHANNEL_MESSAGE);
		MessagingStyle messaging = buildFromCarExtender(evolving.getKey(), n, title, extras, group_chat);
		if (messaging == null) messaging = buildFromArchive(evolving, n, title, extras, group_chat);
		if (messaging == null) return;
		if (group_chat) messaging.setGroupConversation(true).setConversationTitle(title);

		// Add additional replies filled by us in the proxied procedure of direct-reply.
		if (SDK_INT >= N && extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY) != null)
			n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;		// No more alert for direct-replied notification.

		final Bundle addition = new Bundle();
		messaging.addCompatExtras(addition);
		for (final String key : addition.keySet()) {    // Copy the extras generated by MessagingStyle to notification extras.
			final Object value = addition.get(key);
			if (value == null) continue;
			if (value instanceof CharSequence) extras.putCharSequence(key, (CharSequence) value);
			else if (value instanceof Parcelable[]) extras.putParcelableArray(key, (Parcelable[]) value);
			else if (value instanceof Bundle) extras.putBundle(key, (Bundle) value);
			else Log.e(TAG, "Unsupported extra \"" + key + "\": " + value);
		}
		extras.putCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE, title);
		extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_MESSAGING);
	}

	// [Direct message with 1 unread]	Ticker: "Oasis: Hello",		Title: "Oasis",	Content: "Hello"
	// [Direct message with >1 unread]	Ticker: "Oasis: Hello",		Title: "Oasis",	Content: "[2]Oasis: Hello"
	// [Service message with 1 unread]	Ticker: "FedEx: Delivered",	Title: "FedEx",	Content: "[Link] Delivered"
	// [Group chat with 1 unread]		Ticker: "Oasis: Hello",		Title: "Group",	Content: "Oasis: Hello"
	// [Group chat with >1 unread]		Ticker: "Oasis: [Link] Mm",	Title: "Group",	Content: "[2]Oasis: [Link] Mm"
	private static boolean isGroupChat(final CharSequence ticker_text, final String title, final CharSequence content_text) {
		if (ticker_text == null || content_text == null) return false;
		final String ticker = ticker_text.toString();	// Ticker text always starts with sender (same as title for direct message, but not for group chat).
		final String content = content_text.toString();	// Content text includes sender for group and service messages, but not for direct messages.
		final int pos = content.indexOf(ticker.substring(0, Math.min(10, ticker.length())));	// Seek for the first 10 chars of ticker in content.
		if (pos >= 0 && pos <= 6) {		// Max length (up to 999 unread): [999t]
			final String message = pos > 0 && content.charAt(0) == '[' ? content.substring(pos) : content;	// Content without unread count prefix
			return ! message.startsWith(title + SENDER_MESSAGE_SEPARATOR);	// If positive, most probably a direct message with more than 1 unread
		} else return false;										// Most probably a direct message with 1 unread
	}

	private @Nullable MessagingStyle buildFromCarExtender(final String key, final MutableNotification n, final CharSequence title, final Bundle extras, final boolean group_chat) {
		final Bundle car_extender = extras.getBundle(EXTRA_CAR_EXTENDER);
		if (car_extender == null) return null;
		final Bundle conversation = car_extender.getBundle(EXTRA_CONVERSATION);
		if (conversation == null) {
			Log.w(TAG, EXTRA_CONVERSATION + " is missing");
			return null;
		}
		final long latest_timestamp = conversation.getLong(KEY_TIMESTAMP, 0);
		if (latest_timestamp != 0) n.when = latest_timestamp;

		final Parcelable[] parcelable_messages = conversation.getParcelableArray(KEY_MESSAGES);
		if (parcelable_messages == null) {
			Log.w(TAG, KEY_MESSAGES + " is missing");
			return null;
		}
		final MessagingStyle messaging = new MessagingStyle(mSelf);
		if (parcelable_messages.length == 0) {		// When only one message in this conversation
			messaging.addMessage(buildMessage(title, extras.getCharSequence(Notification.EXTRA_TEXT), null, group_chat));
		} else for (final Parcelable parcelable_message : parcelable_messages) {
			if (! (parcelable_message instanceof Bundle)) return null;
			final Bundle message = (Bundle) parcelable_message;
			final String text = message.getString(KEY_TEXT);
			if (text == null) {
				Log.w(TAG, KEY_TEXT + " is missing");
				return null;
			}
			final CharSequence sender = message.getString(KEY_AUTHOR);	// Apparently always null (not yet implemented by WeChat at present)
			messaging.addMessage(buildMessage(title, text, sender, group_chat));
		}

		final PendingIntent on_read = conversation.getParcelable(KEY_ON_READ);
		if (on_read != null) n.deleteIntent = on_read;						// Swipe to mark read
		final PendingIntent on_reply = conversation.getParcelable(KEY_ON_REPLY);
		if (on_reply != null) {
			final RemoteInput remote_input = conversation.getParcelable(KEY_REMOTE_INPUT);
			if (remote_input != null) {
				final CharSequence[] input_history = SDK_INT >= N ? extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY) : null;
				final PendingIntent proxy = proxyDirectReply(key, on_reply, remote_input, input_history, on_read);
				final RemoteInput.Builder tweaked = new RemoteInput.Builder(remote_input.getResultKey()).addExtras(remote_input.getExtras())
						.setAllowFreeFormInput(true);
				final String[] participants = conversation.getStringArray(KEY_PARTICIPANTS);
				if (participants != null && participants.length > 0) {
					final StringBuilder label = new StringBuilder();
					for (final String participant : participants) label.append(',').append(participant);
					tweaked.setLabel(label.subSequence(1, label.length()));
				} else tweaked.setLabel(remote_input.getResultKey());

				final Action.Builder builder = new Action.Builder(null, getString(R.string.action_reply), proxy).addRemoteInput(tweaked.build());
				if (SDK_INT >= N) builder.setAllowGeneratedReplies(true);		// Enable "Smart Reply"
				n.addAction(builder.build());
			}
		}
		return messaging;
	}

	private static Message buildMessage(final CharSequence title, final CharSequence text, final @Nullable CharSequence sender, final boolean group_chat) {
		final int pos_colon;
		CharSequence display_text = text;
		final CharSequence display_sender;
		if (sender == null && (pos_colon = text.toString().indexOf(SENDER_MESSAGE_SEPARATOR)) > 0) {
			display_sender = text.subSequence(0, pos_colon);
			display_text = text.subSequence(pos_colon + 2, text.length());
		} else display_sender = title;
		final Person person = group_chat ? new Person.Builder().setName(display_sender).build() : SENDER_PLACEHOLDER;
		return new Message(display_text, 0/* TODO: Any effect in Android Auto? */, person);
	}

	/** Intercept the PendingIntent in RemoteInput to update the notification with replied message upon success. */
	private PendingIntent proxyDirectReply(final String key, final PendingIntent on_reply, final RemoteInput remote_input,
										   final @Nullable CharSequence[] input_history, final PendingIntent on_read) {
		final Intent proxy_intent = new Intent(ACTION_REPLY).setData(Uri.fromParts(SCHEME_KEY, key, null)).setPackage(getPackageName())
				.putExtra(EXTRA_REPLY_ACTION, on_reply).putExtra(EXTRA_RESULT_KEY, remote_input.getResultKey()).putExtra(EXTRA_MARK_READ_ACTION, on_read);
		if (SDK_INT >= N && input_history != null)
			proxy_intent.putCharSequenceArrayListExtra(Notification.EXTRA_REMOTE_INPUT_HISTORY, new ArrayList<>(Arrays.asList(input_history)));
		return PendingIntent.getBroadcast(this, 0, proxy_intent, FLAG_UPDATE_CURRENT);
	}

	private final BroadcastReceiver mReplyReceiver = new BroadcastReceiver() { @RequiresApi(KITKAT_WATCH) @Override public void onReceive(final Context context, final Intent proxy_intent) {
		final PendingIntent reply_action = proxy_intent.getParcelableExtra(EXTRA_REPLY_ACTION);
		final String result_key = proxy_intent.getStringExtra(EXTRA_RESULT_KEY);
		final PendingIntent mark_read_action = proxy_intent.getParcelableExtra(EXTRA_MARK_READ_ACTION);
		final Uri data = proxy_intent.getData();
		if (data == null || reply_action == null || result_key == null) return;		// Should never happen
		final String key = data.getSchemeSpecificPart();
		final ArrayList<CharSequence> input_history = SDK_INT >= N ? proxy_intent.getCharSequenceArrayListExtra(Notification.EXTRA_REMOTE_INPUT_HISTORY) : null;
		try {
			final Intent input_data = new Intent().setPackage(reply_action.getCreatorPackage());	// Ensure it works even if WeChat is background-restricted.
			input_data.setClipData(proxy_intent.getClipData());
			reply_action.send(WeChatDecorator.this, 0, input_data, new PendingIntent.OnFinished() { @Override public void onSendFinished(final PendingIntent pendingIntent, final Intent intent, final int resultCode, final String resultData, final Bundle resultExtras) {
				final Bundle input = RemoteInput.getResultsFromIntent(input_data);
				if (input == null) return;
				final CharSequence text = input.getCharSequence(result_key);
				if (BuildConfig.DEBUG) Log.d(TAG, "Reply sent: " + intent.toUri(0));
				if (SDK_INT >= N) {
					final Bundle addition = new Bundle();
					final CharSequence[] inputs = input_history == null ? new CharSequence[] { text }
							: input_history.toArray(new CharSequence[input_history.add(text) ? input_history.size() : 0]);
					addition.putCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY, inputs);
					recastNotification(key, addition);
					if (mark_read_action != null) try {
						mark_read_action.send(WeChatDecorator.this, 0, new Intent().setPackage(mark_read_action.getCreatorPackage()));
					} catch (final PendingIntent.CanceledException e) {
						Log.w(TAG, "Mark-read action is already canceled: " + intent.getStringExtra(KEY_CONVERSATION_ID));
					}
				}
			}}, null);
		} catch (final PendingIntent.CanceledException e) {
			Log.w(TAG, "Reply action is already cancelled: " + key);
			abortBroadcast();
		}
	}};

	private @Nullable MessagingStyle buildFromArchive(final MutableStatusBarNotification evolving, final Notification n, final CharSequence title,
													  final Bundle extras, final boolean group_chat) {
		// Chat history in big content view
		final List<StatusBarNotification> history = getArchivedNotifications(evolving.getOriginalKey(), 20);
		if (history.isEmpty()) {
			Log.d(TAG, "No history");
			return null;
		}

		final LongSparseArray<CharSequence> lines = new LongSparseArray<>(MAX_NUM_LINES);
		CharSequence text = null;
		int count = 0, num_lines_with_colon = 0;
		final String redundant_prefix = title.toString() + SENDER_MESSAGE_SEPARATOR;
		for (final StatusBarNotification each : history) {
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
				if (trimmed_text.toString().startsWith(redundant_prefix))    // Remove redundant prefix
					trimmed_text = trimmed_text.subSequence(redundant_prefix.length(), trimmed_text.length());
				else if (trimmed_text.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) num_lines_with_colon ++;
				lines.put(notification.when, text = trimmed_text);
			} else {
				count = 1;
				lines.put(notification.when, text = its_text);
				if (text.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) num_lines_with_colon ++;
			}
		}
		n.number = count;
		if (lines.size() == 0) {
			Log.w(TAG, "No lines extracted, expected " + count);
			return null;
		}

		extras.putCharSequence(Notification.EXTRA_TEXT, text);    // Latest message text for collapsed layout.

		final MessagingStyle messaging = new MessagingStyle(mSelf);
		final boolean sender_inline = num_lines_with_colon == lines.size();
		for (int i = 0; i < lines.size(); i++) {    // All lines have colon in text
			final long when = lines.keyAt(i);
			final CharSequence line = lines.valueAt(i);
			final int pos_colon;
			if (sender_inline && (pos_colon = line.toString().indexOf(SENDER_MESSAGE_SEPARATOR)) > 0) {
				final Person person = group_chat ? new Person.Builder().setName(line.subSequence(0, pos_colon)).build() : SENDER_PLACEHOLDER;
				messaging.addMessage(line.subSequence(pos_colon + 2, line.length()), when, person);
			} else {
				final Person person = group_chat ? new Person.Builder().setName(title).build() : SENDER_PLACEHOLDER;
				messaging.addMessage(line, when, person);
			}
		}
		return messaging;
	}

	/**
	 * @return the extracted count in 0xFF range and start position in 0xFF00 range
	 */
	private static int trimAndExtractLeadingCounter(final CharSequence text) {
		// Parse and remove the leading "[n]" or [n条/則/…]
		if (text == null || text.length() < 4 || text.charAt(0) != '[') return - 1;
		int text_start = 2, count_end;
		while (text.charAt(text_start++) != ']') if (text_start >= text.length()) return - 1;

		try {
			final String num = text.subSequence(1, text_start - 1).toString();    // may contain the suffix "条/則"
			for (count_end = 0; count_end < num.length(); count_end++) if (! Character.isDigit(num.charAt(count_end))) break;
			if (count_end == 0) return - 1;        // Not the expected "unread count"
			final int count = Integer.parseInt(num.substring(0, count_end));
			if (count < 2) return - 1;

			return count < 0xFFFF ? (count & 0xFFFF) | ((text_start << 16) & 0xFFFF0000) : 0xFFFF | ((text_start << 16) & 0xFF00);
		} catch (final NumberFormatException ignored) {
			Log.d(TAG, "Failed to parse: " + text);
			return - 1;
		}
	}

	@Override protected void onConnected() {
		if (SDK_INT >= O) createNotificationChannels("com.tencent.mm", Arrays.asList(
				makeChannel(CHANNEL_MESSAGE, R.string.channel_message),
				makeChannel(CHANNEL_GROUP_CONVERSATION,	R.string.channel_group_message),
				makeChannel(CHANNEL_MISC, R.string.channel_misc)));
	}

	@RequiresApi(O) private NotificationChannel makeChannel(final String channel_id, final @StringRes int name) {
		final NotificationChannel channel = new NotificationChannel(channel_id, getString(name), NotificationManager.IMPORTANCE_HIGH/* Allow heads-up (by default) */);
		channel.setSound(Uri.EMPTY,	// Default to none, due to sound being actually played by WeChat app itself (not via Notification).
				new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_COMMUNICATION_INSTANT).setContentType(CONTENT_TYPE_SONIFICATION).build());
		channel.enableLights(true);
		channel.setLightColor(PRIMARY_COLOR);
		return channel;
	}

	@Override public void onCreate() {
		super.onCreate();
		final Person.Builder self = new Person.Builder().setName(getString(R.string.self_display_name));
		// TODO: The following line is not working
		//if (SDK_INT > O_MR1) self.setUri(ContactsContract.Profile.CONTENT_URI.toString());
		mSelf = self.build();

		final IntentFilter filter = new IntentFilter(ACTION_REPLY);
		filter.addDataScheme(SCHEME_KEY);
		registerReceiver(mReplyReceiver, filter);
	}

	@Override public void onDestroy() {
		try { unregisterReceiver(mReplyReceiver); } catch (final RuntimeException ignored) {}
		super.onDestroy();
	}

	private Person mSelf;
	private static final String CHANNEL_MESSAGE = "message";
	private static final String CHANNEL_GROUP_CONVERSATION = "group";
	private static final String CHANNEL_MISC = "misc";

	private static final String TAG = "Nevo.Decorator[WeChat]";
}
