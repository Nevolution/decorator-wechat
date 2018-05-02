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

package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.app.Notification.BigTextStyle;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.widget.Toast;

import java.util.Objects;

import static android.app.Notification.PRIORITY_MAX;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

/**
 * Created by Oasis on 2018/4/26.
 */
public class WeChatDecoratorSettingsReceiver extends BroadcastReceiver {

	private static final String ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead";

	@Override public void onReceive(final Context context, final Intent intent) {
		if (intent.getAction() == null) {
			installAndroidAuto(context);
			return;
		}
		try {
			context.getPackageManager().getApplicationInfo(ANDROID_AUTO_PACKAGE, 0);
		} catch (final PackageManager.NameNotFoundException e) {
			promptToInstallAndroidAuto(context);
			return;
		}
		Toast.makeText(context, "Settings still under construction", Toast.LENGTH_LONG).show();
	}

	private void installAndroidAuto(final Context context) {
		final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ANDROID_AUTO_PACKAGE)).addFlags(FLAG_ACTIVITY_NEW_TASK);
		try {
			context.startActivity(intent);
		} catch(final ActivityNotFoundException e) {
			try {
				context.startActivity(intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=" + ANDROID_AUTO_PACKAGE)));
			} catch (final ActivityNotFoundException ignored) {}
		}
	}

	private void promptToInstallAndroidAuto(final Context context) {
		final Notification n = new Notification.Builder(context).setSmallIcon(Icon.createWithResource(context, android.R.drawable.stat_notify_more))
				.setContentTitle(context.getText(R.string.settings_experience_upgrade_title))
				.setContentText(context.getText(R.string.settings_experience_upgrade_text))
				.setSubText(context.getText(R.string.decorator_wechat_title))
				.setContentIntent(getSelfPendingIntent(context, FLAG_UPDATE_CURRENT)).setAutoCancel(true)
				.setStyle(new BigTextStyle()).setLocalOnly(true).setShowWhen(false).setPriority(PRIORITY_MAX).setColor(0xFF33B332).build();
		showNotification(context, n);
	}

	private PendingIntent getSelfPendingIntent(final Context context, final int flags) {
		return PendingIntent.getBroadcast(context, 0, new Intent(context, getClass()), flags);
	}

	private void showNotification(final Context context, final Notification n) {
		Objects.requireNonNull((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(0, n);
	}
}
