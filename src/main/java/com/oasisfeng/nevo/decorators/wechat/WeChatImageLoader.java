package com.oasisfeng.nevo.decorators.wechat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

/**
 * Created by Oasis on 2018-8-7.
 */
public class WeChatImageLoader {

	private static final long MAX_TIME_DIFF = 2_000;
	private static final File WECHAT_PATH = new File(Environment.getExternalStorageDirectory(), "/Tencent/MicroMsg");

	static boolean isImagePlaceholder(final Context context, final String text) {
		if (sPlaceholders == null) sPlaceholders = Arrays.asList(context.getResources().getStringArray(R.array.text_placeholders_for_picture));
		return sPlaceholders.contains(text);	// Search throughout languages since WeChat can be configured to different language than current system language.
	}

	@SuppressLint("MissingPermission") @RequiresPermission(READ_EXTERNAL_STORAGE) File loadImage() {
		if (mAccountRootPath == null) return null;
		final long now = System.currentTimeMillis();
		File path = mAccountRootPath;
		while (path.isDirectory()) {
			File best_match = null;
			long best_time_diff = Long.MAX_VALUE;
			for (final File child : path.listFiles()) {
				final String name = child.getName();
				final boolean is_file = child.isFile(), large = is_file && name.endsWith(".jpg");
				if (is_file) {
					if (! large && ! name.startsWith("th_")) continue;	// "th_xxx" is thumbnail.
				} else if (! child.isDirectory()) continue;
				final long time_diff = now - child.lastModified();
				if (time_diff > 0 && time_diff < MAX_TIME_DIFF) {
					if (large) {	// Always prefer large image
						best_match = child;
						break;
					}
					if (! is_file && Math.abs(time_diff - best_time_diff) < MAX_TIME_DIFF) return null;		// Drop indistinct case to avoid mismatch
					if (time_diff >= best_time_diff) continue;
					best_match = child;
					best_time_diff = time_diff;
				}
			}
			if (best_match == null) return null;
			path = best_match;
		}
		Log.d(TAG, "Image loaded: " + path.getPath());
		return path;
	}

	WeChatImageLoader(final Context context) {
		mContext = context;
		File root = null;
		final File[] files = WECHAT_PATH.listFiles();
		if (files != null) for (final File file : files) {
			if (file.getName().length() != 32) continue;		// All account paths are 32 hex chars.
			if (root == null || file.lastModified() > root.lastModified()) root = file;
		}
		if (root == null) {
			mAccountRootPath = null;
			Log.e(TAG, "No account path (32 hex chars) found in " + WECHAT_PATH);
		} else mAccountRootPath = new File(root, "image2");
	}

	@RequiresPermission(READ_EXTERNAL_STORAGE) void startObserver() {
		mObserver = new FileObserver(WECHAT_PATH.getAbsolutePath(), FileObserver.ALL_EVENTS) {

			@WorkerThread @Override public void onEvent(final int event, final String path) {
				final String msg = "Event " + event + ": " + path;
				Log.i(TAG, msg);
				mMainThreadHandler.post(() -> Toast.makeText(mContext, msg, Toast.LENGTH_LONG).show());
			}
		};
		mObserver.startWatching();
	}

	static PendingIntent buildPermissionRequest(final Context context) {
		return PendingIntent.getActivity(context, 0, new Intent(context, WeChatImageLoader.PermissionRequestActivity.class), FLAG_UPDATE_CURRENT);
	}

	private final Context mContext;
	private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());
	private final @Nullable File mAccountRootPath;
	private static List<String> sPlaceholders;
	@SuppressWarnings("FieldCanBeLocal") private FileObserver mObserver;	// FileObserver must be strong-referenced
	private static final String TAG = "Nevo.WeChatPic";

	public static class PermissionRequestActivity extends Activity {

		@Override protected void onResume() {
			super.onResume();
			if (checkSelfPermission(READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
				Toast.makeText(getApplicationContext(), getText(R.string.toast_image_preview_activated), Toast.LENGTH_LONG).show();
				finish();
			} else requestPermissions(new String[] { READ_EXTERNAL_STORAGE }, 0);
		}

		@Override public void onRequestPermissionsResult(final int requestCode, final @NonNull String[] permissions, final @NonNull int[] grantResults) {
			if (grantResults.length == 1 && READ_EXTERNAL_STORAGE.equals(permissions[0]) && grantResults[0] == PackageManager.PERMISSION_GRANTED)
				Toast.makeText(getApplicationContext(), getText(R.string.toast_image_preview_activated), Toast.LENGTH_LONG).show();
			finish();
		}
	}
}
