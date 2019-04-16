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
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;
import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation.ConversationType;
import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat.MessagingStyle;

import static android.app.Notification.EXTRA_TITLE;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;

/**
 * Bring state-of-art notification experience to WeChat.
 *
 * Created by Oasis on 2015/6/1.
 */
public class WeChatDecorator extends NevoDecoratorService {

	public static final String WECHAT_PACKAGE = "com.tencent.mm";
	private static final int MAX_NUM_ARCHIVED = 20;
	private static final long GROUP_CHAT_SORT_KEY_SHIFT = 24 * 60 * 60 * 1000L;			// Sort group chat like one day older message.
	private static final String CHANNEL_MESSAGE = "message_channel_new_id";				// Channel ID used by WeChat for all message notifications
	private static final String OLD_CHANNEL_MESSAGE = "message";						//   old name for migration
	private static final String CHANNEL_MISC = "reminder_channel_id";					// Channel ID used by WeChat for misc. notifications
	private static final String OLD_CHANNEL_MISC = "misc";								//   old name for migration
	private static final String CHANNEL_DND = "message_dnd_mode_channel_id";			// Channel ID used by WeChat for its own DND mode
	private static final String CHANNEL_GROUP_CONVERSATION = "group";					// WeChat has no separate group for group conversation

	private static final @ColorInt int PRIMARY_COLOR = 0xFF33B332;
	private static final @ColorInt int LIGHT_COLOR = 0xFF00FF00;
	static final String SENDER_MESSAGE_SEPARATOR = ": ";
	private static final String KEY_SILENT_REVIVAL = "nevo.wechat.revival";

	@Override public void apply(final MutableStatusBarNotification evolving) {
		final MutableNotification n = evolving.getNotification();
		final Bundle extras = n.extras;

		CharSequence title = extras.getCharSequence(EXTRA_TITLE);
		if (title == null || title.length() == 0) {
			Log.e(TAG, "Title is missing: " + evolving);
			return;
		}
		if (title != (title = EmojiTranslator.translate(title))) extras.putCharSequence(EXTRA_TITLE, title);
		n.color = PRIMARY_COLOR;        // Tint the small icon

		final String channel_id = SDK_INT >= O ? n.getChannelId() : null;
		if (CHANNEL_MISC.equals(channel_id)) {	// Misc. notifications on Android 8+.
			if (SDK_INT >= O && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
				VoiceCall.tweakIfNeeded(this, n);
			} else Log.d(TAG, "Skip further process for non-conversation notification: " + title);    // E.g. web login confirmation notification.
			return;
		} else if (n.tickerText == null) {		// Legacy misc. notifications.
			if (SDK_INT >= O && channel_id == null) n.setChannelId(CHANNEL_MISC);
			Log.d(TAG, "Skip further process for non-conversation notification: " + title);    // E.g. web login confirmation notification.
			return;
		}

		// WeChat previously uses dynamic counter starting from 4097 as notification ID, which is reused after cancelled by WeChat itself,
		//   causing conversation duplicate or overwritten notifications.
		final String pkg = evolving.getPackageName();
		final Conversation conversation;
		if (! isDistinctId(n, pkg)) {
			final int title_hash = title.hashCode();	// Not using the hash code of original title, which might have already evolved.
			evolving.setId(title_hash);
			conversation = mConversationManager.getConversation(title_hash);
		} else conversation = mConversationManager.getConversation(evolving.getOriginalId());

		conversation.setTitle(title);
		if (conversation.getType() == Conversation.TYPE_UNKNOWN)
			conversation.setType(guessConversationType(n.tickerText, title, extras.getCharSequence(Notification.EXTRA_TEXT)));
		final boolean is_group_chat = conversation.getType() == Conversation.TYPE_GROUP_CHAT;
		if (BuildConfig.DEBUG) extras.putString("nevo.debug", "T" + conversation.getType() + "," + n.tickerText);

		extras.putBoolean(Notification.EXTRA_SHOW_WHEN, true);
		if (BuildConfig.DEBUG) n.flags &= ~ Notification.FLAG_LOCAL_ONLY;
		n.setSortKey(String.valueOf(Long.MAX_VALUE - n.when + (is_group_chat ? GROUP_CHAT_SORT_KEY_SHIFT : 0))); // Place group chat below other messages
		if (SDK_INT >= O) {
			if (extras.containsKey(KEY_SILENT_REVIVAL)) {
				n.setGroup("nevo.group.auto");	// Special group name to let Nevolution auto-group it as if not yet grouped. (To be standardized in SDK)
				n.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);		// This trick makes notification silent
			}
			if (is_group_chat && ! CHANNEL_DND.equals(channel_id)) n.setChannelId(CHANNEL_GROUP_CONVERSATION);
			else if (channel_id == null) n.setChannelId(CHANNEL_MESSAGE);		// WeChat versions targeting O+ have its own channel for message
		}

