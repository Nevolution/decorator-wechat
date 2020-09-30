package com.oasisfeng.nevo.decorators.wechat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.util.Consumer

object CompatModeController {

	@JvmStatic fun query(context: Context, result_callback: (Consumer<Boolean>)?) {
		sendRequest(context, null, result_callback)
	}

	@JvmStatic fun request(context: Context, enabled: Boolean, result_callback: (Consumer<Boolean>)?) {
		sendRequest(context, enabled, result_callback)
	}

	private fun sendRequest(context: Context, enabled: Boolean?, callback: (Consumer<Boolean>)?) {
		val uri = Uri.fromParts("nevo", "compat", if (enabled == null) null else if (enabled) "1" else "0")
		context.sendOrderedBroadcast(Intent("", uri), null, object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {
			when (resultCode) {
				Activity.RESULT_FIRST_USER  -> callback?.accept(true)   // Changed (for request) or enabled (for query)
				Activity.RESULT_OK          -> callback?.accept(false)  // Unchanged
				else -> Log.e(WeChatDecorator.TAG, "Unexpected result code: $resultCode") }
		}}, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
	}
}