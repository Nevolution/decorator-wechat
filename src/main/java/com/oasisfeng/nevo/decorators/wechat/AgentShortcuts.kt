package com.oasisfeng.nevo.decorators.wechat

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.LocusId
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.N
import android.os.Build.VERSION_CODES.N_MR1
import android.os.Build.VERSION_CODES.Q
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.AGENT_PACKAGE
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.TAG
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.WECHAT_PACKAGE
import java.lang.reflect.Method

@RequiresApi(N_MR1) class AgentShortcuts(private val context: Context) {

	companion object {
		fun buildShortcutId(key: String) = "C:$key"
	}

	private fun updateShortcut(conversation: Conversation, profile: UserHandle): Boolean {
		val key = conversation.key ?: return false.also { Log.i(TAG, "Postpone shortcut update until conversation key is fetched.") }
		val agentContext = createAgentContext(profile) ?: return false
		if (SDK_INT >= N && agentContext.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return false // Shortcuts cannot be changed if user is locked.

		val activity = agentContext.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN)   // Use agent context to resolve in proper user.
				.addCategory(Intent.CATEGORY_LAUNCHER).setPackage(AGENT_PACKAGE), 0)?.activityInfo?.name
				?: return false.also { Log.d(TAG, "No shortcut update due to lack of agent launcher activity") }

		val sm = agentContext.getShortcutManager() ?: return false
		if (sm.isRateLimitingActive)
			return false.also { Log.w(TAG, "Due to rate limit, shortcut is not updated: $key") }

		val shortcuts = sm.dynamicShortcuts.apply { sortBy { it.rank }}; val count = shortcuts.size
		shortcuts.forEach { shortcut -> if (buildShortcutId(key) == shortcut.id) return true }
		if (count >= sm.maxShortcutCountPerActivity - sm.manifestShortcuts.size)
			sm.removeDynamicShortcuts(listOf(shortcuts.removeAt(0).id))

		val intent = Intent().setComponent(ComponentName(WECHAT_PACKAGE, "com.tencent.mm.ui.LauncherUI"))
				.putExtra("Main_User", key).putExtra(@Suppress("SpellCheckingInspection") "Intro_Is_Muti_Talker", false)
				.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
		val shortcut = ShortcutInfo.Builder(agentContext, buildShortcutId(key)).setActivity(ComponentName(AGENT_PACKAGE, activity))
				.setShortLabel(conversation.title).setRank(if (conversation.isGroupChat) 1 else 0)  // Always keep last direct message conversation on top.
				.setIntent(intent.apply { if (action == null) action = Intent.ACTION_MAIN })
				.setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION)).apply {
					if (conversation.icon != null) setIcon(IconHelper.convertToAdaptiveIcon(context, sm, conversation.icon))
					if (SDK_INT >= Q) @SuppressLint("RestrictedApi") {
						setLongLived(true).setLocusId(LocusId(key))
						if (! conversation.isGroupChat) setPerson(conversation.sender().build().toAndroidPerson()) }}
		return if (sm.addDynamicShortcuts(listOf(shortcut.build()))) true.also { Log.i(TAG, "Shortcut updated for $key") }
		else false.also { Log.e(TAG, "Unexpected rate limit.") }
	}

	private fun createAgentContext(profile: UserHandle): Context?
		= try {
			if (profile == Process.myUserHandle()) context.createPackageContext(AGENT_PACKAGE, 0)
			else mMethodCreatePackageContextAsUser?.invoke(context, AGENT_PACKAGE, 0, profile) as? Context }
		catch (e: PackageManager.NameNotFoundException) { null }
		catch (e: RuntimeException) { null.also { Log.e(TAG, "Error creating context for agent in user ${profile.hashCode()}", e) }}

	fun updateShortcutIfNeeded(conversation: Conversation, profile: UserHandle) {
		val key = conversation.key
		if (SDK_INT < N_MR1 || key == null || ! conversation.isChat || conversation.isBotMessage) return
		if (mDynamicShortcutContacts.get(key) == null) {
			try { if (updateShortcut(conversation, profile)) mDynamicShortcutContacts.put(key, Unit) }
			catch (e: RuntimeException) { Log.e(TAG, "Error publishing shortcut for $key", e) }}
	}

	private fun Context.getShortcutManager() = getSystemService(ShortcutManager::class.java)

	/** Local mark to reduce repeated shortcut updates */
	private val mDynamicShortcutContacts = LruCache<String, Unit>(3)    // Do not rely on maxShortcutCountPerActivity(), as most launcher only display top 4 shortcuts (including manifest shortcuts)

	//	private val mShortcutIdsByProfile = SparseArray<Set<Int>>()
	private val mMethodCreatePackageContextAsUser: Method? by lazy {
		try { Context::class.java.getMethod("createPackageContextAsUser") } catch (e: ReflectiveOperationException) { null }}
}
