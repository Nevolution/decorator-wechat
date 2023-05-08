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

@file:Suppress("DEPRECATION")

package com.oasisfeng.nevo.decorators.wechat

import android.app.AlertDialog
import android.content.*
import android.content.Intent.*
import android.content.pm.PackageManager.*
import android.net.Uri
import android.os.Bundle
import android.preference.Preference
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceActivity
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.Companion.ACTION_SETTINGS_CHANGED
import com.oasisfeng.nevo.sdk.NevoDecoratorService

/**
 * Entry activity. Some ROMs (including Samsung, OnePlus) require a launcher activity to allow any component being bound by other app.
 */
class WeChatDecoratorSettingsActivity : PreferenceActivity() {

    @Deprecated("Deprecated in Java") override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = preferenceManager
        manager.sharedPreferencesName = WeChatDecorator.PREFERENCES_NAME
        manager.setStorageDeviceProtected()
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
                ! isNevoInstalled -> OnPreferenceClickListener { installNevolution() }
                isWechatInstalled && ! running -> OnPreferenceClickListener { activate() }
                else -> null
            }
        }

        val context = this
        (findPreference(getString(R.string.pref_compat_mode)) as android.preference.TwoStatePreference).apply {
            val isAndroidAutoAvailable = getPackageVersion(ANDROID_AUTO_PACKAGE) >= 0
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
            val agentVersion = getPackageVersion(AGENT_PACKAGE)
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
                onPreferenceClickListener = OnPreferenceClickListener { selectAgentLabel() }
            } else {
                setSummary(if (agentVersion < 0) R.string.pref_agent_summary else R.string.pref_agent_summary_update)
                onPreferenceClickListener = OnPreferenceClickListener {
                    startActivity(Intent(ACTION_VIEW, Uri.parse(AGENT_URL)).addFlags(FLAG_ACTIVITY_NEW_TASK))
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

    private fun getPackageVersion(pkg: String): Int {
        return try { packageManager.getPackageInfo(pkg, 0).versionCode } catch (e: NameNotFoundException) { -1 }
    }

    @Deprecated("Deprecated in Java") override fun onDestroy() {
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

    private val mPreferencesChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs: SharedPreferences, key: String? ->
        Log.d(TAG, "Settings changed, notify decorator now.")
        sendBroadcast(Intent(ACTION_SETTINGS_CHANGED).setPackage(packageName).putExtra(key, prefs.getBoolean(key, false)))
    }

    private val mDummyReceiver: BroadcastReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) {}}

    companion object {
        private const val CURRENT_AGENT_VERSION = 1700
        private const val NEVOLUTION_PACKAGE = "com.oasisfeng.nevo"
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val APP_MARKET_PREFIX = "market://details?id="
        private const val AGENT_URL = "https://github.com/Nevolution/decorator-wechat/releases"
    }
}
