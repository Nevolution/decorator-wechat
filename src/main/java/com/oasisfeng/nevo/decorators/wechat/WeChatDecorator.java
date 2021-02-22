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
import android.app.Notification.BubbleMetadata;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.LocusId;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.os.SharedMemory;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;
import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY;
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
import static com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation.TYPE_BOT_MESSAGE;
import static com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation.TYPE_DIRECT_MESSAGE;
import static com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation.TYPE_GROUP_CHAT;
import static com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation.TYPE_UNKNOWN;

/**
 * Bring state-of-art notification experience to WeChat.
 *
 * Created by Oasis on 2015/6/1.
 */
public class WeChatDecorator extends NevoDecoratorService {

	/** Not fully working yet. Bubble will be shown, but WeChat activity LauncherUI cannot be launched into the floating window. */
	static final boolean BUBBLE_ON_Q = false;
	private static final boolean IGNORE_CAR_EXTENDER = false;    // For test purpose

	static final String WECHAT_PACKAGE = "com.tencent.mm";
	static final String AGENT_PACKAGE = "com.oasisfeng.nevo.agents.v1.wechat";
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
	private static final String KEY_SERVICE_MESSAGE = "notifymessage";			// Virtual WeChat account for service notification messages
	private static final String EXTRA_USERNAME = "Main_User";                   // Extra in content intent

	private static final @ColorInt int PRIMARY_COLOR = 0xFF33B332;
	private static final @ColorInt int LIGHT_COLOR = 0xFF00FF00;
	static final String ACTION_SETTINGS_CHANGED = "SETTINGS_CHANGED";
	static final String PREFERENCES_NAME = "decorators-wechat";
	private static final String EXTRA_SILENT_RECAST = "silent_recast";
	private static final Bundle RECAST_SILENT = new Bundle(); static { RECAST_SILENT.putBoolean(EXTRA_SILENT_RECAST, true); }

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

