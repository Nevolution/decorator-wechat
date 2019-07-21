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
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;
import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat.MessagingStyle;

import static android.app.Notification.EXTRA_TEXT;
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
	private static final String CHANNEL_MESSAGE = "message_channel_new_id";				// Channel ID used by WeChat for all message notifications
	private static final String OLD_CHANNEL_MESSAGE = "message";						//   old name for migration
	private static final String CHANNEL_MISC = "reminder_channel_id";					// Channel ID used by WeChat for misc. notifications
	private static final String OLD_CHANNEL_MISC = "misc";								//   old name for migration
	private static final String CHANNEL_DND = "message_dnd_mode_channel_id";			// Channel ID used by WeChat for its own DND mode
	private static final String CHANNEL_GROUP_CONVERSATION = "group";					// WeChat has no separate group for group conversation

	private static final @ColorInt int PRIMARY_COLOR = 0xFF33B332;
	private static final @ColorInt int LIGHT_COLOR = 0xFF00FF00;
	static final String ACTION_SETTINGS_CHANGED = "SETTINGS_CHANGED";
	static final String ACTION_DEBUG_NOTIFICATION = "DEBUG";

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
		final CharSequence content_text = extras.getCharSequence(EXTRA_TEXT);
		if (content_text == null) return;

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
		conversation.summary = content_text;
		conversation.ticker = n.tickerText;
		conversation.timestamp = n.when;
		if (conversation.getType() == Conversation.TYPE_UNKNOWN)
			conversation.setType(WeChatMessage.guessConversationType(conversation));
		final boolean is_group_chat = conversation.isGroupChat();

		extras.putBoolean(Notification.EXTRA_SHOW_WHEN, true);
		if (mPreferences.getBoolean(mPrefKeyWear, false)) n.flags &= ~ Notification.FLAG_LOCAL_ONLY;
		if (SDK_INT >= O) {
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

	@Override protected void onNotificationRemoved(final String key, final int reason) {
		if (reason == REASON_APP_CANCEL) {		// Only if "Removal-Aware" of Nevolution is activated
			Log.d(TAG, "Cancel notification: " + key);
			cancelNotification(key);	// Will cancel all notifications evolved from this original key, thus trigger the "else" branch below
		} else if (reason == REASON_CHANNEL_BANNED) {	// In case WeChat deleted our notification channel for group conversation in Insider delivery mode
			mHandler.post(() -> reviveNotification(key));
		} else if (SDK_INT < O || reason == REASON_CANCEL) {	// Exclude the removal request by us in above case. (Removal-Aware is only supported on Android 8+)
			mMessagingBuilder.markRead(key);
		}
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
		loadPreferences();
		mPrefKeyWear = getString(R.string.pref_wear);

		mMessagingBuilder = new MessagingBuilder(this, mPreferences, this::recastNotification);		// Must be called after loadPreferences().
		final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED); filter.addDataScheme("package");
		registerReceiver(mPackageEventReceiver, filter);
		registerReceiver(mSettingsChangedReceiver, new IntentFilter(ACTION_SETTINGS_CHANGED));
	}

	@Override public void onDestroy() {
		unregisterReceiver(mSettingsChangedReceiver);
		unregisterReceiver(mPackageEventReceiver);
		mMessagingBuilder.close();
		super.onDestroy();
	}

	@Override public int onStartCommand(final Intent intent, final int flags, final int startId) {
		String tag = null; int id = 0;
		if (SDK_INT >= O && BuildConfig.DEBUG && ACTION_DEBUG_NOTIFICATION.equals(intent.getAction())) try {
			tag = intent.getStringExtra(Notification.EXTRA_NOTIFICATION_TAG);
			id = intent.getIntExtra(Notification.EXTRA_NOTIFICATION_ID, 0);
			@SuppressWarnings("deprecation") final String key = new StatusBarNotification(WECHAT_PACKAGE, null, id, tag, getPackageManager()
					.getPackageUid(WECHAT_PACKAGE, 0), 0, 0, new Notification(), Process.myUserHandle(), 0).getKey();
			final StatusBarNotification sbn = getLatestNotifications(Collections.singletonList(key)).get(0);
			final Notification n = sbn.getNotification();
			final Notification.CarExtender.UnreadConversation conversation = new Notification.CarExtender(n).getUnreadConversation();
			if (conversation != null) {
				final String[] lines = Arrays.copyOf(conversation.getMessages(), conversation.getMessages().length + 2);
				final long t = conversation.getLatestTimestamp();
				lines[lines.length - 2] = "TS:" + (t == 0 ? "00" : t - n.when) + ",P:" + conversation.getParticipant();
				lines[lines.length - 1] = n.tickerText != null ? n.tickerText.toString() : null;
				n.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, lines);
				n.extras.putString(Notification.EXTRA_TEMPLATE, Notification.InboxStyle.class.getName());
			} else {
				if (n.tickerText != null) n.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, n.extras.getCharSequence(EXTRA_TEXT) + "\n" + n.tickerText);
				n.extras.putString(Notification.EXTRA_TEMPLATE, Notification.BigTextStyle.class.getName());
			}
			final NotificationManager nm = Objects.requireNonNull(getSystemService(NotificationManager.class));
			nm.createNotificationChannel(new NotificationChannel(n.getChannelId(), "Debug:" + n.getChannelId(), NotificationManager.IMPORTANCE_LOW));
			nm.notify(tag, id, n);
		} catch (final PackageManager.NameNotFoundException | RuntimeException e) {
			Log.e(TAG, "Error debugging notification, id=" + id + ", tag=" + tag, e);
		}
		return START_NOT_STICKY;
	}

	private void loadPreferences() {
		final Context context = SDK_INT >= N ? createDeviceProtectedStorageContext() : this;
		//noinspection deprecation
		mPreferences = context.getSharedPreferences(getDefaultSharedPreferencesName(context), MODE_MULTI_PROCESS);
	}

	private static String getDefaultSharedPreferencesName(final Context context) {
		return SDK_INT >= N ? PreferenceManager.getDefaultSharedPreferencesName(context) : context.getPackageName() + "_preferences";
	}

	private final BroadcastReceiver mPackageEventReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		if (intent.getData() != null && WECHAT_PACKAGE.equals(intent.getData().getSchemeSpecificPart())) mDistinctIdSupported = null;
	}};

	private final BroadcastReceiver mSettingsChangedReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		loadPreferences();
	}};

	private final ConversationManager mConversationManager = new ConversationManager();
	private MessagingBuilder mMessagingBuilder;
	private boolean mWeChatTargetingO;
	private SharedPreferences mPreferences;
	private String mPrefKeyWear;
	private final Handler mHandler = new Handler();

	static final String TAG = "Nevo.Decorator[WeChat]";
}
