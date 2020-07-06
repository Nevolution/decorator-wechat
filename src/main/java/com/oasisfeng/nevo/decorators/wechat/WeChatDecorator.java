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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.LocusId;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.annotation.ColorInt;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.annotation.StringRes;
import android.support.v4.app.NotificationCompat.MessagingStyle;
import android.support.v4.graphics.drawable.IconCompat;
import android.util.Log;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;
import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static android.app.Notification.EXTRA_SUB_TEXT;
import static android.app.Notification.EXTRA_TEXT;
import static android.app.Notification.EXTRA_TITLE;
import static android.app.Notification.FLAG_GROUP_SUMMARY;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.N_MR1;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.Q;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;
import static java.util.Objects.requireNonNull;

/**
 * Bring state-of-art notification experience to WeChat.
 *
 * Created by Oasis on 2015/6/1.
 */
public class WeChatDecorator extends NevoDecoratorService {

	static final String WECHAT_PACKAGE = "com.tencent.mm";
	static final String AGENT_PACKAGE = "com.oasisfeng.nevo.agents.wechat";
	static final String CHANNEL_MESSAGE = "message_channel_new_id";				// Channel ID used by WeChat for all message notifications
	private static final int MAX_NUM_ARCHIVED = 20;
	private static final int NID_LOGIN_CONFIRMATION = 38;                       // The static notification ID of WeChat login confirmation
	private static final String OLD_CHANNEL_MESSAGE = "message";				//   old name for migration
	private static final String CHANNEL_MISC = "reminder_channel_id";			// Channel ID used by WeChat for misc. notifications
	private static final String OLD_CHANNEL_MISC = "misc";						//   old name for migration
	private static final String CHANNEL_DND = "message_dnd_mode_channel_id";	// Channel ID used by WeChat for its own DND mode
	private static final String CHANNEL_VOIP = "voip_notify_channel_new_id";	// Channel ID used by WeChat for VoIP notification
	private static final String CHANNEL_GROUP_CONVERSATION = "group";			// WeChat has no separate group for group conversation
	private static final String GROUP_GROUP = "nevo.group.wechat.group";
	private static final String GROUP_BOT = "nevo.group.wechat.bot";
	private static final String GROUP_DIRECT = "nevo.group.wechat";
	private static final String GROUP_MISC = "misc";    // Not auto-grouped
	@SuppressWarnings("SpellCheckingInspection")
	static final String KEY_SERVICE_MESSAGE = "notifymessage";					// Virtual WeChat account for service notification messages

	private static final @ColorInt int PRIMARY_COLOR = 0xFF33B332;
	private static final @ColorInt int LIGHT_COLOR = 0xFF00FF00;
	private static final String ACTION_NOTIFICATION_CLICKED = "N_CLICKED";
	private static final @SuppressLint("InlinedApi") String EXTRA_NOTIFICATION_ID = Notification.EXTRA_NOTIFICATION_ID;
	static final String ACTION_SETTINGS_CHANGED = "SETTINGS_CHANGED";
	static final String PREFERENCES_NAME = "decorators-wechat";

	@Override public boolean apply(final MutableStatusBarNotification evolving) {
		final MutableNotification n = evolving.getNotification();
		if ((n.flags & FLAG_GROUP_SUMMARY) != 0) {
			if (GROUP_GROUP.equals(n.getGroup())) {
				n.extras.putCharSequence(EXTRA_SUB_TEXT, getText(R.string.header_group_chat));
			} else if (GROUP_BOT.equals(n.getGroup())) {
				n.extras.putCharSequence(EXTRA_SUB_TEXT, getText(R.string.header_bot_message));
			} else return false;
			return true;
		}
		final Bundle extras = n.extras;
		final CharSequence title = extras.getCharSequence(EXTRA_TITLE);
		if (title == null || title.length() == 0) {
			Log.e(TAG, "Title is missing: " + evolving);
			return false;
		}
		final int flags = n.flags; final String channel_id = SDK_INT >= O ? n.getChannelId() : null;
		if ((flags & Notification.FLAG_ONGOING_EVENT) != 0 && CHANNEL_VOIP.equals(channel_id)) return false;

		n.color = PRIMARY_COLOR;        // Tint the small icon
		extras.putBoolean(Notification.EXTRA_SHOW_WHEN, true);
		if (isEnabled(mPrefKeyWear)) n.flags &= ~ Notification.FLAG_LOCAL_ONLY;

		if (n.tickerText == null/* Legacy misc. notifications */|| CHANNEL_MISC.equals(channel_id)) {
			if (SDK_INT >= O && channel_id == null) n.setChannelId(CHANNEL_MISC);
			n.setGroup(GROUP_MISC);             // Avoid being auto-grouped
			if (SDK_INT >= O && evolving.getId() == NID_LOGIN_CONFIRMATION)
				n.setTimeoutAfter(5 * 60_000);  // The actual timeout for login confirmation is a little shorter than 5 minutes.
			Log.d(TAG, "Skip further process for non-conversation notification: " + title);    // E.g. web login confirmation notification.
			return (n.flags & Notification.FLAG_FOREGROUND_SERVICE) == 0;
		}
		final CharSequence content_text = extras.getCharSequence(EXTRA_TEXT);
		if (content_text == null) return true;

		if (SDK_INT >= N && extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY) != null)
			n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;		// No more alert for direct-replied notification.

