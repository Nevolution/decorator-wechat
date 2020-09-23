package com.oasisfeng.nevo.decorators.wechat;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;

import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.content.Intent.ACTION_UNINSTALL_PACKAGE;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.TAG;
import static com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.WECHAT_PACKAGE;

/**
 * Entry activity. Some ROMs (including Samsung, OnePlus) require a launcher activity to allow any component being bound by other app.
 */
@SuppressWarnings("deprecation") @SuppressLint("ExportedPreferenceActivity")
public class WeChatDecoratorSettingsActivity extends PreferenceActivity {

	private static final int CURRENT_AGENT_VERSION = 1700;
	private static final String NEVOLUTION_PACKAGE = "com.oasisfeng.nevo";
	private static final String ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead";
	private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final String ISLAND_PACKAGE = "com.oasisfeng.island";
	private static final String APP_MARKET_PREFIX = "market://details?id=";
	private static final String AGENT_LEGACY_PACKAGE = "com.oasisfeng.nevo.agents.wechat";

	@Override protected void onCreate(@Nullable final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		final PreferenceManager manager = getPreferenceManager();
		manager.setSharedPreferencesName(WeChatDecorator.PREFERENCES_NAME);
		if (SDK_INT >= N) manager.setStorageDeviceProtected();
		getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(mPreferencesChangeListener);
		addPreferencesFromResource(R.xml.decorators_wechat_settings);
	}

