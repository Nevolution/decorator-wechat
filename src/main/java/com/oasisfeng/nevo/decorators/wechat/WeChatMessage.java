package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.app.Notification.CarExtender;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;
import androidx.core.app.Person;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;

import static com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.TAG;

/**
 * Parse various fields collected from notification to build a structural message.
 *
 * Known cases
 * -------------
 *  Direct message   1 unread  Ticker: "Oasis: Hello",         Title: "Oasis",  Summary: "Hello"                    CarExt: "Hello"
 *  Direct message  >1 unread  Ticker: "Oasis: [Link] WTF",    Title: "Oasis",  Summary: "[2]Oasis: [Link] WTF"
 *  Service message  1 unread  Ticker: "FedEx: [Link] Status", Title: "FedEx",  Summary: "[Link] Status"			CarExt: "[Link] Status"
 *  Service message >1 unread  Ticker: "FedEx: Delivered",     Title: "FedEx",  Summary: "[2]FedEx: Delivered"		CarExt: "[Link] Delivered"
 *  Group chat with  1 unread  Ticker: "GroupNick: Hello",     Title: "Group",  Summary: "GroupNick: Hello"			CarExt: "GroupNick: Hello"
 *  Group chat with >1 unread  Ticker: "GroupNick: [Link] Mm", Title: "Group",  Summary: "[2]GroupNick: [Link] Mm"	CarExt: "GroupNick: [Link] Mm"
 *
 * Created by Oasis on 2019-4-19.
 */
class WeChatMessage {

	static final String SENDER_MESSAGE_SEPARATOR = ": ";
	private static final String SELF = "";

	static Message[] buildMessages(final Conversation conversation) {
		final Notification.CarExtender.UnreadConversation ext = conversation.ext; final String[] ext_messages;
		if (ext == null || (ext_messages = ext.getMessages()) == null || ext_messages.length == 0)       // Sometimes extender messages are empty, for unknown cause.
			return new Message[] { buildFromBasicFields(conversation).toMessage() };	// No messages in car conversation

		final WeChatMessage basic_msg = buildFromBasicFields(conversation);
		final Message[] messages = new Message[ext_messages.length];
		int end_of_peers = -1;
		if (! conversation.isGroupChat()) for (end_of_peers = ext_messages.length - 1; end_of_peers >= -1; end_of_peers --)
			if (end_of_peers >= 0 && TextUtils.equals(basic_msg.text, ext_messages[end_of_peers])) break;	// Find the actual end line which matches basic fields, in case extra lines are sent by self
		for (int i = 0, count = ext_messages.length; i < count; i ++)
			messages[i] = buildFromCarMessage(conversation, ext_messages[i], end_of_peers >= 0 && i > end_of_peers).toMessage();
		return messages;
	}

