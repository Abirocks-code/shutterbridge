package com.shutterbridge.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.wearable.Wearable

/** Setup + diagnostics screen for the phone companion. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var fxEdit: EditText
    private lateinit var fyEdit: EditText

    private val ui = Handler(Looper.getMainLooper())
    private var watchText = "Watch link: checking…"
    private var lastLog = ""
    private var tick = 0

    private val refresher = object : Runnable {
        override fun run() {
            if (tick % 5 == 0) checkWatch()
            tick++
            refresh()
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, p)
        }

        root.addView(TextView(this).apply {
            text = "Shutter Bridge"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusView)

        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("2. App info (restricted settings, autostart, pop-ups)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3. Battery: set to no restrictions") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        root.addView(button("Test: take a photo in 5 s") {
            val svc = ShutterAccessibilityService.instance
            if (svc == null) {
                toast("Enable the accessibility service first")
            } else {
                AppLog.add("Test started")
                svc.capture(5000L) { AppLog.add("Test result: $it") }
            }
        })
        root.addView(button("Diagnose camera screen") {
            val svc = ShutterAccessibilityService.instance
            if (svc == null) toast("Enable the accessibility service first")
            else {
                AppLog.add("Diagnose started")
                svc.diagnose()
            }
        })

        root.addView(TextView(this).apply {
            text = "Fallback tap position (% of screen), used only if the shutter can't be found:"
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fxEdit = numberField(Prefs.fx(this))
        fyEdit = numberField(Prefs.fy(this))
        row.addView(TextView(this).apply { text = "X " })
        row.addView(fxEdit, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply { text = "   Y " })
        row.addView(fyEdit, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(button("Save") {
            val x = fxEdit.text.toString().toFloatOrNull()?.coerceIn(0f, 100f)
            val y = fyEdit.text.toString().toFloatOrNull()?.coerceIn(0f, 100f)
            if (x == null || y == null) toast("Enter numbers between 0 and 100")
            else {
                Prefs.save(this, x, y)
                toast("Saved")
            }
        })
        root.addView(row)

        root.addView(button("Copy log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ShutterBridge log", AppLog.dump()))
            toast("Log copied")
        })

        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        tick = 0
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun checkWatch() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                watchText = if (nodes.isEmpty()) "Watch link: NOT connected"
                else "Watch link: " + nodes.joinToString { it.displayName } + " ✓"
            }
            .addOnFailureListener { e ->
                watchText = "Watch link error: ${e.message}"
            }
    }

    private fun refresh() {
        val running = ShutterAccessibilityService.instance != null
        val enabled = isServiceEnabled()
        val a11y = when {
            running -> "RUNNING ✓"
            enabled -> "enabled but not running (toggle it off and on)"
            else -> "OFF ✗  (step 1)"
        }
        statusView.text = "Accessibility service: $a11y\n$watchText"

        val log = AppLog.dump()
        if (log != lastLog) {
            lastLog = log
            logView.text = log
        }
    }

    private fun isServiceEnabled(): Boolean {
        val setting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val mine = ComponentName(this, ShutterAccessibilityService::class.java)
        return setting.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun numberField(value: Float): EditText =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(value.toString())
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