	@Override protected void onResume() {
		super.onResume();
		final Preference preference_activate = findPreference(getString(R.string.pref_activate));
		boolean nevolution_installed = false, wechat_installed = false, running = false;
		final PackageManager pm = getPackageManager();
		try {
			pm.getApplicationInfo(NEVOLUTION_PACKAGE, 0);
			nevolution_installed = true;
			pm.getApplicationInfo(WECHAT_PACKAGE, PackageManager.GET_UNINSTALLED_PACKAGES);
			wechat_installed = true;
			running = isDecoratorRunning();
		} catch (final NameNotFoundException ignored) {}

		preference_activate.setEnabled(! nevolution_installed || wechat_installed);		// No reason to promote WeChat if not installed.
		preference_activate.setSelectable(! running);
		preference_activate.setSummary(! nevolution_installed ? getText(R.string.pref_activate_summary_nevo_not_installed)
				: ! wechat_installed ? getText(R.string.pref_activate_summary_wechat_not_installed)
				: running ? getText(R.string.pref_activate_summary_already_activated) : null);
		preference_activate.setOnPreferenceClickListener(! nevolution_installed ? this::installNevolution
				: wechat_installed && ! running ? this::activate : null);

		final Preference preference_extension = findPreference(getString(R.string.pref_extension));
		final boolean android_auto_available = getPackageVersion(ANDROID_AUTO_PACKAGE) >= 0;
		final List<Integer> android_auto_unavailable_in_profiles = new ArrayList<>();
		List<UserHandle> profiles = Collections.emptyList();
		if (SDK_INT >= N) {
			final UserManager um = getSystemService(UserManager.class);
			final LauncherApps la = getSystemService(LauncherApps.class);
			if (um != null && la != null) for (final UserHandle profile : (profiles = um.getUserProfiles())) {
				if (profile.equals(Process.myUserHandle())) continue;
				if (getApplicationInfo(la, WECHAT_PACKAGE, profile) == null) continue;
				if (getApplicationInfo(la, ANDROID_AUTO_PACKAGE, profile) == null)
					android_auto_unavailable_in_profiles.add(profile.hashCode());
			}
		}
		preference_extension.setEnabled(wechat_installed);
		preference_extension.setSelectable(! android_auto_available || ! android_auto_unavailable_in_profiles.isEmpty());
		preference_extension.setSummary(! android_auto_available ? getText(R.string.pref_extension_summary)
				: android_auto_unavailable_in_profiles.isEmpty() ? getText(R.string.pref_extension_summary_installed)
				: getString(R.string.pref_extension_summary_not_cloned_in_island,
				profiles.size() <= 2/* Just one Island space */? "" : android_auto_unavailable_in_profiles.toString()));
		preference_extension.setOnPreferenceClickListener(! android_auto_available ? this::installExtension
                : ! android_auto_unavailable_in_profiles.isEmpty() ? this::showExtensionInIsland : null);

		final Preference preference_agent = findPreference(getString(R.string.pref_agent));
		int agent_version = getPackageVersion(WeChatDecorator.AGENT_PACKAGE);
		if (agent_version < 0) agent_version = getPackageVersion(AGENT_LEGACY_PACKAGE);
		preference_agent.setEnabled(wechat_installed);
		if (agent_version >= CURRENT_AGENT_VERSION) {
			final Intent launcher_intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(WeChatDecorator.AGENT_PACKAGE);
			final boolean disabled = pm.queryIntentActivities(launcher_intent, 0).isEmpty();
			final @StringRes int prefix = (disabled ? R.string.pref_agent_summary_prefix_disabled : R.string.pref_agent_summary_prefix_enabled);
			preference_agent.setSummary(getString(prefix) + "\n" + getString(R.string.pref_agent_summary_installed));
			preference_agent.setOnPreferenceClickListener(pref -> selectAgentLabel());
		} else {
			preference_agent.setSummary(agent_version < 0 ? R.string.pref_agent_summary : R.string.pref_agent_summary_update);
			preference_agent.setOnPreferenceClickListener(pref -> {
				try { pm.getApplicationInfo(AGENT_LEGACY_PACKAGE, 0); }
				catch (final NameNotFoundException ignored) {
					installAssetApk("agent.apk");
					return true;
				}
				new AlertDialog.Builder(this).setMessage(R.string.prompt_uninstall_agent_first).setPositiveButton(R.string.action_continue, (d, w) ->
						startActivity(new Intent(ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", AGENT_LEGACY_PACKAGE, null))
								.putExtra("android.intent.extra.UNINSTALL_ALL_USERS", true))).show();
				return true;
			});
		}
	}

	private boolean selectAgentLabel() {
		final PackageManager pm = getPackageManager();
		final Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(WeChatDecorator.AGENT_PACKAGE);
		final List<ResolveInfo> resolves = pm.queryIntentActivities(query, PackageManager.GET_DISABLED_COMPONENTS);
		final int size = resolves.size();
		if (size <= 1) throw new IllegalStateException("No activities found for " + query);
		final CharSequence[] labels = new CharSequence[size + 1];
		final String[] names = new String[size];
		for (int i = 0; i < size; i ++) {
			final ActivityInfo activity = resolves.get(i).activityInfo;
			labels[i] = activity.loadLabel(pm);
			names[i] = activity.name;
		}
		labels[size] = getText(R.string.action_disable_agent_launcher_entrance);
		new AlertDialog.Builder(this).setSingleChoiceItems(labels, -1, (dialog, which) -> {	// TODO: Item cannot be selected on Sony device?
			for (int i = 0; i < names.length; i ++)
				pm.setComponentEnabledSetting(new ComponentName(WeChatDecorator.AGENT_PACKAGE, names[i]),
						i == which ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
			dialog.dismiss();
		}).show();
		return true;
	}

	private boolean toggleHidingLauncherIcon(@SuppressWarnings("unused") final Preference unused, final Object value) {
		final boolean start_from_launcher = getIntent().getAction() != null;
		if (start_from_launcher && value == Boolean.TRUE) {
			new AlertDialog.Builder(this).setMessage(R.string.prompt_hide_prerequisite).setPositiveButton(android.R.string.cancel, null).show();
			return false;
		}
		getPackageManager().setComponentEnabledSetting(new ComponentName(this, getClass()),
				value != Boolean.TRUE ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
		return true;
	}

	private boolean isDecoratorRunning() {
		final Intent service = new Intent(this, WeChatDecorator.class).setAction(NevoDecoratorService.ACTION_DECORATOR_SERVICE);
		return mDummyReceiver.peekService(this, service) != null;
	}

	private boolean installExtension(@SuppressWarnings("unused") final Preference unused) {
		if (isPlayStoreSystemApp()) {
			new AlertDialog.Builder(this).setMessage(R.string.prompt_extension_install)
					.setPositiveButton(R.string.action_install_android_auto, (d, c) -> showAndroidAutoInPlayStore())
					.setNeutralButton(R.string.action_install_dummy_auto, (d, c) -> installDummyAuto()).show();
		} else installDummyAuto();
		return true;
	}

	@SuppressLint("InlinedApi") private boolean showExtensionInIsland(@SuppressWarnings("unused") final Preference unused) {
	    try {
            startActivity(new Intent(Intent.ACTION_SHOW_APP_INFO).putExtra(Intent.EXTRA_PACKAGE_NAME, ANDROID_AUTO_PACKAGE).setPackage(ISLAND_PACKAGE));
        } catch (final Exception ignored) {}
	    return true;
    }

	private void showAndroidAutoInPlayStore() {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + ANDROID_AUTO_PACKAGE))
					.setPackage(PLAY_STORE_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
		} catch(final ActivityNotFoundException e) { /* In case of Google Play malfunction */ }
	}

	private void installDummyAuto() {
		installAssetApk("dummy-auto.apk");
	}

	private void installAssetApk(final String asset_name) {
		try {
			final String authority = getPackageManager().getProviderInfo(new ComponentName(this, AssetFileProvider.class), 0).authority;
			startActivity(new Intent(Intent.ACTION_INSTALL_PACKAGE, Uri.parse("content://" + authority + "/" + asset_name)).addFlags(FLAG_GRANT_READ_URI_PERMISSION));
		} catch (final NameNotFoundException | ActivityNotFoundException ignored) {}	// Should never happen
	}

	private int getPackageVersion(final String pkg) {
		try {
			return getPackageManager().getPackageInfo(pkg, 0).versionCode;
		} catch (final NameNotFoundException e) {
			return -1;
		}
	}

	@RequiresApi(N) @SuppressLint("NewApi")
	private static ApplicationInfo getApplicationInfo(final LauncherApps la, final String pkg, final UserHandle profile) {
		try {
			return la.getApplicationInfo(pkg, 0, profile);
		} catch (final NameNotFoundException e) { return null; }
	}

	@Override protected void onDestroy() {
		getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(mPreferencesChangeListener);
		super.onDestroy();
	}

	private boolean installNevolution(final @SuppressWarnings("unused") Preference preference) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APP_MARKET_PREFIX + NEVOLUTION_PACKAGE)));
		} catch (final ActivityNotFoundException ignored) {}	// TODO: Landing web page
		return true;
	}

	private boolean activate(final @SuppressWarnings("unused") Preference preference) {
		try {
			startActivityForResult(new Intent("com.oasisfeng.nevo.action.ACTIVATE_DECORATOR").setPackage(NEVOLUTION_PACKAGE)
					.putExtra("nevo.decorator", new ComponentName(this, WeChatDecorator.class))
					.putExtra("nevo.target", WECHAT_PACKAGE), 0);
		} catch (final ActivityNotFoundException e) {
			startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(NEVOLUTION_PACKAGE));
		}
		return true;
	}

	private boolean isPlayStoreSystemApp() {
		try {
			return (getPackageManager().getApplicationInfo(PLAY_STORE_PACKAGE, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
		} catch (final NameNotFoundException e) {
			return false;
		}
	}

	private final SharedPreferences.OnSharedPreferenceChangeListener mPreferencesChangeListener = (prefs, key) -> {
		Log.d(TAG, "Settings changed, notify decorator now.");
		sendBroadcast(new Intent(WeChatDecorator.ACTION_SETTINGS_CHANGED).setPackage(getPackageName()).putExtra(key, prefs.getBoolean(key, false)));
	};

	private final BroadcastReceiver mDummyReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context c, final Intent i) {}};
}