		final CharSequence[] input_history = SDK_INT >= N ? extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY) : null;
		if (input_history != null || extras.getBoolean(EXTRA_SILENT_RECAST))
			n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;

		// WeChat previously uses dynamic counter starting from 4097 as notification ID, which is reused after cancelled by WeChat itself,
		//   causing conversation duplicate or overwritten notifications.
		final String pkg = evolving.getPackageName(); final UserHandle profile = evolving.getUser();
		final Conversation conversation;
		if (! isDistinctId(n, pkg)) {
			final int title_hash = title.hashCode();	// Not using the hash code of original title, which might have already evolved.
			evolving.setId(title_hash);
			conversation = mConversationManager.getOrCreateConversation(profile, title_hash);
		} else conversation = mConversationManager.getOrCreateConversation(profile, evolving.getOriginalId());

		final Icon large_icon = n.getLargeIcon();
		conversation.icon = IconCompat.createFromIcon(this, large_icon != null ? large_icon : n.getSmallIcon());
		conversation.title = title;
		conversation.summary = content_text;
		conversation.ticker = n.tickerText;
		conversation.timestamp = n.when;
		conversation.ext = IGNORE_CAR_EXTENDER ? null : new Notification.CarExtender(n).getUnreadConversation();

		final String original_key = evolving.getOriginalKey();
		MessagingStyle messaging = mMessagingBuilder.buildFromConversation(conversation, evolving);
		if (messaging == null)	// EXTRA_TEXT will be written in buildFromArchive()
			messaging = mMessagingBuilder.buildFromArchive(conversation, n, title, getArchivedNotifications(original_key, MAX_NUM_ARCHIVED));
		if (messaging == null) return true;
		final List<MessagingStyle.Message> messages = messaging.getMessages();
		if (messages.isEmpty()) return true;

		if (conversation.id == null && mActivityBlocker != null) try {
			final CountDownLatch latch = new CountDownLatch(1);
			n.contentIntent.send(this, 0, new Intent().putExtra("", mActivityBlocker), (pi, intent, r, d, e) -> {
				final String id = intent.getStringExtra(EXTRA_USERNAME);
				if (id == null) { Log.e(TAG, "Unexpected null ID received for conversation: " + conversation.title); return; }
				conversation.id = id;    // setType() below will trigger rebuilding of conversation sender.
				latch.countDown();
				if (BuildConfig.DEBUG && id.hashCode() != conversation.nid) Log.e(TAG, "NID is not hash code of CID");
			}, null);
			try {
				if (latch.await(100, TimeUnit.MILLISECONDS)) {
					if (BuildConfig.DEBUG) Log.d(TAG, "Conversation ID retrieved: " + conversation.id);
				} else Log.w(TAG, "Timeout retrieving conversation ID");
			} catch (final InterruptedException ignored) {}
		} catch (final PendingIntent.CanceledException ignored) {}

		final String cid = conversation.id;
		if (cid != null) {
			final int type = cid.endsWith("@chatroom") || cid.endsWith("@im.chatroom"/* WeWork */) ? TYPE_GROUP_CHAT
					: cid.startsWith("gh_") || cid.equals(KEY_SERVICE_MESSAGE) ? TYPE_BOT_MESSAGE
					: cid.endsWith("@openim") ? TYPE_DIRECT_MESSAGE : TYPE_UNKNOWN;
			conversation.setType(type);
		} else if (conversation.isTypeUnknown())
			conversation.setType(WeChatMessage.guessConversationType(conversation));

		if (SDK_INT >= Build.VERSION_CODES.R && input_history != null) {    // EXTRA_REMOTE_INPUT_HISTORY is not longer supported on Android R.
			for (int i = input_history.length - 1; i >= 0; i--)             // Append them to messages in MessagingStyle.
				messages.add(new MessagingStyle.Message(input_history[i], 0L, (Person) null));
			extras.remove(EXTRA_REMOTE_INPUT_HISTORY);
		}

		final boolean is_group_chat = conversation.isGroupChat();
		if (SDK_INT >= P && KEY_SERVICE_MESSAGE.equals(cid)) {  // Setting conversation title before Android P will make it a group chat.
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

		if (SDK_INT >= N_MR1 && cid != null) {
			final String shortcut_id = AgentShortcuts.Companion.buildShortcutId(cid);
			final boolean shortcut_ready = mAgentShortcuts.updateShortcutIfNeeded(shortcut_id, conversation, profile);
			if (SDK_INT >= O && shortcut_ready) n.setShortcutId(shortcut_id);
			if (SDK_INT >= Q) {
				n.setLocusId(new LocusId(shortcut_id));
				if (SDK_INT == Q && BUBBLE_ON_Q) {
					setBubbleMetadata(n, conversation, shortcut_ready ? shortcut_id : null);    // Bubble could also work without shortcut on Android 10.
					if (! hasValidRemoteInput(n.actions)) { // RemoteInput is required to show bubble on Android 10.
						final Action.Builder action = new Action.Builder(null, "Reply", null)
								.addRemoteInput(new RemoteInput.Builder("").setAllowFreeFormInput(false).build());
						if (n.actions == null) n.actions = new Action[]{ action.build() };
					}
				} else if (SDK_INT > Q && shortcut_ready)   // Shortcut does not use conversation ID if it is absent.
					setBubbleMetadata(n, conversation, conversation.id != null ? shortcut_id : null);
			}
		}
		return true;
	}

	@RequiresApi(Q) @SuppressWarnings("deprecation")
	private void setBubbleMetadata(final MutableNotification n, final Conversation conversation, final @Nullable String shortcut_id) {
		final BubbleMetadata.Builder builder = SDK_INT > Q && shortcut_id != null ? new BubbleMetadata.Builder(shortcut_id) // WeChat does not met the requirement of bubble on Android Q: "documentLaunchMode=always"
				: new BubbleMetadata.Builder().setIcon(IconHelper.convertToAdaptiveIcon(this, conversation.icon))
				.setIntent(SDK_INT > Q ? n.contentIntent : buildBubblePendingIntent(n.contentIntent, shortcut_id));
		Resources resources = getApplicationContext().getResources();
		DisplayMetrics displayMetrics = resources.getDisplayMetrics();
		float dpHeight = displayMetrics.heightPixels / displayMetrics.density;
		
		n.setBubbleMetadata(builder.setDesiredHeight((int) dpHeight).build());
	}

	private PendingIntent buildBubblePendingIntent(final PendingIntent target, final String locusId) {
		return PendingIntent.getActivity(this, 0, new Intent()
				.setData(Uri.fromParts("locus", locusId, null))
				.setClassName(AGENT_PACKAGE, "com.oasisfeng.nevo.agents.AgentBubbleActivity")
				.putExtra("target", target), PendingIntent.FLAG_UPDATE_CURRENT) ;
	}

	private static boolean hasValidRemoteInput(final @Nullable Action[] actions) {
		if (actions == null) return false;
		for (final Action action : actions) {
			final RemoteInput[] inputs = action.getRemoteInputs();
			if (inputs != null && inputs.length > 0) return true;
		}
		return false;
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
		final Context context = SDK_INT >= N ? createDeviceProtectedStorageContext() : this;
		//noinspection deprecation
		mPreferences = context.getSharedPreferences(PREFERENCES_NAME, MODE_MULTI_PROCESS);
		mPrefKeyWear = getString(R.string.pref_wear);

		mMessagingBuilder = new MessagingBuilder(this, new MessagingBuilder.Controller() {
			@Override public void recastNotification(final String key, final Bundle addition) {
				WeChatDecorator.this.recastNotification(key, addition);
			}
			@Override public Conversation getConversation(final UserHandle user, final int id) {
				return mConversationManager.getConversation(user, id);
			}
		});	// Must be called after loadPreferences().
		mAgentShortcuts = SDK_INT >= N_MR1 ? new AgentShortcuts(this) : null;

		final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED); filter.addDataScheme("package");
		registerReceiver(mPackageEventReceiver, filter);
		registerReceiver(mSettingsChangedReceiver, new IntentFilter(ACTION_SETTINGS_CHANGED));
	}

	@Override public void onDestroy() {
		unregisterReceiver(mSettingsChangedReceiver);
		unregisterReceiver(mPackageEventReceiver);
		if (SDK_INT >= N_MR1) mAgentShortcuts.close();
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

	private static @Nullable Parcelable buildParcelableWithFileDescriptor() {
		try {
			if (SDK_INT >= Q) return SharedMemory.create(null, 1);
			return ParcelFileDescriptor.createPipe()[0];
		} catch (final Exception e) { Log.e(TAG, "Partially incompatible ROM: " + e.getMessage()); }
		return null;
	}

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
	private final Handler mHandler = new Handler(Looper.myLooper());
	private final @Nullable Parcelable mActivityBlocker = buildParcelableWithFileDescriptor();

	static final String TAG = "Nevo.Decorator[WeChat]";
}
