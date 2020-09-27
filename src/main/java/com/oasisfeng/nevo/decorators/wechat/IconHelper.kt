package com.oasisfeng.nevo.decorators.wechat

import android.content.Context
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Icon
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat

@RequiresApi(O) object IconHelper {

	@JvmStatic fun convertToAdaptiveIcon(context: Context, source: IconCompat): Icon
			= if (source.type == Icon.TYPE_RESOURCE) source.toIcon(null)
			else source.toLocalAdaptiveIcon(context, context.getSystemService()!!)

	fun drawableToBitmap(context: Context, sm: ShortcutManager, icon: IconCompat): Bitmap {
		val extraInsetFraction = AdaptiveIconDrawable.getExtraInsetFraction()
		val width = sm.iconMaxWidth; val height = sm.iconMaxHeight
		val xInset = (width * extraInsetFraction).toInt(); val yInset = (height * extraInsetFraction).toInt()
		return Bitmap.createBitmap(width + xInset * 2, height + yInset * 2, Bitmap.Config.ARGB_8888).also { bitmap ->
			icon.loadDrawable(context)?.apply {
				setBounds(xInset, yInset, width + xInset, height + yInset)
				draw(Canvas(bitmap)) }}
	}
}

fun IconCompat.toLocalAdaptiveIcon(context: Context, sm: ShortcutManager): Icon
		= @Suppress("CascadeIf") if (SDK_INT < O) toIcon(null)
		else if (type == Icon.TYPE_ADAPTIVE_BITMAP) toIcon(null)
		else Icon.createWithAdaptiveBitmap(IconHelper.drawableToBitmap(context, sm, this))