		// WeChat previously uses dynamic counter starting from 4097 as notification ID, which is reused after cancelled by WeChat itself,
		//   causing conversation duplicate or overwritten notifications.
		final String pkg = evolving.getPackageName();
		final Conversation conversation;
		if (! isDistinctId(n, pkg)) {
			final int title_hash = title.hashCode();	// Not using the hash code of original title, which might have already evolved.
			evolving.setId(title_hash);
			conversation = mConversationManager.getOrCreateConversation(title_hash);
		} else conversation = mConversationManager.getOrCreateConversation(evolving.getOriginalId());

		final Icon icon = n.getLargeIcon();
		conversation.icon = icon != null ? IconCompat.createFromIcon(this, icon) : null;
		conversation.title = title;
		conversation.summary = content_text;
		conversation.ticker = n.tickerText;
		conversation.timestamp = n.when;
		conversation.ext = new Notification.CarExtender(n).getUnreadConversation();

		if (conversation.isTypeUnknown())
			conversation.setType(WeChatMessage.guessConversationType(conversation));    // mMessagingBuilder replies on the type

		MessagingStyle messaging = mMessagingBuilder.buildFromConversation(conversation, evolving);
		if (messaging == null)	// EXTRA_TEXT will be written in buildFromArchive()
			messaging = mMessagingBuilder.buildFromArchive(conversation, n, title, getArchivedNotifications(evolving.getOriginalKey(), MAX_NUM_ARCHIVED));
		if (messaging == null) return true;
		final List<MessagingStyle.Message> messages = messaging.getMessages();
		if (messages.isEmpty()) return true;

		final boolean is_group_chat = conversation.isGroupChat();
		if (SDK_INT >= P && KEY_SERVICE_MESSAGE.equals(conversation.key)) {     // Setting conversation title before Android P will make it a group chat.
			messaging.setConversationTitle(getString(R.string.header_service_message)); // A special header for this non-group conversation with multiple senders
			n.setGroup(GROUP_BOT);
		} else n.setGroup(is_group_chat ? GROUP_GROUP : conversation.isBotMessage() ? GROUP_BOT : GROUP_DIRECT);
		if (SDK_INT >= O) {
			if (is_group_chat && mUseExtraChannels && ! CHANNEL_DND.equals(channel_id))
				n.setChannelId(CHANNEL_GROUP_CONVERSATION);
			else if (channel_id == null) n.setChannelId(CHANNEL_MESSAGE);	// WeChat versions targeting O+ have its own channel for message
		}

