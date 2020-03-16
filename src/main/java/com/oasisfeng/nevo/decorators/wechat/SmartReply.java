package com.oasisfeng.nevo.decorators.wechat;

import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat.MessagingStyle.Message;
import android.text.TextUtils;

/**
 * A no-smart implementation for Smart Reply
 *
 * Created by Oasis on 2018-8-10.
 */
class SmartReply {

	private static final CharSequence[][] REPLIES_FOR_QUESTION = {{ "👌", "好", "对", "没问题" }, { "👌", "OK", "Ye" }};

	static @Nullable CharSequence[] generateChoices(final Message[] messages) {
		if (messages.length == 0) return null;
		final CharSequence text = messages[messages.length - 1].getText();
		final boolean chinese;
		if ((chinese = TextUtils.indexOf(text, '？') >= 0) || TextUtils.indexOf(text, '?') >= 0)
			return REPLIES_FOR_QUESTION[chinese ? 0 : 1];
		return null;
	}
}
