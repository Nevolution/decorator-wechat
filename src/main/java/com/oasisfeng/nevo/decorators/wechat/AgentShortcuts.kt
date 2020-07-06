package com.oasisfeng.nevo.decorators.wechat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.LocusId
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Icon
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.*
import android.os.UserManager
import android.support.annotation.RequiresApi
import android.support.v4.graphics.drawable.IconCompat
import android.util.Log
import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.AGENT_PACKAGE
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.TAG

@RequiresApi(N_MR1) class AgentShortcuts(private val context: Context) {

	fun publishShortcut(conversation: Conversation, intent: Intent): Boolean {
		val locusId = conversation.key ?: return false      // Lack of proper persistent ID
		if (SDK_INT >= N && context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return false // Shortcuts cannot be changed if user is locked.

		val activity = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN)
				.addCategory(Intent.CATEGORY_LAUNCHER).setPackage(AGENT_PACKAGE), 0)?.activityInfo?.name
				?: return true   // Agent is not installed or its launcher activity is disabled.

		val agentContext = try { context.createPackageContext(AGENT_PACKAGE, 0) }
		catch (e: PackageManager.NameNotFoundException) { return true }

		val sm = agentContext.getSystemService(ShortcutManager::class.java) ?: return true
		if (sm.isRateLimitingActive)
			return true.also { Log.w(WeChatDecorator.TAG, "Due to rate limit, shortcut is not updated: " + conversation.key) }

		val shortcuts = sm.dynamicShortcuts; val count = shortcuts.size
		shortcuts.forEachIndexed { i, shortcut ->   // TODO: This is insufficient as it cannot affect the order of shortcuts returned next time.
			if (locusId == shortcut.id) return true.also { if (i != count - 1) shortcuts.add(shortcuts.removeAt(i)) }}  // Move to the end (latest)
		if (count >= sm.maxShortcutCountPerActivity - sm.manifestShortcuts.size)
			sm.removeDynamicShortcuts(listOf(shortcuts.removeAt(0).id))

		val shortcut = ShortcutInfo.Builder(agentContext, locusId).setActivity(ComponentName(AGENT_PACKAGE, activity))
				.setShortLabel(conversation.title).setRank(if (conversation.isGroupChat) 1 else 0)  // TODO: setPerson()
				.setIntent(intent.apply { if (action == null) action = Intent.ACTION_MAIN })
				.setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION)).apply {
					if (SDK_INT >= Q) setLongLived(true).setLocusId(LocusId(locusId))
					if (conversation.icon != null) setIcon(convertToAdaptiveIcon(sm, conversation.icon)) }.build()
		if (sm.addDynamicShortcuts(listOf(shortcut))) Log.i(TAG, "Shortcut published for " + conversation.key)
		else Log.e(TAG, "Unexpected rate limit.")
		return false
	}

	private fun convertToAdaptiveIcon(sm: ShortcutManager, source: IconCompat): Icon
			= if (SDK_INT < O) source.toIcon() else when (source.type) {
				Icon.TYPE_ADAPTIVE_BITMAP, Icon.TYPE_RESOURCE -> source.toIcon()
				else -> Icon.createWithAdaptiveBitmap(drawableToBitmap(sm, source)) }

	@RequiresApi(O) private fun drawableToBitmap(sm: ShortcutManager, icon: IconCompat): Bitmap {
		val extraInsetFraction = AdaptiveIconDrawable.getExtraInsetFraction()
		val width = sm.iconMaxWidth; val height = sm.iconMaxHeight
		val xInset = (width * extraInsetFraction).toInt(); val yInset = (height * extraInsetFraction).toInt()
		return Bitmap.createBitmap(width + xInset * 2, height + yInset * 2, Bitmap.Config.ARGB_8888).also { bitmap ->
			icon.loadDrawable(context).apply {
				setBounds(xInset, yInset, width + xInset, height + yInset)
				draw(Canvas(bitmap)) }}
	}
}
