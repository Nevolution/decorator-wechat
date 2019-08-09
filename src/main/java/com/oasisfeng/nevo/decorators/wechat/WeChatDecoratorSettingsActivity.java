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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.TwoStatePreference;
import android.support.annotation.Nullable;

import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.List;

import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.WECHAT_PACKAGE;

/**
 * Entry activity. Some ROMs (including Samsung, OnePlus) require a launcher activity to allow any component being bound by other app.
 */
@SuppressWarnings("deprecation") @SuppressLint("ExportedPreferenceActivity")
public class WeChatDecoratorSettingsActivity extends PreferenceActivity {

	private static final int CURRENT_AGENT_VERSION = 1200;
	private static final String NEVOLUTION_PACKAGE = "com.oasisfeng.nevo";
	private static final String ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead";
	private static final String AGENT_WECHAT_PACKAGE = "com.oasisfeng.nevo.agents.wechat";
	private static final String PLAY_STORE_PACKAGE = "com.android.vending";
	private static final String APP_MARKET_PREFIX = "market://details?id=";

	@Override protected void onCreate(@Nullable final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (SDK_INT >= N) //noinspection deprecation
			getPreferenceManager().setStorageDeviceProtected();
		//noinspection deprecation
		addPreferencesFromResource(R.xml.settings);
	}

	@Override protected void onResume() {
		super.onResume();
		final PackageManager pm = getPackageManager();
		PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(mPreferencesChangeListener);

		final Preference preference_activate = findPreference(getString(R.string.pref_activate));
		boolean nevolution_installed = false, wechat_installed = false, running = false;
		try {
			pm.getApplicationInfo(NEVOLUTION_PACKAGE, 0);
			nevolution_installed = true;
			pm.getApplicationInfo(WECHAT_PACKAGE, PackageManager.GET_UNINSTALLED_PACKAGES);
			wechat_installed = true;
			running = isDecoratorRunning();
		} catch (final PackageManager.NameNotFoundException ignored) {}

		preference_activate.setEnabled(! nevolution_installed || wechat_installed);		// No reason to promote WeChat if not installed.
		preference_activate.setSelectable(! running);
		preference_activate.setSummary(! nevolution_installed ? getText(R.string.pref_activate_summary_nevo_not_installed)
				: ! wechat_installed ? getText(R.string.pref_activate_summary_wechat_not_installed)
				: running ? getText(R.string.pref_activate_summary_already_activated) : null);
		preference_activate.setOnPreferenceClickListener(! nevolution_installed ? this::installNevolution
				: wechat_installed && ! running ? this::activate : null);

		final Preference preference_extension = findPreference(getString(R.string.pref_extension));
		final boolean android_auto_available = getPackageVersion(ANDROID_AUTO_PACKAGE) >= 0;
		preference_extension.setEnabled(wechat_installed);
		preference_extension.setSelectable(! android_auto_available);
		preference_extension.setSummary(android_auto_available ? R.string.pref_extension_summary_installed : R.string.pref_extension_summary);
		preference_extension.setOnPreferenceClickListener(android_auto_available ? null : this::installExtension);

		final Preference preference_agent = findPreference(getString(R.string.pref_agent));
		final int agent_version = getPackageVersion(AGENT_WECHAT_PACKAGE);
		preference_agent.setEnabled(wechat_installed);
		preference_agent.setSummary(agent_version < 0 ? R.string.pref_agent_summary
				: agent_version >= CURRENT_AGENT_VERSION ? R.string.pref_agent_summary_installed : R.string.pref_agent_summary_update);
		preference_agent.setOnPreferenceClickListener(agent_version >= CURRENT_AGENT_VERSION ? pref -> selectAgentLabel()
				: pref -> installAssetApk("agent.apk"));

		final TwoStatePreference preference_hide = (TwoStatePreference) findPreference(getString(R.string.pref_hide));
		if (preference_hide != null) {
			final ComponentName component = new ComponentName(this, getClass());
			final int state = pm.getComponentEnabledSetting(component);
			preference_hide.setChecked(state == COMPONENT_ENABLED_STATE_DISABLED);
			preference_hide.setOnPreferenceChangeListener(this::toggleHidingLauncherIcon);
		}

		try {
			findPreference(getString(R.string.pref_version)).setSummary(pm.getPackageInfo(getPackageName(), 0).versionName);
		} catch (final PackageManager.NameNotFoundException ignored) {}
	}

