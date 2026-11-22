package com.shutterbridge.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses the shutter of the phone's STOCK camera app (so you keep Xiaomi's full image processing).
 *
 * Flow for each watch request:
 *   1. open the camera (if it isn't already in front)
 *   2. wait until its window is active (and until the requested timer delay has passed)
 *   3. find the shutter button in the accessibility tree and tap it with a real touch gesture
 *      (falls back to a configurable screen position if the button can't be found)
 *   4. report "ok" or "err:<reason>" back to the watch
 *
 * The service only looks at the screen while handling a request; onAccessibilityEvent does nothing.
 */
class ShutterAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ShutterAccessibilityService? = null

        // Stock Xiaomi / AOSP camera, and Google Camera.
        val CAMERA_PACKAGES = setOf("com.android.camera", "com.google.android.GoogleCamera")

        // Known / likely view ids of the shutter button (without package prefix).
        private val SHUTTER_IDS = listOf(
            "v9_shutter_button_internal",
            "shutter_button_internal",
            "shutter_button",
            "shutter"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.add("Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AppLog.add("Accessibility service stopped")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: we only act when the watch asks.
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------- public API

    /** Take one photo with the stock camera after [delayMs]; [done] gets "ok" or "err:<reason>". */
    fun capture(delayMs: Long, done: (String) -> Unit) {
        handler.post {
            val started = SystemClock.elapsedRealtime()
            if (!isCameraForeground()) launchCamera()
            waitForCamera(started, 0, delayMs, done)
        }
    }

    /** Opens the camera and writes the ids / descriptions it can see into the app log. */
    fun diagnose() {
        handler.post {
            if (!isCameraForeground()) launchCamera()
            handler.postDelayed({
                val root = rootInActiveWindow
                if (root == null) {
                    AppLog.add("Diagnose: no active window readable")
                    return@postDelayed
                }
                AppLog.add("Diagnose: foreground package = ${root.packageName}")
                dumpTree(root)
            }, 3000)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun isCameraForeground(): Boolean =
        rootInActiveWindow?.packageName?.toString() in CAMERA_PACKAGES

    private fun wakeScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "shutterbridge:wake"
            )
            wl.acquire(15_000L)
        } catch (e: Exception) {
            AppLog.add("Wake failed: ${e.message}")
        }
    }

    private fun launchCamera() {
        wakeScreen()
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val action = if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLog.add("Launching camera (${if (locked) "secure, from lock screen" else "normal"})")
        } catch (e: Exception) {
            AppLog.add("Camera launch failed: ${e.message}")
        }
    }

    private fun waitForCamera(started: Long, attempt: Int, delayMs: Long, done: (String) -> Unit) {
        if (isCameraForeground()) {
            val remaining = (started + delayMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            // A freshly launched camera needs a moment to show its preview.
            val settle = if (attempt == 0) 300L else 900L
            handler.postDelayed({ press(done) }, maxOf(remaining, settle))
            return
        }
        if (attempt >= 24) {
            AppLog.add("Camera never came to the front (pop-up permission? locked?)")
            done("err:camera_not_open")
            return
        }
        handler.postDelayed({ waitForCamera(started, attempt + 1, delayMs, done) }, 250L)
    }

    private fun press(done: (String) -> Unit) {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() !in CAMERA_PACKAGES) {
            done("err:camera_not_open")
            return
        }

        val node = findShutter(root)
        if (node != null) {
            val r = Rect()
            node.getBoundsInScreen(r)
            AppLog.add(
                "Shutter node: id=${node.viewIdResourceName} desc=${node.contentDescription} " +
                    "bounds=$r clickable=${node.isClickable}"
            )
            if (r.width() > 0 && r.height() > 0) {
                tap(r.exactCenterX(), r.exactCenterY()) { result ->
                    if (result == "ok") done("ok")
                    else done(if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "ok" else result)
                }
                return
            }
            done(if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "ok" else "err:shutter_not_found")
            return
        }

        // Fallback: tap at the configured screen position.
        val dm = resources.displayMetrics
        val x = dm.widthPixels * Prefs.fx(this) / 100f
        val y = dm.heightPixels * Prefs.fy(this) / 100f
        AppLog.add("Shutter node not found; tapping fallback position ${x.toInt()},${y.toInt()}")
        tap(x, y, done)
    }

    private fun tap(x: Float, y: Float, done: (String) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                AppLog.add("Tap completed at ${x.toInt()},${y.toInt()}")
                done("ok")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                AppLog.add("Tap was cancelled by the system")
                done("err:gesture_failed")
            }
        }, handler)
        if (!accepted) {
            AppLog.add("Tap was not accepted")
            done("err:gesture_failed")
        }
    }

    private fun findShutter(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) Known ids.
        for (pkg in CAMERA_PACKAGES) {
            for (id in SHUTTER_IDS) {
                val hits = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                if (!hits.isNullOrEmpty()) return hits[0]
            }
        }
        // 2) Anything in the tree whose id or description mentions "shutter".
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val n = queue.removeFirst()
            visited++
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
            if (id.contains("shutter") || desc.contains("shutter")) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun dumpTree(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var logged = 0
        while (queue.isNotEmpty() && visited < 800 && logged < 90) {
            val n = queue.removeFirst()
            visited++
            val id = n.viewIdResourceName.orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            if (id.isNotEmpty() || desc.isNotEmpty()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                AppLog.add("  id=$id | desc=$desc | ${n.className} | $r | clickable=${n.isClickable}")
                logged++
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        AppLog.add("Diagnose: listed $logged nodes ($visited visited)")
    }
}
