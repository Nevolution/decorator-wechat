/*
 * Copyright (C) 2015 The Nevolution Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.nevo.decorators.wechat

import android.text.TextUtils
import androidx.core.app.NotificationCompat.MessagingStyle.Message

/**
 * A no-smart implementation for Smart Reply
 *
 * Created by Oasis on 2018-8-10.
 */
internal object SmartReply {
    private val REPLIES_FOR_QUESTION =
        arrayOf(arrayOf<CharSequence>("👌", "好", "对", "没问题"), arrayOf<CharSequence>("👌", "OK", "Ye"))

    fun generateChoices(messages: List<Message>): Array<CharSequence>? {
        if (messages.isEmpty()) return null
        val text = messages[messages.size - 1].text
        val chinese = TextUtils.indexOf(text, '？') >= 0
        return if (chinese || TextUtils.indexOf(text, '?') >= 0) REPLIES_FOR_QUESTION[if (chinese) 0 else 1] else null
    }
}