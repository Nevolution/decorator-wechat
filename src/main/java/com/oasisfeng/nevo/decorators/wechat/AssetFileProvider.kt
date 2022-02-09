package com.oasisfeng.nevo.decorators.wechat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Expose asset file with content URI
 *
 * Created by Oasis on 2019-1-28.
 */
class AssetFileProvider : ContentProvider() {

    @Throws(FileNotFoundException::class) override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val filename = uri.lastPathSegment ?: throw FileNotFoundException()
        val context = context!!
        return try {
            val suffix = context.getString(R.string.repacked_asset_suffix)
            val offset = context.resources.getInteger(R.integer.repacked_asset_offset)
            val afd = context.assets.openFd(
                if (suffix.isNotEmpty() && filename.endsWith(suffix)) filename + context.getString(R.string.repacked_asset_appendix) else filename)
            AssetFileDescriptor(afd.parcelFileDescriptor, afd.startOffset + offset, afd.length - offset)
        } catch (e: IOException) {
            null.also { Log.e(TAG, "Error opening asset", e) }
        }
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}