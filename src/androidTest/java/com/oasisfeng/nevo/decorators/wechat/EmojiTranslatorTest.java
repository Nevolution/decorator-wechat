package com.oasisfeng.nevo.decorators.wechat;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Created by Oasis on 2018-8-9.
 */
public class EmojiTranslatorTest {

	@Test public void testConvert() {
		test("[Smile]", "😃");
		test("Left[Smile]", "Left😃");
		test("[Smile] Right", "😃 Right");
		test("Left[Smile] Right", "Left😃 Right");
		test("Left [色][色][发呆]Right", "Left 😍😍😳Right");

		test("Left[[Smile]", "Left[😃");
		test("Left[Smile]]", "Left😃]");
		test("Left[[Smile]]", "Left[😃]");

		test("Left[NotEmoji][][[Smile][", "Left[NotEmoji][][😃[");
	}

	private static void test(final String input, final String expected) {
		assertEquals(expected, EmojiTranslator.translate(input).toString());
	}
}