		if (is_group_chat) messaging.setGroupConversation(true).setConversationTitle(title);
		MessagingBuilder.flatIntoExtras(messaging, extras);
		extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_MESSAGING);

		if (conversation.key != null) {
			final String locusId = "C:" + conversation.key;
			if (SDK_INT >= O) n.setShortcutId(locusId);
			if (SDK_INT >= Q) n.setLocusId(new LocusId(locusId));
		}

		if (SDK_INT >= N_MR1 && n.contentIntent != null)    // No need to wrap content intent if dynamic shortcut is not supported.
			n.contentIntent = getPackageName().equals(n.contentIntent.getCreatorPackage()) ? n.contentIntent    // Possibly already wrapped
					: PendingIntent.getBroadcast(this, conversation.id, new Intent(ACTION_NOTIFICATION_CLICKED)
					.putExtra(Intent.EXTRA_INTENT, n.contentIntent).putExtra(EXTRA_NOTIFICATION_ID, conversation.id)
					.setPackage(getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
		return true;
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

	private boolean isEnabled(final String mPrefKeyCallTweak) {
		return mPreferences.getBoolean(mPrefKeyCallTweak, false);
	}

	@Override protected boolean onNotificationRemoved(final String key, final int reason) {
		if (reason == REASON_APP_CANCEL) {		// For ongoing notification, or if "Removal-Aware" of Nevolution is activated
			Log.d(TAG, "Cancel notification: " + key);
		} else if (SDK_INT >= O && reason == REASON_CHANNEL_BANNED && ! isChannelAvailable(getUser(key))) {
			Log.w(TAG, "Channel lost, disable extra channels from now on.");
			mUseExtraChannels = false;
			mHandler.post(() -> recastNotification(key, null));
		} else if (SDK_INT < O || reason == REASON_CANCEL) {	// Exclude the removal request by us in above case. (Removal-Aware is only supported on Android 8+)
			mMessagingBuilder.markRead(key);
		}
		return false;
	}

	@RequiresApi(O) private boolean isChannelAvailable(final UserHandle user) {
		return getNotificationChannel(WECHAT_PACKAGE, user, CHANNEL_GROUP_CONVERSATION) != null;
	}

	private static UserHandle getUser(final String key) {
		final int pos_pipe = key.indexOf('|');
		if (pos_pipe > 0) try {
			return userHandleOf(Integer.parseInt(key.substring(0, pos_pipe)));
		} catch (final NumberFormatException ignored) {}
		Log.e(TAG, "Invalid key: " + key);
		return Process.myUserHandle();		// Only correct for single user.
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
		//noinspection deprecation
		if (BuildConfig.DEBUG && SDK_INT >= N_MR1 && new Date().getMinutes() % 5 == 0) try {
			requireNonNull(createPackageContext(AGENT_PACKAGE, 0).getSystemService(ShortcutManager.class)).removeAllDynamicShortcuts();
		} catch (final PackageManager.NameNotFoundException ignored) {}

		final Context context = SDK_INT >= N ? createDeviceProtectedStorageContext() : this;
		//noinspection deprecation
		mPreferences = context.getSharedPreferences(PREFERENCES_NAME, MODE_MULTI_PROCESS);
		mPrefKeyWear = getString(R.string.pref_wear);

		mMessagingBuilder = new MessagingBuilder(this, new MessagingBuilder.Controller() {
			@Override public void recastNotification(final String key, final Bundle addition) {
				WeChatDecorator.this.recastNotification(key, addition);
			}
			@Override public Conversation getConversation(final int id) {
				return mConversationManager.getConversation(id);
			}
		});	// Must be called after loadPreferences().
		mAgentShortcuts = SDK_INT >= N_MR1 ? new AgentShortcuts(this) : null;

		registerReceiver(mNotificationClickReceiver, new IntentFilter(ACTION_NOTIFICATION_CLICKED));
		final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED); filter.addDataScheme("package");
		registerReceiver(mPackageEventReceiver, filter);
		registerReceiver(mSettingsChangedReceiver, new IntentFilter(ACTION_SETTINGS_CHANGED));
	}

	@Override public void onDestroy() {
		unregisterReceiver(mSettingsChangedReceiver);
		unregisterReceiver(mPackageEventReceiver);
		unregisterReceiver(mNotificationClickReceiver);
		mMessagingBuilder.close();
	}

	private static UserHandle userHandleOf(final int user) {
		final UserHandle current_user = Process.myUserHandle();
		if (user == current_user.hashCode()) return current_user;
		if (SDK_INT >= N) UserHandle.getUserHandleForUid(user * 100000 + 1);
		final Parcel parcel = Parcel.obtain();
		try {
			parcel.writeInt(user);
			parcel.setDataPosition(0);
			return new UserHandle(parcel);
		} finally {
			parcel.recycle();
		}
	}

	private final BroadcastReceiver mNotificationClickReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		final PendingIntent target = intent.getParcelableExtra(Intent.EXTRA_INTENT);
		final int id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);
		if (target != null) try {
			target.send(0, (pi, revealed, i, s, bundle) -> {
				if (BuildConfig.DEBUG) Log.i(TAG, "Sending: " + revealed.toUri(Intent.URI_INTENT_SCHEME));

				final Conversation conversation = mConversationManager.getConversation(id);
				if (conversation == null) Log.w(TAG, "Unknown conversation ID: " + id);
				else if (SDK_INT >= N_MR1 && conversation.isChat() && ! conversation.isBotMessage()) mHandler.post(() -> {    // Async for potentially heavy job
					try { mAgentShortcuts.publishShortcut(conversation, revealed); }
					catch (final RuntimeException e) { Log.e(TAG, "Error publishing shortcut for " + conversation.key, e); }
				});
			}, mHandler);
		} catch (final PendingIntent.CanceledException e) { Log.e(TAG, "Unexpected already canceled content intent. ID=" + id); }
	}};

	private final BroadcastReceiver mPackageEventReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		if (intent.getData() != null && WECHAT_PACKAGE.equals(intent.getData().getSchemeSpecificPart())) mDistinctIdSupported = null;
	}};

	private final BroadcastReceiver mSettingsChangedReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		final Bundle extras = intent.getExtras();
		final Set<String> keys = extras != null ? extras.keySet() : Collections.emptySet();
		if (keys.isEmpty()) return;
		final SharedPreferences.Editor editor = mPreferences.edit();
		for (final String key : keys) editor.putBoolean(key, extras.getBoolean(key)).apply();
		editor.apply();
	}};

	private final ConversationManager mConversationManager = new ConversationManager();
	private MessagingBuilder mMessagingBuilder;
	private AgentShortcuts mAgentShortcuts;
	private boolean mWeChatTargetingO;
	private boolean mUseExtraChannels = true;	// Extra channels should not be used in Insider mode, as WeChat always removes channels not maintained by itself.
	private SharedPreferences mPreferences;
	private String mPrefKeyWear;
	private final Handler mHandler = new Handler();

	static final String TAG = "Nevo.Decorator[WeChat]";
}
