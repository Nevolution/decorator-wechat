package com.oasisfeng.nevo.decorators.wechat

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_ACTIVITIES
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.graphics.drawable.Icon.TYPE_RESOURCE
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.N
import android.os.Build.VERSION_CODES.N_MR1
import android.os.Build.VERSION_CODES.Q
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.ArrayMap
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.AGENT_PACKAGE
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.TAG
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.WECHAT_PACKAGE
import java.lang.reflect.Method

@RequiresApi(N_MR1) class AgentShortcuts(private val context: Context) {

	companion object {
		fun buildShortcutId(key: String) = "C:$key"
	}

	/** @return true if shortcut is ready */
	private fun updateShortcut(id: String, conversation: Conversation, agentContext: Context): Boolean {
		if (SDK_INT >= N && agentContext.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return false // Shortcuts cannot be changed if user is locked.

		val activity = agentContext.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN)   // Use agent context to resolve in proper user.
				.addCategory(Intent.CATEGORY_LAUNCHER).setPackage(AGENT_PACKAGE), 0)?.activityInfo?.name
				?: return false.also { Log.d(TAG, "No shortcut update due to lack of agent launcher activity") }

		val sm = agentContext.getShortcutManager() ?: return false
		if (sm.isRateLimitingActive)
			return false.also { Log.w(TAG, "Due to rate limit, shortcut is not updated: $id") }

		val shortcuts = sm.dynamicShortcuts.apply { sortBy { it.rank }}; val count = shortcuts.size
		if (count >= sm.maxShortcutCountPerActivity - sm.manifestShortcuts.size)
			sm.removeDynamicShortcuts(listOf(shortcuts.removeAt(0).id.also { Log.i(TAG, "Evict excess shortcut: $it") }))

		val intent = if (conversation.ext != null) Intent().setClassName(WECHAT_PACKAGE, "com.tencent.mm.ui.LauncherUI")
				.putExtra("Main_User", conversation.key).putExtra(@Suppress("SpellCheckingInspection") "Intro_Is_Muti_Talker", false)
				.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
		else {
			val bubbleActivity = (mAgentBubbleActivity
					?: try { context.packageManager.getPackageInfo(AGENT_PACKAGE, GET_ACTIVITIES).activities
					.firstOrNull { it.enabled && it.flags.and(FLAG_ALLOW_EMBEDDED) != 0 }?.name ?: "" }
					catch (e: PackageManager.NameNotFoundException) { "" }.also { mAgentBubbleActivity = it })   // "" to indicate N/A
			if (bubbleActivity.isNotEmpty()) {
				Intent(Intent.ACTION_VIEW_LOCUS).putExtra(Intent.EXTRA_LOCUS_ID, id).setClassName(AGENT_PACKAGE, bubbleActivity)
			} else Intent().setClassName(AGENT_PACKAGE, activity)
		}

		val shortcut = ShortcutInfo.Builder(agentContext, id).setActivity(ComponentName(AGENT_PACKAGE, activity))
				.setShortLabel(conversation.title).setRank(if (conversation.isGroupChat) 1 else 0)  // Always keep last direct message conversation on top.
				.setIntent(intent.apply { if (action == null) action = Intent.ACTION_MAIN })
				.setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION)).apply {
					if (conversation.icon != null) setIcon(conversation.icon.toLocalAdaptiveIcon(context, sm))
					if (SDK_INT >= Q) @SuppressLint("RestrictedApi") {
						setLongLived(true).setLocusId(LocusId(id))
						if (! conversation.isGroupChat) setPerson(conversation.sender().build().toAndroidPerson()) }}.build()
		if (BuildConfig.DEBUG) { Log.i(TAG, "Updating shortcut \"${shortcut.id}\": ${shortcut.intent.toString()}") }
		return if (sm.addDynamicShortcuts(listOf(shortcut))) true.also { Log.i(TAG, "Shortcut updated: $id") }
		else false.also { Log.e(TAG, "Unexpected rate limit.") }
	}

	private fun createAgentContext(profile: UserHandle): Context?
		= try {
			if (profile == Process.myUserHandle()) context.createPackageContext(AGENT_PACKAGE, 0)
			else mMethodCreatePackageContextAsUser?.invoke(context, AGENT_PACKAGE, 0, profile) as? Context }
		catch (e: PackageManager.NameNotFoundException) { null }
		catch (e: RuntimeException) { null.also { Log.e(TAG, "Error creating context for agent in user ${profile.hashCode()}", e) }}

	/** @return whether shortcut is ready */
	@RequiresApi(N_MR1) fun updateShortcutIfNeeded(id: String, conversation: Conversation, profile: UserHandle): Boolean {
		if (! conversation.isChat || conversation.isBotMessage) return false
		val agentContext = mAgentContextByProfile[profile] ?: return false
		if (mDynamicShortcutContacts.get(id) != null) return true
		try { if (updateShortcut(id, conversation, agentContext))
			return true.also { if (conversation.icon.type != TYPE_RESOURCE) mDynamicShortcutContacts.put(id, Unit) }}   // If no large icon, wait for the next update
		catch (e: RuntimeException) { Log.e(TAG, "Error publishing shortcut: $id", e) }
		return false
	}

	private fun Context.getShortcutManager() = getSystemService(ShortcutManager::class.java)

	private var mAgentBubbleActivity: String? = null
	private val mPackageEventReceiver = object: LauncherApps.Callback() {

		private fun update(pkg: String, user: UserHandle) {
			if (pkg == AGENT_PACKAGE) mAgentContextByProfile[user] = createAgentContext(user)
		}

		override fun onPackageRemoved(pkg: String, user: UserHandle) { update(pkg, user) }
		override fun onPackageAdded(pkg: String, user: UserHandle) { update(pkg, user) }
		override fun onPackageChanged(pkg: String, user: UserHandle) { update(pkg, user) }
		override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) { pkgs.forEach { update(it, user) }}
		override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) { pkgs.forEach { update(it, user) }}
	}

	/** Local mark to reduce repeated shortcut updates */
	private val mDynamicShortcutContacts = LruCache<String/* shortcut ID */, Unit>(3)   // Do not rely on maxShortcutCountPerActivity(), as most launcher only display top 4 shortcuts (including manifest shortcuts)

	private val mMethodCreatePackageContextAsUser: Method? by lazy {
		try { Context::class.java.getMethod("createPackageContextAsUser") } catch (e: ReflectiveOperationException) { null }}
	private val mAgentContextByProfile = ArrayMap<UserHandle, Context?>()

	init {
		context.getSystemService<LauncherApps>()?.registerCallback(mPackageEventReceiver)
		context.getSystemService<UserManager>()?.userProfiles?.forEach {
			mAgentContextByProfile[it] = createAgentContext(it) }
	}

	fun close() {
		context.getSystemService<LauncherApps>()?.unregisterCallback(mPackageEventReceiver)
		mAgentContextByProfile.clear()
	}
}

const val FLAG_ALLOW_EMBEDDED = -0x80000000
