package com.oasisfeng.nevo.decorators.wechat.image;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import com.oasisfeng.nevo.decorators.wechat.R;
import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.io.File;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;

/**
 * Created by Oasis on 2018-11-30.
 */
public class WeChatImagePreviewDecorator extends NevoDecoratorService {

	private static final String KEY_DATA_MIME_TYPE = "type";
	private static final String KEY_DATA_URI= "uri";

	@Override protected void apply(final MutableStatusBarNotification evolving) {
		final MutableNotification n = evolving.getNotification();
		final CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
		if (text == null) return;
		if (! WeChatImageLoader.isImagePlaceholder(this, text.toString())) return;
		if (checkSelfPermission(READ_EXTERNAL_STORAGE) != PERMISSION_GRANTED) {
			n.addAction(new Notification.Action.Builder(null, getText(R.string.action_preview_image), WeChatImageLoader.buildPermissionRequest(this)).build());
			return;
		}
		if (mImageLoader == null) mImageLoader = new WeChatImageLoader(this);
		final File image = mImageLoader.loadImage();
		if (image == null) return;

		@SuppressLint("InlinedApi") final Parcelable[] messages = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
		if (SDK_INT >= P && messages != null && messages.length > 0) {
			final Object last = messages[messages.length - 1];
			if (! (last instanceof Bundle)) return;
			final Bundle last_message = (Bundle) last;
			if (last_message.containsKey(KEY_DATA_MIME_TYPE) && last_message.containsKey(KEY_DATA_URI)) return;
			last_message.putString(KEY_DATA_MIME_TYPE, "image/jpeg");
			last_message.putParcelable(KEY_DATA_URI, Uri.fromFile(image));	// TODO: Keep image mapping for previous messages.
		} else if (messages == null || messages.length == 1) {
			final BitmapFactory.Options options = new BitmapFactory.Options();
			options.inPreferredConfig = SDK_INT >= O ? Bitmap.Config.HARDWARE : Bitmap.Config.ARGB_8888;
			n.extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_BIG_PICTURE);
			n.extras.putParcelable(Notification.EXTRA_PICTURE, BitmapFactory.decodeFile(image.getPath(), options));
			n.extras.putCharSequence(Notification.EXTRA_SUMMARY_TEXT, text);
		}
	}

	private WeChatImageLoader mImageLoader;
}