	private static WeChatMessage buildFromBasicFields(final Conversation conversation) {
		// Trim the possible trailing white spaces in ticker.
		CharSequence ticker = conversation.ticker;
		int ticker_length = ticker.length();
		int ticker_end = ticker_length;
		while (ticker_end > 0 && ticker.charAt(ticker_end - 1) == ' ') ticker_end--;
		if (ticker_end != ticker_length) {
			ticker = ticker.subSequence(0, ticker_end);
			ticker_length = ticker_end;
		}

		CharSequence sender = null, text;
		int pos = TextUtils.indexOf(ticker, SENDER_MESSAGE_SEPARATOR), unread_count = 0;
		if (pos > 0) {
			sender = ticker.subSequence(0, pos);
			text = ticker.subSequence(pos + SENDER_MESSAGE_SEPARATOR.length(), ticker_length);
		} else text = ticker;

		final CharSequence summary = conversation.summary;
		final int content_length = summary.length();
		CharSequence content_wo_prefix = summary;
		if (content_length > 3 && summary.charAt(0) == '[' && (pos = TextUtils.indexOf(summary, ']', 1)) > 0) {
			unread_count = parsePrefixAsUnreadCount(summary.subSequence(1, pos));
			if (unread_count > 0) {
				conversation.count = unread_count;
				content_wo_prefix = summary.subSequence(pos + 1, content_length);
			} else if (TextUtils.equals(summary.subSequence(pos + 1, content_length), text))
				conversation.setType(Conversation.TYPE_BOT_MESSAGE);	// Only bot message omits prefix (e.g. "[Link]")
		}

		if (sender == null) {	// No sender in ticker, blindly trust the sender in summary text.
			pos = TextUtils.indexOf(content_wo_prefix, SENDER_MESSAGE_SEPARATOR);
			if (pos > 0) {
				sender = content_wo_prefix.subSequence(0, pos);
				text = content_wo_prefix.subSequence(pos + 1, content_wo_prefix.length());
			} else text = content_wo_prefix;
		} else if (! startsWith(content_wo_prefix, sender, SENDER_MESSAGE_SEPARATOR)) {    // Ensure sender matches (in ticker and summary)
			if (unread_count > 0)	// When unread count prefix is present, sender should also be included in summary.
				Log.e(TAG, "Sender mismatch: \"" + sender + "\" in ticker, summary: " + summary.subSequence(0, Math.min(10, content_length)));
			if (startsWith(ticker, sender, SENDER_MESSAGE_SEPARATOR))	// Normal case for single unread message
				return new WeChatMessage(conversation, sender, content_wo_prefix, conversation.timestamp);
		}
		return new WeChatMessage(conversation, sender, text, conversation.timestamp);
	}

	/**
	 * Parse unread count prefix in the form of "n" or "n条/則/…".
	 * @return unread count, or 0 if unrecognized as unread count
	 */
	private static int parsePrefixAsUnreadCount(final CharSequence prefix) {
		final int length = prefix.length();
		if (length < 1) return 0;
		final CharSequence count = length > 1 && ! Character.isDigit(prefix.charAt(length - 1)) ? prefix.subSequence(0, length - 1) : prefix;
		try {
			return Integer.parseInt(count.toString());
		} catch (final NumberFormatException ignored) {
			Log.d(TAG, "Failed to parse as int: " + prefix);
			return 0;
		}
	}

	static int guessConversationType(final Conversation conversation) {
		final CarExtender.UnreadConversation ext = conversation.ext;
		final String[] messages = ext != null ? ext.getMessages() : null;
		final int num_messages = messages != null ? messages.length : 0;
		final String last_message = num_messages > 0 ? messages[num_messages - 1] : null;
		if (num_messages > 1) {  // Car extender messages with multiple senders are strong evidence for group chat.
			String sender = null;
			for (final String message : messages) {
				final String[] splits = message.split(":", 2);
				if (splits.length < 2) continue;
				if (sender == null) sender = splits[0];
				else if (! sender.equals(splits[0])) return Conversation.TYPE_GROUP_CHAT;   // More than one sender
			}
		}
		final CharSequence content = conversation.summary;
		if (content == null) return Conversation.TYPE_UNKNOWN;
		final String ticker = conversation.ticker.toString().trim();	// Ticker text (may contain trailing spaces) always starts with sender (same as title for direct message, but not for group chat).
		// Content text includes sender for group and service messages, but not for direct messages.
		final int pos = TextUtils.indexOf(content, ticker);             // Seek for the ticker text in content.
		if (pos >= 0 && pos <= 6) {        // Max length (up to 999 unread): [999t]
			// The content without unread count prefix, may or may not start with sender nick
			final CharSequence content_wo_count = pos > 0 && content.charAt(0) == '[' ? content.subSequence(pos, content.length()) : content;
			// content_wo_count.startsWith(title + SENDER_MESSAGE_SEPARATOR)
			if (startsWith(content_wo_count, conversation.title, SENDER_MESSAGE_SEPARATOR)) {   // The title of group chat is group name, not the message sender
				final CharSequence text = content_wo_count.subSequence(
						conversation.title.length() + SENDER_MESSAGE_SEPARATOR.length(), content_wo_count.length());
				if (startWithBracketedPrefixAndOneSpace(last_message, text))      // Ticker: "Bot name: Text", Content: "[2] Bot name: Text", Message: "[Link] Text"
					return Conversation.TYPE_BOT_MESSAGE;
				else if (isBracketedPrefixOnly(last_message)) return Conversation.TYPE_BOT_MESSAGE;
				return Conversation.TYPE_DIRECT_MESSAGE;    // Most probably a direct message with more than 1 unread
			}
			return Conversation.TYPE_GROUP_CHAT;
		} else if (TextUtils.indexOf(ticker, content) >= 0) {
			if (startWithBracketedPrefixAndOneSpace(last_message, content))      // Ticker: "Bot name: Text", Content: "Text", Message: "[Link] Text"
				return Conversation.TYPE_BOT_MESSAGE;
			return Conversation.TYPE_UNKNOWN;				// Indistinguishable (direct message with 1 unread, or a service text message without link)
		} else return Conversation.TYPE_BOT_MESSAGE;		// Most probably a service message with link
	}

