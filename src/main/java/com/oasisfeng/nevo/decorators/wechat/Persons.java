package com.oasisfeng.nevo.decorators.wechat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import androidx.annotation.Nullable;
import androidx.core.app.Person;

/**
 * Created by Oasis on 2018-11-9.
 */
public class Persons {

	WeChatPerson get(final String conversation, final String key, final String nick) {
		WeChatPerson person = null;
		Map<String, WeChatPerson> persons_by_key = mPersonsInConversation.get(conversation);
		if (persons_by_key == null) mPersonsInConversation.put(conversation, persons_by_key = new HashMap<>());
		else person = persons_by_key.get(key);
		if (person == null) person = new WeChatPerson(key);
		persons_by_key.put(key, person.setNick(nick));		// Update nick if changed
		return person;
	}

	WeChatPerson getSelf() { return mSelf; }

	Persons(final CharSequence self_display_name) {
		mSelf.setName(self_display_name);
		// The following line is not working. TODO: Figure out why
		//if (SDK_INT > O_MR1) mSelf.setUri(ContactsContract.Profile.CONTENT_URI.toString());
	}

	private final Map<String/* conversation */, Map<String/* key */, WeChatPerson>> mPersonsInConversation = new HashMap<>();
	private final WeChatPerson mSelf = new WeChatPerson(null);

	static class WeChatPerson extends Person.Builder {

		WeChatPerson setNick(final @Nullable String nick) {
			if (Objects.equals(nick, mNick)) return this;
			mNick = nick;
			setName(EmojiTranslator.translate(nick));
			mPerson = null;		// Invalidate the cache
			return this;
		}

		Person toPerson() { return mPerson != null ? mPerson : (mPerson = build()); }
		WeChatPerson(final String key) { setKey(key); }

		private Person mPerson;		// Cache the built Person instance
		private @Nullable String mNick;
	}
}
