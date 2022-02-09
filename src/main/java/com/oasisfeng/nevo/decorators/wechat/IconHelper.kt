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

import android.content.Context
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Icon
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat

object IconHelper {

	fun convertToAdaptiveIcon(context: Context, source: IconCompat): Icon =
		if (source.type == Icon.TYPE_RESOURCE) source.toIcon(null)
		else source.toLocalAdaptiveIcon(context, context.getSystemService()!!)

	private fun drawableToBitmap(context: Context, sm: ShortcutManager, icon: IconCompat): Bitmap {
		val extraInsetFraction = AdaptiveIconDrawable.getExtraInsetFraction()
		val width = sm.iconMaxWidth; val height = sm.iconMaxHeight
		val xInset = (width * extraInsetFraction).toInt(); val yInset = (height * extraInsetFraction).toInt()
		return Bitmap.createBitmap(width + xInset * 2, height + yInset * 2, Bitmap.Config.ARGB_8888).also { bitmap ->
			icon.loadDrawable(context)?.apply {
				setBounds(xInset, yInset, width + xInset, height + yInset)
				draw(Canvas(bitmap))
			}
		}
	}

	fun IconCompat.toLocalAdaptiveIcon(context: Context, sm: ShortcutManager): Icon =
		if (type == IconCompat.TYPE_ADAPTIVE_BITMAP) toIcon(null)
		else Icon.createWithAdaptiveBitmap(drawableToBitmap(context, sm, this))
}