	private static boolean startWithBracketedPrefixAndOneSpace(final @Nullable String text, final CharSequence needle) {
		if (text == null) return false;
		final int start = text.indexOf(needle.toString());
		return start > 3 && text.charAt(0) == '[' && text.charAt(start - 1) == ' ' && text.charAt(start - 2) == ']';
	}

	private static boolean isBracketedPrefixOnly(final @Nullable String text) {
		if (text == null) return false;
		final int length = text.length();
		return length >= 3 && length <= 4 && text.charAt(0) == '[' && text.charAt(length - 1) == ']';
	}

	private static boolean startsWith(final CharSequence text, final CharSequence needle1, @SuppressWarnings("SameParameterValue") final String needle2) {
		final int needle1_length = needle1.length(), needle2_length = needle2.length();
		return text.length() > needle1_length + needle2_length && TextUtils.regionMatches(text, 0, needle1, 0, needle1_length)
				&& TextUtils.regionMatches(text, needle1_length, needle2, 0, needle2_length);
	}

	private static WeChatMessage buildFromCarMessage(final Conversation conversation, final String message, final boolean from_self) {
		String text = message, sender = null;
		final int pos = from_self ? 0 : TextUtils.indexOf(message, SENDER_MESSAGE_SEPARATOR);
		if (pos > 0) {
			sender = message.substring(0, pos);
			final boolean title_as_sender = TextUtils.equals(sender, conversation.title);
			if (conversation.isGroupChat() || title_as_sender) {	// Verify the sender with title for non-group conversation
				text = message.substring(pos + SENDER_MESSAGE_SEPARATOR.length());
				if (conversation.isGroupChat() && title_as_sender) sender = SELF;		// WeChat incorrectly use group chat title as sender for self-sent messages.
			} else sender = null;		// Not really the sender name, revert the parsing result.
		}
		return new WeChatMessage(conversation, from_self ? SELF : sender, EmojiTranslator.translate(text), 0);
	}

	private Message toMessage() {
		final Person person = SELF.equals(sender) ? null : conversation.isGroupChat() ? conversation.getGroupParticipant(sender, nick) : conversation.sender().build();
		return new Message(text, time, person);
	}

	private WeChatMessage(final Conversation conversation, final @Nullable CharSequence sender, final CharSequence text, final long time) {
		this.conversation = conversation;
		this.sender = nick = sender == null ? null : sender.toString();		// Nick defaults to sender
		this.text = text;
		this.time = time;
	}

	private final Conversation conversation;
	private final @Nullable String sender;
	private final @Nullable String nick;
	private final CharSequence text;
	private final long time;
}
