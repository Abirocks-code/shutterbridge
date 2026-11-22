package com.shutterbridge.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

private const val PATH_CAPTURE = "/shutter/capture"
private const val PATH_RESULT = "/shutter/result"

/**
 * One big shutter button.
 *  - tap        : take a photo now
 *  - long press : take a photo after a 3 second timer
 * The watch only sends a message; the phone companion presses the real camera shutter.
 */
class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {

    private lateinit var button: TextView
    private lateinit var status: TextView
    private var countdown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val p = dp(14)
            setPadding(p, p, p, p)
        }

        button = TextView(this).apply {
            text = "SHUTTER"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(6), Color.parseColor("#90CAF9"))
            }
            isClickable = true
            setOnClickListener { requestCapture(0) }
            setOnLongClickListener {
                requestCapture(3)
                true
            }
        }
        root.addView(button, LinearLayout.LayoutParams(dp(112), dp(112)))

        status = TextView(this).apply {
            text = "Tap: photo  ·  Hold: 3 s timer"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        countdown?.cancel()
        super.onPause()
    }

    private fun requestCapture(delaySec: Int) {
        countdown?.cancel()
        status.text = "Contacting phone…"

        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    status.text = "Phone not connected"
                    return@addOnSuccessListener
                }
                for (node in nodes) {
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, PATH_CAPTURE, delaySec.toString().toByteArray())
                        .addOnSuccessListener {
                            if (delaySec > 0) startCountdown(delaySec)
                            else status.text = "Sent, waiting for phone…"
                        }
                        .addOnFailureListener { e ->
                            status.text = "Send failed: ${e.message}"
                        }
                }
            }
            .addOnFailureListener { e ->
                status.text = "Link error: ${e.message}"
            }
    }

    private fun startCountdown(seconds: Int) {
        countdown = object : CountDownTimer(seconds * 1000L, 250L) {
            override fun onTick(msLeft: Long) {
                status.text = "Photo in ${(msLeft + 999) / 1000} s…"
            }

            override fun onFinish() {
                status.text = "Waiting for phone…"
            }
        }.start()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_RESULT) return
        val result = String(event.data)
        runOnUiThread {
            countdown?.cancel()
            status.text = when (result) {
                "ok" -> "Photo taken ✓"
                "err:accessibility_off" -> "Phone: enable the Shutter Bridge accessibility service"
                "err:camera_not_open" -> "Phone: camera did not open (unlock phone / allow pop-ups)"
                "err:shutter_not_found" -> "Phone: shutter button not found"
                "err:gesture_failed" -> "Phone: tap on shutter failed"
                else -> "Phone said: $result"
            }
            if (result == "ok" && Build.VERSION.SDK_INT >= 30) {
                button.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
