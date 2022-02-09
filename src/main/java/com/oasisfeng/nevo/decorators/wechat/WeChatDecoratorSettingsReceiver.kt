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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import com.oasisfeng.nevo.decorators.wechat.WeChatDecoratorSettingsActivity

/**
 * Created by Oasis on 2018/4/26.
 */
class WeChatDecoratorSettingsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) =
        context.startActivity(Intent(context, WeChatDecoratorSettingsActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK))
}