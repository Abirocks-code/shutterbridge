package com.shutterbridge.app

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Google Play services wakes this service whenever the watch sends a message
 * on a path starting with /shutter, even if the app UI is closed.
 */
class ShutterListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Const.PATH_CAPTURE) return

        val delaySec = try {
            String(event.data).trim().toInt().coerceIn(0, 15)
        } catch (e: Exception) {
            0
        }
        val nodeId = event.sourceNodeId
        val ctx = applicationContext
        AppLog.add("Watch asked for a photo (delay ${delaySec}s)")

        fun reply(text: String) {
            AppLog.add("Result: $text")
            Wearable.getMessageClient(ctx)
                .sendMessage(nodeId, Const.PATH_RESULT, text.toByteArray())
        }

        val svc = ShutterAccessibilityService.instance
        if (svc == null) {
            reply("err:accessibility_off")
            return
        }
        svc.capture(delaySec * 1000L) { result -> reply(result) }
    }
}
