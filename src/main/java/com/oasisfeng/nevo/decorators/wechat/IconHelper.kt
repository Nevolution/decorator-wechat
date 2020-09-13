package com.oasisfeng.nevo.decorators.wechat

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Build.VERSION_CODES.O
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.IconCompat

object IconHelper {

	@JvmStatic fun convertToAdaptiveIcon(context: Context, sm: ShortcutManager, source: IconCompat): Icon
			= if (Build.VERSION.SDK_INT < O) @Suppress("DEPRECATION") source.toIcon()
			else @SuppressLint("SwitchIntDef") when (source.type) {
				Icon.TYPE_ADAPTIVE_BITMAP, Icon.TYPE_RESOURCE -> @Suppress("DEPRECATION") source.toIcon()
				else -> Icon.createWithAdaptiveBitmap(drawableToBitmap(context, sm, source)) }

	@RequiresApi(O) private fun drawableToBitmap(context: Context, sm: ShortcutManager, icon: IconCompat): Bitmap {
		val extraInsetFraction = AdaptiveIconDrawable.getExtraInsetFraction()
		val width = sm.iconMaxWidth; val height = sm.iconMaxHeight
		val xInset = (width * extraInsetFraction).toInt(); val yInset = (height * extraInsetFraction).toInt()
		return Bitmap.createBitmap(width + xInset * 2, height + yInset * 2, Bitmap.Config.ARGB_8888).also { bitmap ->
			icon.loadDrawable(context)?.apply {
				setBounds(xInset, yInset, width + xInset, height + yInset)
				draw(Canvas(bitmap)) }}
	}
}