	private boolean selectAgentLabel() {
		final PackageManager pm = getPackageManager();
		final Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(AGENT_WECHAT_PACKAGE);
		final List<ResolveInfo> resolves = pm.queryIntentActivities(query, PackageManager.GET_DISABLED_COMPONENTS);
		final int size = resolves.size();
		if (size <= 1) throw new IllegalStateException("No activities found for " + query);
		final CharSequence[] labels = new CharSequence[size];
		final String[] names = new String[size];
		for (int i = 0; i < size; i ++) {
			final ActivityInfo activity = resolves.get(i).activityInfo;
			labels[i] = activity.loadLabel(pm);
			names[i] = activity.name;
		}
		new AlertDialog.Builder(this).setSingleChoiceItems(labels, -1, (dialog, which) -> {
			for (int i = 0; i < names.length; i ++)
				pm.setComponentEnabledSetting(new ComponentName(AGENT_WECHAT_PACKAGE, names[i]),
						i == which ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
			dialog.dismiss();
		}).show();
		return true;
	}

	private boolean toggleHidingLauncherIcon(@SuppressWarnings("unused") final Preference unused, final Object value) {
		final boolean start_from_launcher = getIntent().getAction() != null;
		final boolean standalone = getPackageName().equals(BuildConfig.APPLICATION_ID);
		// Always allow hiding if bundled and decorator is inactive, but it will be re-enabled automatically upon next decorator activation.
		if (start_from_launcher && value == Boolean.TRUE && (standalone || isDecoratorRunning())) {
			new AlertDialog.Builder(this).setMessage(R.string.prompt_hide_prerequisite).setPositiveButton(android.R.string.cancel, null).show();
			return false;
		}
		getPackageManager().setComponentEnabledSetting(new ComponentName(this, getClass()), value != Boolean.TRUE ? COMPONENT_ENABLED_STATE_ENABLED
				: standalone ? COMPONENT_ENABLED_STATE_DISABLED : COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP);
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

	private void showAndroidAutoInPlayStore() {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + ANDROID_AUTO_PACKAGE))
					.setPackage(PLAY_STORE_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
		} catch(final ActivityNotFoundException e) { /* In case of Google Play malfunction */ }
	}

	private void installDummyAuto() {
		installAssetApk("dummy-auto.apk");
	}

	private boolean installAssetApk(final String asset_name) {
		try {
			final String authority = getPackageManager().getProviderInfo(new ComponentName(this, AssetFileProvider.class), 0).authority;
			startActivity(new Intent(Intent.ACTION_INSTALL_PACKAGE, Uri.parse("content://" + authority + "/" + asset_name)).addFlags(FLAG_GRANT_READ_URI_PERMISSION));
		} catch (final PackageManager.NameNotFoundException | ActivityNotFoundException ignored) {}	// Should never happen
		return true;
	}

	private int getPackageVersion(final String pkg) {
		try {
			return getPackageManager().getPackageInfo(pkg, 0).versionCode;
		} catch (final PackageManager.NameNotFoundException e) {
			return -1;
		}
	}

	static void enableLauncherEntranceIfNotYet(final Context context) {
		final ComponentName settings_component = new ComponentName(context, WeChatDecoratorSettingsActivity.class);
		final int state = context.getPackageManager().getComponentEnabledSetting(settings_component);
		if (state == COMPONENT_ENABLED_STATE_DEFAULT)
			context.getPackageManager().setComponentEnabledSetting(settings_component, COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP);
	}

	@Override protected void onPause() {
		super.onPause();
		PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(mPreferencesChangeListener);
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
		} catch (final PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	private final SharedPreferences.OnSharedPreferenceChangeListener mPreferencesChangeListener = (prefs, key)
			-> sendBroadcast(new Intent(WeChatDecorator.ACTION_SETTINGS_CHANGED).setPackage(getPackageName()));

	private final BroadcastReceiver mDummyReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context c, final Intent i) {}};
}
