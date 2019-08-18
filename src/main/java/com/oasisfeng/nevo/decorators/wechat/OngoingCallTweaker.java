package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.List;
import java.util.Objects;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;
import static com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.TAG;

/**
 * Tweaks to the voice call on-going notification.
 *
 * Created by Oasis on 2019-3-17.
 */
class OngoingCallTweaker {

	private static final String EXTRA_CALL_START_TIME = "call.start";

	boolean apply(final NevoDecoratorService service, final String key, final MutableNotification n) {
		if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
		final CharSequence text_cs = n.extras.getCharSequence(Notification.EXTRA_TEXT);
		if (text_cs == null) return false;
		final String text = text_cs.toString();
		final String[] keywords = service.getResources().getStringArray(R.array.text_keywords_for_ongoing_call);
		for (final String keyword : keywords) {
			if (! text.contains(keyword)) continue;
			mOngoingKey = key;
			Log.i(TAG, "Tweak notification of ongoing call: " + key);
			return tweakOngoingCall(n);
		}
		return false;
	}

	private boolean tweakOngoingCall(final MutableNotification n) {
		n.category = Notification.CATEGORY_CALL;
		n.flags |= Notification.FLAG_FOREGROUND_SERVICE;	// For EXTRA_COLORIZED to work. (Foreground service is already used by newer version of WeChat)
		if (SDK_INT >= O) n.extras.putBoolean(Notification.EXTRA_COLORIZED, true);
		if (SDK_INT >= N) getAudioManager().registerAudioRecordingCallback(mAudioRecordingCallback, null);

		final long start_time = n.extras.getLong(EXTRA_CALL_START_TIME, 0);
		n.when = start_time > 0 ? start_time : System.currentTimeMillis();
		n.extras.putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, true);
		return true;
	}

	void onNotificationRemoved(final String key) {
		if (! key.equals(mOngoingKey)) return;
		Log.i(TAG, "Notification of ongoing call is removed: " + key);
		mOngoingKey = null;
		unregisterAudioRecordingCallback();
	}

	void close() {
		unregisterAudioRecordingCallback();
	}

	private void unregisterAudioRecordingCallback() { if (SDK_INT >= N) getAudioManager().unregisterAudioRecordingCallback(mAudioRecordingCallback); }
	private AudioManager getAudioManager() { return Objects.requireNonNull((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE)); }

	interface NotificationUpdater {
		void update(String key, Bundle addition);
	}

	OngoingCallTweaker(final NevoDecoratorService service, final NotificationUpdater updater) {
		mContext = service;
		mUpdater = updater;
	}

	@RequiresApi(N) private final AudioManager.AudioRecordingCallback mAudioRecordingCallback = SDK_INT < N ? null : new AudioManager.AudioRecordingCallback() {

		@Override public void onRecordingConfigChanged(final List<AudioRecordingConfiguration> configs) {
			for (final AudioRecordingConfiguration config : configs) {
				final int audio_source = config.getClientAudioSource();
				Log.d(TAG, "Detected recording audio source: " + audio_source);
				if (audio_source != MediaRecorder.AudioSource.VOICE_COMMUNICATION) continue;
				final Bundle updates = new Bundle(); updates.putLong(EXTRA_CALL_START_TIME, System.currentTimeMillis());
				mUpdater.update(mOngoingKey, updates);
				break;
			}
		}
	};

	private final Context mContext;
	private final NotificationUpdater mUpdater;
	private String mOngoingKey;
}
