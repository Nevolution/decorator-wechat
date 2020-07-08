package com.oasisfeng.nevo.decorators.wechat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

/**
 * Expose asset file with content URI
 *
 * Created by Oasis on 2019-1-28.
 */
public class AssetFileProvider extends ContentProvider {

	@Nullable @Override public AssetFileDescriptor openAssetFile(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
		final String filename = uri.getLastPathSegment();
		if (filename == null) throw new FileNotFoundException();
		final Context context = Objects.requireNonNull(getContext());
		try {
			final String suffix = context.getString(R.string.repacked_asset_suffix);
			final int offset = context.getResources().getInteger(R.integer.repacked_asset_offset);
			final AssetFileDescriptor afd = Objects.requireNonNull(context).getAssets().openFd(
					! suffix.isEmpty() && filename.endsWith(suffix) ? filename + context.getString(R.string.repacked_asset_appendix) : filename);
			return new AssetFileDescriptor(afd.getParcelFileDescriptor(), afd.getStartOffset() + offset, afd.getLength() - offset);
		} catch (final IOException e) {
			Log.e(WeChatDecorator.TAG, "Error opening asset", e);
			return null;
		}
	}

	@Override public boolean onCreate() { return true; }
	@Nullable @Override public Cursor query(@NonNull final Uri uri, @Nullable final String[] projection, @Nullable final String selection, @Nullable final String[] selectionArgs, @Nullable final String sortOrder) { return null; }
	@Nullable @Override public String getType(@NonNull final Uri uri) { return null; }
	@Nullable @Override public Uri insert(@NonNull final Uri uri, @Nullable final ContentValues values) { return null; }
	@Override public int delete(@NonNull final Uri uri, @Nullable final String selection, @Nullable final String[] selectionArgs) { return 0; }
	@Override public int update(@NonNull final Uri uri, @Nullable final ContentValues values, @Nullable final String selection, @Nullable final String[] selectionArgs) { return 0; }
}
