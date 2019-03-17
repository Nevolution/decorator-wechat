package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.content.Context;

import com.oasisfeng.nevo.sdk.MutableNotification;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.O;

/**
 * Tweaks to the voice call on-going notification.
 *
 * Created by Oasis on 2019-3-17.
 */
class VoiceCall {

	static void tweakIfNeeded(final Context context, final MutableNotification n) {
		final CharSequence text_cs = n.extras.getCharSequence(Notification.EXTRA_TEXT);
		if (text_cs == null) return;
		final String text = text_cs.toString();
		final String[] prefixes = context.getResources().getStringArray(R.array.text_prefix_for_voice_call);
		for (final String prefix : prefixes)
			if (text.startsWith(prefix)) {
				tweak(n, text);
				break;
			}
	}

	private static void tweak(final MutableNotification n, final String text) {
		n.category = Notification.CATEGORY_CALL;
		if (SDK_INT >= O) n.extras.putBoolean(Notification.EXTRA_COLORIZED, true);

		final int pos = text.lastIndexOf(' ');
		final String[] parts;
		if (pos > 0 && (parts = text.substring(pos + 1).split(":")).length >= 2) {	// Extract duration from text
			final int duration = Integer.parseInt(parts[parts.length - 1]) + Integer.parseInt(parts[parts.length - 2]) * 60
					+ (parts.length > 2 ? Integer.parseInt(parts[parts.length - 3]) * 3600 : 0);
			n.when = System.currentTimeMillis() - duration * 1000;
			n.extras.putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, true);
			n.extras.putCharSequence(Notification.EXTRA_TEXT, text.substring(0, pos));
		}
	}
}
