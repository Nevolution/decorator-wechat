package com.oasisfeng.nevo.decorators.wechat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

object CompatModeController {

	fun query(context: Context, callback: (Boolean) -> Unit) = sendRequest(context, null, callback)
	fun request(context: Context, enabled: Boolean, callback: (Boolean) -> Unit) = sendRequest(context, enabled, callback)

	private fun sendRequest(context: Context, enabled: Boolean?, callback: (Boolean) -> Unit) {
		val uri = Uri.fromParts("nevo", "compat", if (enabled == null) null else if (enabled) "1" else "0")
		context.sendOrderedBroadcast(Intent("", uri), null, object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {
			when (resultCode) {
				Activity.RESULT_FIRST_USER  -> callback(true)   // Changed (for request) or enabled (for query)
				Activity.RESULT_OK          -> callback(false)  // Unchanged
				else 						-> Log.e(TAG, "Unexpected result code: $resultCode") }
		}}, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
	}
}