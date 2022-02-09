package com.oasisfeng.nevo.decorators.wechat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.content.Intent.*
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager.*
import android.net.Uri
import android.os.*
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.N
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.TwoStatePreference
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.Companion.ACTION_SETTINGS_CHANGED
import com.oasisfeng.nevo.sdk.NevoDecoratorService

/**
 * Entry activity. Some ROMs (including Samsung, OnePlus) require a launcher activity to allow any component being bound by other app.
 */
@SuppressLint("ExportedPreferenceActivity")
class WeChatDecoratorSettingsActivity : PreferenceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = preferenceManager
        manager.sharedPreferencesName = WeChatDecorator.PREFERENCES_NAME
        if (SDK_INT >= N) manager.setStorageDeviceProtected()
        preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(mPreferencesChangeListener)
        addPreferencesFromResource(R.xml.decorators_wechat_settings)
    }

    override fun onResume() {
        super.onResume()
        val pm = packageManager

        val isNevoInstalled = try { pm.getApplicationInfo(NEVOLUTION_PACKAGE, 0); true } catch (e: NameNotFoundException) { false }
        val isWechatInstalled = try { pm.getApplicationInfo(WECHAT_PACKAGE, GET_UNINSTALLED_PACKAGES); true } catch (e: NameNotFoundException) { false }
        val running = isDecoratorRunning()

        findPreference(getString(R.string.pref_activate)).apply {
            isEnabled = ! isNevoInstalled || isWechatInstalled // No reason to promote WeChat if not installed.
            isSelectable = ! running
            summary = when {
                ! isNevoInstalled -> getText(R.string.pref_activate_summary_nevo_not_installed)
                ! isWechatInstalled -> getText(R.string.pref_activate_summary_wechat_not_installed)
                running -> getText(R.string.pref_activate_summary_already_activated)
                else -> null
            }
            onPreferenceClickListener = when {
                ! isNevoInstalled -> Preference.OnPreferenceClickListener { installNevolution() }
                isWechatInstalled && ! running -> Preference.OnPreferenceClickListener { activate() }
                else -> null
            }
        }

        val isAndroidAutoAvailable = getPackageVersion(ANDROID_AUTO_PACKAGE) >= 0
        findPreference(getString(R.string.pref_extension)).apply {
            val profilesWithoutAndroidAuto = ArrayList<Int>()
            val profiles = getSystemService(UserManager::class.java)?.userProfiles ?: emptyList()
            if (SDK_INT >= N) {
                val la = getSystemService(LauncherApps::class.java)
                if (la != null) for (profile in profiles) {
                    if (profile == Process.myUserHandle()) continue
                    if (la.getApplicationInfo(WECHAT_PACKAGE, profile) == null) continue
                    if (la.getApplicationInfo(ANDROID_AUTO_PACKAGE, profile) == null)
                        profilesWithoutAndroidAuto.add(profile.hashCode())
                }
            }
            isEnabled = isWechatInstalled
            isSelectable = ! isAndroidAutoAvailable || profilesWithoutAndroidAuto.isNotEmpty()
            summary = when {
                ! isAndroidAutoAvailable -> getText(R.string.pref_extension_summary)
                profilesWithoutAndroidAuto.isEmpty() -> getText(R.string.pref_extension_summary_installed)
                else -> getString(R.string.pref_extension_summary_not_cloned_in_island,
                    if (profiles.size <= 2 /* Just one Island space */) "" else profilesWithoutAndroidAuto.toString()) }
            onPreferenceClickListener = when {
                ! isAndroidAutoAvailable -> Preference.OnPreferenceClickListener { installExtension() }
                profilesWithoutAndroidAuto.isNotEmpty() -> Preference.OnPreferenceClickListener { showExtensionInIsland() }
                else -> null }
        }

        val context = this
        (findPreference(getString(R.string.pref_compat_mode)) as TwoStatePreference).apply {
            if (isAndroidAutoAvailable && Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0) {
                CompatModeController.query(context) { checked: Boolean? -> isChecked = checked!! }
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                    val enabled = newValue as Boolean
                    CompatModeController.request(context, enabled) { changed: Boolean -> if (changed) isChecked = enabled }
                    false // Will be updated by the callback in previous line.
                }
            } else preferenceScreen.removePreference(this)
        }

        findPreference(getString(R.string.pref_agent))?.apply {
            var agentVersion = getPackageVersion(AGENT_PACKAGE)
            if (agentVersion < 0) agentVersion = getPackageVersion(AGENT_LEGACY_PACKAGE)
            isEnabled = isWechatInstalled
            if (agentVersion >= CURRENT_AGENT_VERSION) {
                val launcherIntent = Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER)
                    .setPackage(AGENT_PACKAGE)
                val disabled = pm.queryIntentActivities(launcherIntent, 0).isEmpty()
                @StringRes val prefix = if (disabled) R.string.pref_agent_summary_prefix_disabled else R.string.pref_agent_summary_prefix_enabled
                summary = """
                    ${getString(prefix)}
                    ${getString(R.string.pref_agent_summary_installed)}
                    """.trimIndent()
                onPreferenceClickListener = Preference.OnPreferenceClickListener { selectAgentLabel() }
            } else {
                setSummary(if (agentVersion < 0) R.string.pref_agent_summary else R.string.pref_agent_summary_update)
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    try {
                        pm.getApplicationInfo(AGENT_LEGACY_PACKAGE, 0)
                    } catch (ignored: NameNotFoundException) {
                        installAssetApk("agent.apk")
                        return@OnPreferenceClickListener true
                    }
                    AlertDialog.Builder(context).setMessage(R.string.prompt_uninstall_agent_first)
                        .setPositiveButton(R.string.action_continue) { _: DialogInterface?, _: Int ->
                            startActivity(Intent(ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", AGENT_LEGACY_PACKAGE, null))
                                .putExtra("android.intent.extra.UNINSTALL_ALL_USERS", true))
                        }.show()
                    true
                }
            }
        }
    }

    private fun selectAgentLabel(): Boolean {
        val pm = packageManager
        val query = Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(AGENT_PACKAGE)
        val resolves = pm.queryIntentActivities(query, GET_DISABLED_COMPONENTS)
        val size = resolves.size
        check(size > 1) { "No activities found for $query" }
        val labels = resolves.map { it.activityInfo.loadLabel(pm) }
            .plus(getText(R.string.action_disable_agent_launcher_entrance)).toTypedArray()
        AlertDialog.Builder(this).setSingleChoiceItems(labels, -1) { dialog: DialogInterface, which: Int -> // TODO: Item cannot be selected on Sony device?
            for (i in resolves.indices)
                pm.setComponentEnabledSetting(ComponentName(AGENT_PACKAGE, resolves[i].activityInfo.name),
                    if (i == which) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP)
            dialog.dismiss()
        }.show()
        return true
    }

    private fun isDecoratorRunning(): Boolean {
        val service = Intent(this, WeChatDecorator::class.java).setAction(NevoDecoratorService.ACTION_DECORATOR_SERVICE)
        return null != mDummyReceiver.peekService(this, service)
    }

    private fun installExtension() = true.also {
        if (isPlayStoreSystemApp()) {
            AlertDialog.Builder(this).setMessage(R.string.prompt_extension_install)
                .setPositiveButton(R.string.action_install_android_auto) { d: DialogInterface?, c: Int -> showAndroidAutoInPlayStore() }
                .setNeutralButton(R.string.action_install_dummy_auto) { d: DialogInterface?, c: Int -> installDummyAuto() }
                .show()
        } else installDummyAuto()
    }

    @SuppressLint("InlinedApi") private fun showExtensionInIsland() = true.also { try {
        startActivity(Intent(ACTION_SHOW_APP_INFO).putExtra(EXTRA_PACKAGE_NAME, ANDROID_AUTO_PACKAGE).setPackage(ISLAND_PACKAGE))
    } catch (e: Exception) {}}

    private fun showAndroidAutoInPlayStore() {
        val uri = Uri.parse("https://play.google.com/store/apps/details?id=$ANDROID_AUTO_PACKAGE")
        try { startActivity(Intent(ACTION_VIEW, uri).setPackage(PLAY_STORE_PACKAGE).addFlags(FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: ActivityNotFoundException) { /* In case of Google Play malfunction */ }
    }

    private fun installDummyAuto() {
        installAssetApk("dummy-auto.apk")
    }

    private fun installAssetApk(asset_name: String) {
        val authority = packageManager.getProviderInfo(ComponentName(this, AssetFileProvider::class.java), 0).authority
        val uri = Uri.parse("content://$authority/$asset_name")
        try { startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: ActivityNotFoundException) {}
    }

    private fun getPackageVersion(pkg: String): Int {
        return try { packageManager.getPackageInfo(pkg, 0).versionCode } catch (e: NameNotFoundException) { -1 }
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(mPreferencesChangeListener)
        super.onDestroy()
    }

    private fun installNevolution(): Boolean {
        try { startActivity(Intent(ACTION_VIEW, Uri.parse(APP_MARKET_PREFIX + NEVOLUTION_PACKAGE))) }
        catch (ignored: ActivityNotFoundException) { } // TODO: Landing web page
        return true
    }

    private fun activate(): Boolean {
        try {
            startActivityForResult(Intent("com.oasisfeng.nevo.action.ACTIVATE_DECORATOR").setPackage(NEVOLUTION_PACKAGE)
                .putExtra("nevo.decorator", ComponentName(this, WeChatDecorator::class.java))
                .putExtra("nevo.target", WECHAT_PACKAGE), 0)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(NEVOLUTION_PACKAGE))
        }
        return true
    }

    private fun isPlayStoreSystemApp(): Boolean =
        try { packageManager.getApplicationInfo(PLAY_STORE_PACKAGE, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        catch (e: NameNotFoundException) { false }

    private val mPreferencesChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs: SharedPreferences, key: String? ->
        Log.d(TAG, "Settings changed, notify decorator now.")
        sendBroadcast(Intent(ACTION_SETTINGS_CHANGED).setPackage(packageName).putExtra(key, prefs.getBoolean(key, false)))
    }

    private val mDummyReceiver: BroadcastReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) {}}

    companion object {
        private const val CURRENT_AGENT_VERSION = 1700
        private const val NEVOLUTION_PACKAGE = "com.oasisfeng.nevo"
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val ISLAND_PACKAGE = "com.oasisfeng.island"
        private const val APP_MARKET_PREFIX = "market://details?id="
        private const val AGENT_LEGACY_PACKAGE = "com.oasisfeng.nevo.agents.wechat"

        @RequiresApi(N) @SuppressLint("NewApi") private fun LauncherApps.getApplicationInfo(pkg: String, profile: UserHandle) =
            try { getApplicationInfo(pkg, 0, profile) } catch (e: NameNotFoundException) { null }
    }
}