		MessagingStyle messaging = mMessagingBuilder.buildFromExtender(conversation, evolving);
		if (messaging == null)	// EXTRA_TEXT will be written in buildFromArchive()
			messaging = mMessagingBuilder.buildFromArchive(conversation, n, title, getArchivedNotifications(evolving.getOriginalKey(), MAX_NUM_ARCHIVED));
		if (messaging == null) return;
		final List<MessagingStyle.Message> messages = messaging.getMessages();
		if (messages.isEmpty()) return;

		if (is_group_chat) messaging.setGroupConversation(true).setConversationTitle(title);
		MessagingBuilder.flatIntoExtras(messaging, extras);
		extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_MESSAGING);

		if (SDK_INT >= N && extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY) != null)
			n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;		// No more alert for direct-replied notification.
	}

	private boolean isDistinctId(final Notification n, final String pkg) {
		if (mDistinctIdSupported != null) return mDistinctIdSupported;
		int version = 0;
		final ApplicationInfo app_info = n.extras.getParcelable("android.appInfo");
		if (app_info != null) try {
			if (pkg.equals(app_info.packageName))	// This will be Nevolution for active evolved notifications.
				//noinspection JavaReflectionMemberAccess
				version = (int) ApplicationInfo.class.getField("versionCode").get(app_info);
		} catch (final IllegalAccessException | NoSuchFieldException | ClassCastException ignored) {}    // Fall-through
		if (version == 0) try {
			version = getPackageManager().getPackageInfo(pkg, 0).versionCode;
		} catch (final PackageManager.NameNotFoundException ignored) {}
		return version != 0 && (mDistinctIdSupported = version >= 1340);	// Distinct ID is supported since WeChat 6.7.3.
	}
	private Boolean mDistinctIdSupported;

	// [Direct message with 1 unread]	Ticker: "Oasis: Hello",		Title: "Oasis",	Content: "Hello"
	// [Direct message with >1 unread]	Ticker: "Oasis: Hello",		Title: "Oasis",	Content: "[2]Oasis: Hello"
	// [Service message with 1 unread]	Ticker: "FedEx: Delivered",	Title: "FedEx",	Content: "[Link] Delivered"
	// [Group chat with 1 unread]		Ticker: "Oasis: Hello",		Title: "Group",	Content: "Oasis: Hello"
	// [Group chat with >1 unread]		Ticker: "Oasis: [Link] Mm",	Title: "Group",	Content: "[2]Oasis: [Link] Mm"
	private static @ConversationType int guessConversationType(final CharSequence ticker_raw, final CharSequence title, final CharSequence content) {
		if (content == null) return Conversation.TYPE_UNKNOWN;
		final String ticker = ticker_raw.toString().trim();	// Ticker text (may contain trailing spaces) always starts with sender (same as title for direct message, but not for group chat).
		// Content text includes sender for group and service messages, but not for direct messages.
		final int pos = TextUtils.indexOf(content, ticker.substring(0, Math.min(10, ticker.length())));    // Seek for the first 10 chars of ticker in content.
		if (pos >= 0 && pos <= 6) {        // Max length (up to 999 unread): [999t]
			// The content without unread count prefix, may or may not start with sender nick
			final CharSequence message = pos > 0 && content.charAt(0) == '[' ? content.subSequence(pos, content.length()) : content;
			// message.startsWith(title + SENDER_MESSAGE_SEPARATOR)
			if (startsWith(message, title, SENDER_MESSAGE_SEPARATOR))		// The title of group chat is group name, not the message sender
				return Conversation.TYPE_DIRECT_MESSAGE;	// Most probably a direct message with more than 1 unread
			return Conversation.TYPE_GROUP_CHAT;
		} else if (TextUtils.indexOf(ticker, content) >= 0) {
			return Conversation.TYPE_UNKNOWN;				// Indistinguishable (direct message with 1 unread, or a service text message without link)
		} else return Conversation.TYPE_BOT_MESSAGE;		// Most probably a service message with link
	}

	private static boolean startsWith(final CharSequence text, final CharSequence needle1, @SuppressWarnings("SameParameterValue") final String needle2) {
		final int needle1_length = needle1.length(), needle2_length = needle2.length();
		return text.length() > needle1_length + needle2_length && TextUtils.regionMatches(text, 0, needle1, 0, needle1_length)
				&& TextUtils.regionMatches(text, needle1_length, needle2, 0, needle2_length);
	}

	@Override protected void onNotificationRemoved(final String key, final int reason) {
		if (reason == REASON_APP_CANCEL) {		// Only if "Removal-Aware" of Nevolution is activated
			Log.d(TAG, "Cancel notification: " + key);
			cancelNotification(key);	// Will cancel all notifications evolved from this original key, thus trigger the "else" branch below
		} else if (reason == REASON_CHANNEL_BANNED) {	// In case WeChat deleted our notification channel for group conversation in Insider delivery mode
			mHandler.post(() -> reviveNotificationAfterChannelDeletion(key));
		} else if (SDK_INT < O || reason == REASON_CANCEL) {	// Exclude the removal request by us in above case. (Removal-Aware is only supported on Android 8+)
			mMessagingBuilder.markRead(key);
		}
	}

	private void reviveNotificationAfterChannelDeletion(final String key) {
		Log.d(TAG, ("Revive silently: ") + key);
		final Bundle addition = new Bundle();
		addition.putBoolean(KEY_SILENT_REVIVAL, true);
		recastNotification(key, addition);
	}

	@Override protected void onConnected() {
		if (SDK_INT >= O) {
			mWeChatTargetingO = isWeChatTargeting26OrAbove();
			final List<NotificationChannel> channels = new ArrayList<>();
			channels.add(makeChannel(CHANNEL_GROUP_CONVERSATION, R.string.channel_group_message, false));
			// WeChat versions targeting O+ have its own channels for message and misc
			channels.add(migrate(OLD_CHANNEL_MESSAGE,	CHANNEL_MESSAGE,	R.string.channel_message, false));
			channels.add(migrate(OLD_CHANNEL_MISC,		CHANNEL_MISC,		R.string.channel_misc, true));
			createNotificationChannels(WECHAT_PACKAGE, Process.myUserHandle(), channels);
		}
	}

	@RequiresApi(O) private NotificationChannel migrate(final String old_id, final String new_id, final @StringRes int new_name, final boolean silent) {
		final NotificationChannel channel_message = getNotificationChannel(WECHAT_PACKAGE, Process.myUserHandle(), old_id);
		deleteNotificationChannel(WECHAT_PACKAGE, Process.myUserHandle(), old_id);
		if (channel_message != null) return cloneChannel(channel_message, new_id, new_name);
		else return makeChannel(new_id, new_name, silent);
	}

	@RequiresApi(O) private NotificationChannel makeChannel(final String channel_id, final @StringRes int name, final boolean silent) {
		final NotificationChannel channel = new NotificationChannel(channel_id, getString(name), NotificationManager.IMPORTANCE_HIGH/* Allow heads-up (by default) */);
		if (silent) channel.setSound(null, null);
		else channel.setSound(getDefaultSound(), new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
		channel.enableLights(true);
		channel.setLightColor(LIGHT_COLOR);
		return channel;
	}

	@RequiresApi(O) private NotificationChannel cloneChannel(final NotificationChannel channel, final String id, final int new_name) {
		final NotificationChannel clone = new NotificationChannel(id, getString(new_name), channel.getImportance());
		clone.setGroup(channel.getGroup());
		clone.setDescription(channel.getDescription());
		clone.setLockscreenVisibility(channel.getLockscreenVisibility());
		clone.setSound(Optional.ofNullable(channel.getSound()).orElse(getDefaultSound()), channel.getAudioAttributes());
		clone.setBypassDnd(channel.canBypassDnd());
		clone.setLightColor(channel.getLightColor());
		clone.setShowBadge(channel.canShowBadge());
		clone.setVibrationPattern(channel.getVibrationPattern());
		return clone;
	}

	@Nullable private Uri getDefaultSound() {	// Before targeting O, WeChat actually plays sound by itself (not via Notification).
		return mWeChatTargetingO ? Settings.System.DEFAULT_NOTIFICATION_URI : null;
	}

	private boolean isWeChatTargeting26OrAbove() {
		try {
			return getPackageManager().getApplicationInfo(WECHAT_PACKAGE, PackageManager.GET_UNINSTALLED_PACKAGES).targetSdkVersion >= O;
		} catch (final PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	@Override public void onCreate() {
		super.onCreate();
		mMessagingBuilder = new MessagingBuilder(this, this::recastNotification);
		final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED); filter.addDataScheme("package");
		registerReceiver(mPackageEventReceiver, filter);
	}

	@Override public void onDestroy() {
		unregisterReceiver(mPackageEventReceiver);
		mMessagingBuilder.close();
		super.onDestroy();
	}

	private final BroadcastReceiver mPackageEventReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		if (intent.getData() != null && WECHAT_PACKAGE.equals(intent.getData().getSchemeSpecificPart())) mDistinctIdSupported = null;
	}};

	private final ConversationManager mConversationManager = new ConversationManager();
	private MessagingBuilder mMessagingBuilder;
	private boolean mWeChatTargetingO;
	private final Handler mHandler = new Handler();

	static final String TAG = "Nevo.Decorator[WeChat]";
}
