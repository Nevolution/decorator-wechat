package com.oasisfeng.nevo.decorators.wechat;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Translate Emoji markers to Emoji characters.
 *
 * Created by Oasis on 2018-8-9.
 */
public class EmojiTranslator {

	private static final Map<String, String> CHINESE_MAP = new HashMap<>(EmojiMap.MAP.length);
	private static final Map<String, String> ENGLISH_MAP = new HashMap<>(EmojiMap.MAP.length);
	static {
		for (final String[] entry : EmojiMap.MAP) {
			if (entry[0] != null)
				CHINESE_MAP.put(entry[0], entry[2]);
			if (entry[1] != null)
				ENGLISH_MAP.put(entry[1], entry[2]);
		}
	}

	static CharSequence translate(final CharSequence text) {
		int bracket_end = TextUtils.indexOf(text, ']');
		if (bracket_end == -1) return text;
		int bracket_start = TextUtils.lastIndexOf(text, '[', bracket_end - 2);	// At least 1 char between brackets
		if (bracket_start == -1) return text;

		SpannableStringBuilder builder = null;
		int offset = 0;
		while (bracket_start >= 0 && bracket_end >= 0) {
			final String marker = text.subSequence(bracket_start + 1, bracket_end).toString();
			final char first_char = marker.charAt(0);
			final String emoji = (first_char >= 'A' && first_char <= 'Z' ? ENGLISH_MAP : CHINESE_MAP).get(marker);
			if (emoji != null) {
				if (builder == null) builder = new SpannableStringBuilder(text);
				builder.replace(bracket_start + offset, bracket_end + 1 + offset, emoji);
				offset += emoji.length() - marker.length() - 2;
			}
			bracket_end = TextUtils.indexOf(text, ']', bracket_end + 3);		// "]...[X..."
			bracket_start = TextUtils.lastIndexOf(text, '[', bracket_end - 2);
		}
		return builder != null ? builder : text;
	}
}
