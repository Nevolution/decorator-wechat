package com.oasisfeng.nevo.decorators.wechat;

import android.text.TextUtils;

import java.util.List;

import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;

/**
 * A no-smart implementation for Smart Reply
 *
 * Created by Oasis on 2018-8-10.
 */
public class SmartReply {

	private static final CharSequence[][] REPLIES_FOR_QUESTION = {{ "👌", "好", "对", "没问题" }, { "👌", "OK", "Ye" }};

	static CharSequence[] generateChoices(final MessagingStyle messaging) {
		final List<Message> messages = messaging.getMessages();
		if (messages.isEmpty()) return null;
		final CharSequence text = messages.get(messages.size() - 1).getText();
		final boolean chinese;
		if ((chinese = TextUtils.indexOf(text, '？') >= 0) || TextUtils.indexOf(text, '?') >= 0)
			return REPLIES_FOR_QUESTION[chinese ? 0 : 1];
		return null;
	}
}
