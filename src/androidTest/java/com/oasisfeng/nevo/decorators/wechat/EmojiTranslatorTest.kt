package com.oasisfeng.nevo.decorators.wechat

import com.oasisfeng.nevo.decorators.wechat.EmojiTranslator.translate
import org.junit.Assert
import org.junit.Test

/**
 * Created by Oasis on 2018-8-9.
 */
class EmojiTranslatorTest {

    @Test fun testConvert() {
        test("[Smile]", "😃")
        test("Left[Smile]", "Left😃")
        test("[Smile] Right", "😃 Right")
        test("Left[Smile] Right", "Left😃 Right")
        test("Left [色][色][发呆]Right", "Left 😍😍😳Right")
        test("Left[[Smile]", "Left[😃")
        test("Left[Smile]]", "Left😃]")
        test("Left[[Smile]]", "Left[😃]")
        test("Left[NotEmoji][][[Smile][", "Left[NotEmoji][][😃[")
    }

    companion object {
        private fun test(input: String, expected: String) = Assert.assertEquals(expected, translate(input).toString())
    }
}