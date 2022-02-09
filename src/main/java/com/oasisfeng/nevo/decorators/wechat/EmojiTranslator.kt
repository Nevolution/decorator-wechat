package com.oasisfeng.nevo.decorators.wechat

import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.util.Log

/**
 * Translate Emoji markers to Emoji characters.
 *
 * Created by Oasis on 2018-8-9.
 */
object EmojiTranslator {

    private val CHINESE_MAP: MutableMap<String, String> = HashMap(EmojiMap.MAP.size)
    private val ENGLISH_MAP: MutableMap<String, String> = HashMap(EmojiMap.MAP.size)

    init {
        for (entry in EmojiMap.MAP) {
            entry[0]?.also { CHINESE_MAP[it] = entry[2] }
            entry[1]?.also { ENGLISH_MAP[it] = entry[2] }
        }
    }

    fun translate(text: CharSequence?): CharSequence? {
        if (text == null) return null
        var bracketEnd = text.indexOf(']')
        if (bracketEnd == -1) return text
        var bracketStart = TextUtils.lastIndexOf(text, '[', bracketEnd - 2) // At least 1 char between brackets
        if (bracketStart == -1) return text

        var builder: SpannableStringBuilder? = null
        var offset = 0
        while (bracketStart >= 0 && bracketEnd >= 0) {
            val marker = text.subSequence(bracketStart + 1, bracketEnd).toString()
            val firstChar = marker[0]
            val emoji = (if (firstChar in 'A'..'Z') ENGLISH_MAP else CHINESE_MAP)[marker]
            if (emoji != null) {
                if (builder == null) builder = SpannableStringBuilder(text)
                builder.replace(bracketStart + offset, bracketEnd + 1 + offset, emoji)
                offset += emoji.length - marker.length - 2
            } else if (BuildConfig.DEBUG) Log.d(TAG, "Not translated $marker: $text")
            bracketEnd = TextUtils.indexOf(text, ']', bracketEnd + 3) // "]...[X..."
            bracketStart = TextUtils.lastIndexOf(text, '[', bracketEnd - 2)
        }
        return builder ?: text
    }
}